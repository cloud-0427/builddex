# Release 构建一致性锁实施计划

## 1. 目标

本计划为每个 Android 应用版本建立唯一、不可歧义的 Jiagu Release，并保证：

- 同一公司内，一个 `packageName + versionCode` 只有一个 Release 和一个标准 Payload；
- DRAFT 可以原地修订，PUBLISHED 和 REVOKED 永不允许替换；
- 已发布版本的业务 DEX、资源、Manifest、assets、Native Library 或签名证书集合发生变化时，构建以 HTTP 409 失败；
- 一个标准 Payload 仍按设备动态派生不同 Payload Key 和不同设备密文，不影响一机一身份；
- APK、ABI Split APK、资源 Split APK 和 AAB 使用同一套 Variant 级构建指纹；
- 所有 JSON API 使用统一的 `code/message/details` 响应信封；
- RSA Key 封装统一使用 RSA-OAEP-SHA1、MGF1-SHA1 和空 Label。

本轮允许清空现有公司数据库并重新构建，不实施旧 Schema 迁移和旧客户端兼容。

## 2. 已确认的约束

### 2.1 Release 身份

数据库内唯一约束：

```text
UNIQUE(package_name, version_code)
```

`payloadId` 固定为 `app-main`，`payloadVersion` 等于 `versionCode`，但二者不参与唯一约束。

同版本 Debug 与 Release 内容不同是预期冲突。PUBLISHED 后继续构建相同 `packageName + versionCode` 的 Debug Variant 时，插件必须输出明确日志，提示提升 `versionCode` 或为 Debug 配置不同 `applicationIdSuffix`。

### 2.2 锁定范围

锁定以下最终构建内容：

1. D8 输出的全部业务 DEX；
2. 资源收缩和 Jiagu 资源混淆后的 `AndroidManifest.xml`、`resources.arsc`、`res/**`、`assets/**`；
3. 所有 ABI 的最终 Strip 后 `.so`，包括应用、第三方依赖和 `libjiagu-core.so`。

排除：

- `META-INF/**`；
- 包签名块、ZIP 时间戳、压缩级别等容器元数据；
- 包含 Release RuntimeConfig 的 `liblog_ext.so`，避免 `releaseId -> liblog_ext.so -> nativeHash -> releaseId` 循环。

该锁是构建期版本一致性锁，不在 Android 启动时扫描 Play 生成的 Split APK 重新计算资源或 Native 摘要。运行时完整性继续依赖签名证书、versionCode、Play Integrity、服务端签名 Grant、Payload Hash 和 AES-GCM。

### 2.3 签名证书集合

Release 保存排序、去重后的 `certificateSha256Digests`：

- APK/Debug 默认加入当前 Variant signingConfig 的证书；
- AAB 正式发布必须显式加入 Play App Signing 证书；
- 可同时加入侧载证书和证书轮换历史；
- 设备实际安装证书命中集合中任意一项即可；
- DRAFT 修改证书集合会轮换 Payload Key；
- PUBLISHED 后证书集合不可修改。

所有证书摘要使用 SHA-256 Base64URL 无填充格式。服务端对集合排序、去重，并计算：

```text
certificateSetSha256 = SHA-256(
    canonical("JIAGU-CERTIFICATE-SET-V1", sortedCertificateDigests...)
)
```

### 2.4 计数

采用版本计数：

- 首次创建 `packageName + versionCode` 时 `pack_count + 1`；
- DRAFT 更新、幂等重试、PUBLISHED 复用和所有失败请求不增加；
- 并发创建只能有一个事务增加计数。

## 3. 构建摘要规范

所有摘要均为 SHA-256 Base64URL 无填充编码。Canonical 编码使用 UTF-8 字节长度前缀：

```text
{UTF-8字节长度}:{值}\n
```

### 3.1 业务 DEX

对 D8 最终输出的 `classes.dex`、`classes2.dex` 等按文件名排序，计算：

```text
businessDexSha256 = SHA-256(
    canonical(
        "JIAGU-BUSINESS-DEX-V1",
        dexPath1, dexLength1, SHA-256(dexBytes1),
        dexPath2, dexLength2, SHA-256(dexBytes2),
        ...
    )
)
```

不得直接对临时 `businessJar` 或 ZIP 原始字节计算摘要。服务端解析 JG3 后应独立复算该摘要，拒绝客户端摘要与 Payload 内容不一致的请求。

### 3.2 资源和 Manifest

对最终资源包解包后的目标 Entry 按规范化路径排序，计算：

```text
resourcesSha256 = SHA-256(
    canonical(
        "JIAGU-RESOURCES-V1",
        entryPath1, entryLength1, SHA-256(entryBytes1),
        ...
    )
)
```

包含：

- `AndroidManifest.xml`；
- `resources.arsc`；
- `res/**`；
- `assets/**`。

资源混淆必须先改造成确定性过程：目录、文件和 ZIP Entry 全部排序，生成 Entry 使用固定时间戳。摘要本身仍忽略时间戳、压缩方法和 CRC 等容器元数据。

### 3.3 Native Library

对所有 ABI 的最终 Strip 后 `.so` 按 `ABI/文件名` 排序：

```text
nativeLibsSha256 = SHA-256(
    canonical(
        "JIAGU-NATIVE-LIBS-V1",
        abiPath1, fileLength1, SHA-256(fileBytes1),
        ...
    )
)
```

`liblog_ext.so` 必须排除；同名文件在不同 ABI 下使用不同路径参与摘要。

### 3.4 Release 总摘要

```text
releaseBuildSha256 = SHA-256(
    canonical(
        "JIAGU-RELEASE-BUILD-V1",
        businessDexSha256,
        resourcesSha256,
        nativeLibsSha256
    )
)
```

服务端根据三个组件摘要复算总摘要，不信任客户端直接提交的总摘要。三个组件摘要和总摘要都保存，用于冲突定位。

## 4. Gradle 任务重构

将当前单一 `JiaguTask` 拆成：

| Task | 主要输入 | 主要输出 | 网络 |
|---|---|---|---:|
| `prepareJiaguBusinessDex<Variant>` | ScopedArtifact.CLASSES | JG3 Payload、business DEX 摘要 | 否 |
| `obfuscateJiaguResources<Variant>` | 最终链接/收缩资源 | 确定性资源包 | 否 |
| `hashJiaguResources<Variant>` | 混淆后资源包、最终 Manifest | resources 摘要 | 否 |
| `hashJiaguNativeInputs<Variant>` | 合并并 Strip 后的 Variant Native 输入 | native 摘要 | 否 |
| `createJiaguRelease<Variant>` | Payload、三个摘要、版本身份、证书集合 | release metadata | 是 |
| `generateJiaguRuntimeConfig<Variant>` | release metadata、固定服务端公钥 | RuntimeConfig | 否 |
| `generateJiaguPayloadLibrary<Variant>` | RuntimeConfig | 各 ABI `liblog_ext.so` | 否 |
| `verifyJiaguArtifact<Variant>` | 最终 APK/AAB | 校验标记 | 否 |
| 构建结束发布 Flow | invocation ID、整体构建结果、releaseId | PUBLISHED 状态 | 是 |

依赖关系：

```text
业务 DEX ──────────┐
最终资源摘要 ──────┼──> Create/Update DRAFT
Native 输入摘要 ───┘             │
                                  ▼
                         RuntimeConfig/liblog_ext.so
                                  │
                                  ▼
                           APK/Split APK/AAB
                                  │
                                  ▼
                            最终产物校验
                                  │
                                  ▼
                               PUBLISH
```

要求：

- 网络 Task 禁用 Gradle Build Cache，不把公司 API Key声明成可共享缓存输入；
- 同一 Variant 所有 Output 必须使用同一个 versionCode，否则构建失败；
- Variant 聚合所有 ABI、语言和 density 输入；不对 Google Play 最终生成的设备 APK 求摘要；
- 同一次 Gradle 调用同时请求 APK 和 AAB 时，只有两个目标都成功才发布；
- APK/AAB 失败时保留 DRAFT，不允许提前发布。

## 5. 服务端数据模型

`payload_releases` 使用全新 Schema，至少包含：

| 字段 | 说明 |
|---|---|
| release_id | DRAFT 首次创建后保持不变 |
| payload_id | 固定 `app-main` |
| payload_version | 等于 versionCode |
| package_name | 最终 applicationId |
| version_code | Variant 所有 Output 的统一 versionCode |
| certificate_sha256_digests_json | 排序去重后的证书摘要 JSON 数组 |
| certificate_set_sha256 | 证书集合摘要 |
| business_dex_sha256 | 最终业务 DEX 摘要 |
| resources_sha256 | 最终资源/Manifest/assets 摘要 |
| native_libs_sha256 | 最终 Native 集合摘要 |
| release_build_sha256 | 三组件总摘要 |
| plaintext_sha256 | JG3 Payload 明文摘要 |
| canonical_ciphertext_sha256 | 标准密文摘要 |
| canonical_payload | 标准加密 Payload |
| canonical_key_ciphertext | 公司 KEK 封装的随机 Canonical Key |
| payload_key_version | DRAFT 绑定变化时递增 |
| status | DRAFT、PUBLISHED、REVOKED |
| created_at/updated_at | 创建和最近更新时间 |
| published_at/revoked_at | 状态时间 |

唯一约束为 `UNIQUE(package_name, version_code)`。

## 6. Release 状态机和事务

| 现有状态 | 请求内容 | 服务端行为 | 响应 code |
|---|---|---|---|
| 不存在 | 合法 | 创建 DRAFT，KeyVersion=1，pack_count+1 | `RELEASE_CREATED` |
| DRAFT | 所有绑定字段相同 | 原样返回，不换 Key | `RELEASE_REUSED` |
| DRAFT | 任一组件、Payload 或证书集合变化 | 保留 releaseId，重加密 Payload，KeyVersion+1 | `RELEASE_UPDATED` |
| PUBLISHED | 所有绑定字段相同 | 原样返回 | `RELEASE_REUSED` |
| PUBLISHED | 任一绑定字段变化 | HTTP 409 | `PUBLISHED_VERSION_MODIFIED` |
| REVOKED | 任意 | HTTP 409 | `REVOKED_VERSION_REUSE_FORBIDDEN` |

“绑定字段”包括 packageName、versionCode、payloadId、payloadVersion、证书集合、三个组件摘要、总摘要和 Payload 明文摘要。

读取当前行、状态判断、KeyVersion 递增、Payload/AAD 加密、数据库更新和 `pack_count` 计数必须使用同一事务或等价 CAS，防止 DRAFT 更新与 PUBLISH 并发穿透。

## 7. API 响应协议

所有 JSON 成功和失败响应统一为：

```json
{
  "code": "STABLE_MACHINE_CODE",
  "message": "Human readable message.",
  "details": {}
}
```

约束：

- `code` 是稳定枚举，客户端不得解析 message；
- `details` 永远是 JSON Object，没有内容时为 `{}`；
- HTTP 状态码保留语义；
- `/unpack/download` 成功返回 `application/octet-stream`，失败返回统一 JSON；
- 列表统一放在 `details.items`；
- 现有服务端管理页面、插件、Runtime 和测试必须同步适配。

创建/更新 Release 的 multipart 字段：

- `payloadId`；
- `payloadVersion`；
- `packageName`；
- `versionCode`；
- 一个或多个重复的 `certificateSha256Digest`；
- `businessDexSha256`；
- `resourcesSha256`；
- `nativeLibsSha256`；
- `payload`。

冲突响应示例：

```json
{
  "code": "PUBLISHED_VERSION_MODIFIED",
  "message": "Published application version cannot be modified. Increase versionCode.",
  "details": {
    "packageName": "com.example.app",
    "versionCode": 105,
    "changedComponents": ["BUSINESS_DEX", "RESOURCES", "NATIVE_LIBS", "SIGNING_CERTIFICATES"]
  }
}
```

服务端不返回完整旧 Hash；插件将该错误转换为高可见 GradleException。

## 8. RuntimeConfig 和密码学绑定

RuntimeConfig 升级为版本 2，至少包含：

```json
{
  "configVersion": 2,
  "serverUrl": "https://jiagu.example.com",
  "companyId": "acme",
  "releaseId": "...",
  "payloadId": "app-main",
  "payloadVersion": 105,
  "packageName": "com.example.app",
  "versionCode": 105,
  "certificateSha256Digests": ["..."],
  "certificateSetSha256": "...",
  "payloadPlaintextSha256": "...",
  "releaseBuildSha256": "...",
  "payloadKeyVersion": 3,
  "serverKeyId": "company-sign-v1",
  "serverPublicKey": "...",
  "wrapAlgorithm": "RSA-OAEP-SHA1"
}
```

`releaseBuildSha256` 和 `certificateSetSha256` 加入 Canonical Payload AAD。`releaseBuildSha256`、设备实际证书摘要和 `payloadKeyVersion` 加入 DevicePayloadKey 派生、Grant 和 Device Payload AAD。

证书集合使 ENROLL canonical message 升级为 `ENROLL-V2`。服务端同时验证：

- 设备实际证书在 Release 允许集合中；
- Play Integrity 返回的证书命中允许集合；
- Credential 保存本次注册使用的实际证书摘要；
- 后续 AUTHORIZE/Grant 使用该实际证书，并再次确认仍属于 Release 集合。

## 9. RSA-OAEP 统一

协议固定：

```text
wrapAlgorithm = RSA-OAEP-SHA1
JCA transformation = RSA/ECB/OAEPPadding
OAEP digest = SHA-1
MGF1 digest = SHA-1
PSource = PSpecified.DEFAULT
label = empty byte array
```

服务端、Android Runtime、端到端测试和文档必须同时修改。不得继续使用 `RSA-OAEP-SHA256` 名称描述当前实现。

## 10. 运行时诊断

Runtime HTTP 层保留非 2xx 响应 body，并解析 `code/message/details`。服务端返回 expected 信息，Runtime 使用本地 RuntimeConfig 和实际 APK 身份补充 actual 信息。

必须覆盖：

- 本地 APK packageName/versionCode/证书不匹配；
- Release/KeyVersion/BuildHash 不匹配；
- 服务端身份拒绝；
- Grant 绑定不匹配。

Runtime 不得打印完整 Credential、Grant、wrappedPayloadKey、API Key 或 Integrity Token。当前打印完整 authorize 响应的日志必须删除。配置嵌入错误时提示重新构建并安装正确版本，不提示无效的“仅清缓存即可修复”。

## 11. 实施阶段

### 阶段一：服务端协议和数据模型

1. 重建 Schema 和 Release Store；
2. 实现统一 JSON 信封和稳定 code 枚举；
3. 实现原子 Release 状态机和版本计数；
4. 实现业务 DEX 摘要复算；
5. 将 BuildHash/CertificateSetHash 加入加密和 Grant 绑定；
6. 统一 OAEP-SHA1；
7. 更新管理页面。

### 阶段二：Gradle 插件流水线

1. 拆分 JiaguTask；
2. 实现确定性业务 DEX、资源和 Native 摘要；
3. 支持 APK、Split APK、AAB 和统一 output versionCode 校验；
4. 增加 Play App Signing 证书配置；
5. 实现结构化 HTTP 异常和冲突日志；
6. 推迟 publish 到最终产物校验之后。

### 阶段三：Runtime 协议

1. RuntimeConfig V2 和证书集合；
2. ENROLL-V2、Grant 和 AAD 新字段；
3. OAEP-SHA1 常量统一；
4. 结构化错误与本地/服务端诊断；
5. 清理敏感日志。

### 阶段四：测试和文档收尾

1. 全量自动化测试；
2. APK/Split/AAB 手工验收；
3. 清空开发数据库并重新创建公司、API Key 和 Release；
4. 核对所有文档、示例和管理页面字段。

## 12. 自动化测试矩阵

### 服务端

- 首次创建、DRAFT 相同请求复用、DRAFT 变化更新；
- DRAFT 更新 KeyVersion 只增加一次；
- PUBLISHED 相同请求复用、四类组件变化分别 409；
- REVOKED 永久禁止复用；
- 证书集合排序、去重、命中和变化；
- 并发创建只计一次、UPDATE/PUBLISH 竞争不穿透；
- JG3 解析和 businessDexSha256 复算；
- 所有 JSON 响应信封；
- OAEP-SHA1 端到端解封和 JGPD 解密。

### 插件

- ZIP/JAR 时间戳或顺序变化不改变摘要；
- DEX、Manifest、res、assets、每个 ABI `.so` 变化分别改变对应摘要；
- `liblog_ext.so` 不参与 native 摘要；
- 多 Output versionCode 不同构建失败；
- Debug 命中已发布 Release 时输出明确升级提示；
- Play App Signing 证书配置进入有序集合；
- APK、ABI Split、资源 Split 和 AAB 任务图无循环；
- package 失败不 publish。

### Runtime

- 本地包名/versionCode/证书不匹配日志；
- 统一错误信封解析；
- expected/actual KeyVersion 和 BuildHash 诊断；
- 多证书集合和证书轮换历史；
- OAEP-SHA1 空 Label 解封；
- 日志不包含 Credential、Grant、wrapped Key 和 Integrity Token。

## 13. 手工验收

1. 发布 Release 后不改内容重复构建，确认复用同一 releaseId；
2. 分别修改业务代码、Manifest、res、assets 和各 ABI `.so`，确认 409 和准确组件日志；
3. 正式版发布后使用同 package/version 构建 Debug，确认提示提升版本或修改 applicationId；
4. 修改 DRAFT 两次，确认 releaseId 不变、KeyVersion 逐次增加；
5. 构建普通 APK、ABI Split、资源 Split 和 AAB；
6. 从 Google Play 安装 AAB 产物，确认 Play App Signing 证书可注册和授权；
7. 在两台设备安装同一版本，确认得到不同 deviceId、wrapped Key 和设备 Payload 密文；
8. 检查 Logcat 和服务端日志无敏感令牌。

## 14. 完成标准

- 代码、数据库、API、插件、Runtime、管理页面和设计文档使用相同字段名称和协议版本；
- 所有自动化测试通过，包含现有 OAEP 端到端测试；
- PUBLISHED/REVOKED Release 不存在任何内容替换路径；
- 一个 Variant 的 APK/Split/AAB 使用同一 Release 构建摘要；
- BuildHash、证书集合、Payload Hash 和 KeyVersion 均进入签名或加密绑定；
- 清空数据后可以从创建公司到 Play 安装授权完成全链路验收。
