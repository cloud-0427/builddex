# 打包插件、壳工程与密钥服务整体改造方案

## 1. 目标与范围

服务端已经提供公司授权、打包次数限制、设备授权、下发次数限制和设备专属密钥能力。下一阶段需要改造：

- `dex-report-plugin`：在构建期间认证公司、上传 Payload、创建并发布 release；
- `jiagu-runtime`：在设备上完成注册、授权、下载、验签和解密；
- 应用打包配置：增加公司和服务端配置，且确保公司 API Key 不进入 APK；
- 三方协议：插件、壳和端口 `8761` 的服务端使用同一套字段、摘要和二进制格式。

本方案不增加微服务，不要求服务端持久化普通设备记录，沿用“一公司一个 SQLite 文件”的服务端设计。

## 2. 必须解决的现有问题

### 2.1 旧密钥链必须删除

当前插件和壳仍使用以下旧方案：

- 插件本地生成 Session Key；
- 使用 `SHA-256(packageName:versionName:固定常量)` 派生 Master Key；
- 将可被客户端自行解封的 `salt/nonce/bksBlob` 发布到普通 JSON；
- 壳从 `KEY_URL` 下载通用密钥块并在本地解封；
- Manifest 保存密钥地址、JSON 节点和安全开关。

这些逻辑允许攻击者通过静态分析还原派生算法，或在动态运行时拦截通用 Session Key，不能实现真正的一机一码。改造完成后应删除：

- `publicKeyPath`、`publicKeyJsonKey`、`enableMultiVersion`、`keyExpiryDays`；
- `jiagu_keys.json`；
- Manifest 中的 `KEY_URL`、`JSON_KEY`、`KEY_EXPIRY`；
- `handleKeyManagement()` 及固定常量派生逻辑；
- `NetworkHelper` 中旧 JSON Key 拉取和旧 Key 缓存逻辑；
- Native 中 `decrypt_kms_key()` 及 `salt|nonce|bksBlob` 解析逻辑。

### 2.2 公司身份必须进入构建流程

公司级打包限额只有在插件调用以下接口时才能生效：

```text
POST /api/v1/companies/{companyId}/pack/releases
X-Company-Key: {companyApiKey}
```

`companyId` 决定打开哪个公司数据库，`companyApiKey` 摘要必须存在于该数据库的 `company_api_keys` 表。创建 release 成功后，服务端事务增加该公司的 `pack_count`；超过 `pack_limit` 返回 HTTP 429。

### 2.3 构建密钥和运行时身份必须隔离

`companyApiKey` 是构建系统凭据。当前简化方案允许直接配置在 `build.gradle` 中；如果项目会提交到公共仓库或多人共享，后续再改用环境变量或 CI Secret。无论从哪里读取，它都不得被插件写入：

- Manifest、resources、assets 或 BuildConfig；
- Java/Kotlin 常量或 Native 字符串；
- APK/AAB、中间产物、构建扫描和普通日志；
- Gradle Task 输出、缓存文件和异常信息。

设备运行时不需要、也不应该知道 `companyApiKey`。

## 3. 总体架构

```text
可信构建环境
  Gradle 插件
    ├─ 读取 companyId、serverUrl
    ├─ 读取构建配置中的 companyApiKey
    ├─ 提取并生成明文 Payload 容器
    ├─ POST pack/releases                 ──────┐
    ├─ 获取 releaseId、Payload hash             │
    ├─ 固定服务端 Ed25519 公钥                   │  :8761 单服务
    ├─ 生成壳的公开 RuntimeConfig               │  公司 SQLite
    └─ 构建成功后 publish release         ──────┘

Android 设备
  壳 Runtime
    ├─ Android Keystore 生成签名/解封密钥对
    ├─ ENROLL：Integrity + 设备签名
    ├─ AUTHORIZE：Integrity + Credential + 设备签名
    ├─ 验证服务端 Grant 签名及全部绑定字段
    ├─ Keystore 私钥解封设备专属 Payload Key
    ├─ 下载设备专属 JGPD 密文
    └─ Native 内存解密、校验并加载 DEX
```

关键边界：

- 插件使用公司 Key，负责公司级打包授权；
- 壳使用设备 Keystore 私钥，负责设备级运行授权；
- 服务端保存标准加密 Payload，不保存普通设备记录；
- APK 中只有公开配置，不存在能解开其他设备 Payload 的通用秘密。

## 4. Gradle 插件配置设计

### 4.1 建议 DSL

```groovy
dexReport {
    serverUrl = "https://jiagu.example.com:8761"
    companyId = "acme"
    companyApiKey = "公司创建时返回的 Key"
    // AAB 正式发布时配置 Play App Signing 证书；可同时配置轮换历史和侧载证书。
    certificateSha256Digests = ["BASE64URL_SHA256"]
}
```

前三项负责公司鉴权和服务端连接。证书列表为公开配置：APK/Debug 可以仅使用插件自动读取的 signingConfig 证书，AAB 正式发布必须显式加入 Play App Signing 证书。

### 4.2 必需配置

| 配置 | 是否必需 | 是否秘密 | 用途 |
|---|---:|---:|---|
| `serverUrl` | 是 | 否 | 8761 服务地址，正式环境必须使用 HTTPS |
| `companyId` | 是 | 否 | 选择公司数据库并写入 RuntimeConfig |
| `companyApiKey` | 是 | 是 | 构建期调用公司打包接口，绝不写入 APK |
| `certificateSha256Digests` | AAB 正式发布必需 | 否 | Play App Signing、受控侧载和证书轮换允许集合；插件还会加入当前 signingConfig 证书 |

`companyId` 与 `companyApiKey` 必须同时验证，不能只依赖 Key。服务端现有逻辑为：先用 URL 中的 `companyId` 打开公司 SQLite，再在该库验证 Key 摘要。

### 4.3 插件内部默认值

以下内容不开放配置，由插件自动处理：

| 项目 | 默认行为 |
|---|---|
| `payloadId` | 固定为 `app-main` |
| `payloadVersion` | 使用当前 variant 的 Android `versionCode` |
| `packageName` | 使用 AGP variant 的 `applicationId` |
| `certificateSha256Digests` | signingConfig 证书与显式允许证书合并、排序、去重 |
| `serverKeyId` | 固定为协议当前值 `company-sign-v1` |
| `serverPublicKey` | 构建时调用 `public-config` 获取，并固定写入当前 APK |
| 自动发布 | 默认开启，最终产物构建成功后自动发布 release |
| 连接和读取超时 | 使用插件内置合理默认值 |

`public-config` 获取的公钥只在可信构建阶段使用；运行时不会再次动态替换该公钥。正式服务地址必须使用 HTTPS。

### 4.4 公司 Key 使用约束

公司 Key 可以直接写入 `build.gradle`，因为 Gradle DSL 配置不会自动进入 APK。插件必须保证不将其传递给 Manifest、资源生成或 Native 编译任务。

如果以后需要避免 Key 被提交到代码仓库，可以保持 DSL 不变，仅把值改为环境变量读取：

```groovy
dexReport {
    serverUrl = "https://jiagu.example.com:8761"
    companyId = "acme"
    companyApiKey = providers.environmentVariable("JIAGU_COMPANY_KEY").get()
}
```

插件日志不得打印 Key 明文，只允许显示：

```text
companyId=acme
companyKeyFingerprint=SHA-256 前 8~12 字符
```

Task/Flow 中公司 Key 是内部敏感配置，不得进入产物或日志；所有网络打包阶段默认禁用 Gradle Build Cache。

## 5. 插件任务流水线

当前实现由 `jiagu<Variant>`、`obfuscateRes<Variant>`、`createJiaguRelease<Variant>` 和构建结束发布 Flow 组成。`jiagu<Variant>` 只负责 Scoped Classes 变换以及业务 DEX/JG3 Payload；`createJiaguRelease<Variant>` 等待该 Variant 的最终资源包（开启资源收缩时位于 R8/资源收缩之后），再计算资源和 Native 摘要、创建 DRAFT 并生成 RuntimeConfig ELF。这样 R8 开启与关闭时都使用实际存在的资源生产任务，不会让 R8 反向依赖其下游资源任务。Flow 仅在本次 Gradle invocation 无任何失败、且 metadata 中的 invocation ID 匹配时发布。因此同时请求 APK 与 AAB 时，必须全部成功才会发布。

| 逻辑阶段 | 输入 | 输出 | 网络 |
|---|---|---|---:|
| `prepareJiaguBusinessDex<Variant>` | 业务 class/jar | JG3 Payload、business DEX 摘要 | 否 |
| `obfuscateJiaguResources<Variant>` | 链接/收缩后的资源 | 确定性资源包 | 否 |
| `hashJiaguResources<Variant>` | 最终 Manifest、资源、assets | resources 摘要 | 否 |
| `hashJiaguNativeInputs<Variant>` | 合并并 Strip 后的 `.so` | native 摘要；排除 `liblog_ext.so` | 否 |
| `createJiaguRelease<Variant>` | Payload、三类摘要、证书集合、公司配置 | `release-metadata.json` | 是 |
| `generateJiaguRuntimeConfig<Variant>` | release metadata、固定公钥 | RuntimeConfig V2 | 否 |
| `generateJiaguPayloadLibrary<Variant>` | RuntimeConfig | 各 ABI `liblog_ext.so` | 否 |
| `verifyJiaguArtifact<Variant>` | APK/Split APK/AAB | 最终校验标记 | 否 |
| 构建结束发布 Flow | releaseId、invocation ID、整体 BuildWorkResult | PUBLISHED 状态 | 是 |

### 5.1 创建 release

插件先执行轻量鉴权预检：

```text
GET {serverUrl}/api/v1/companies/{companyId}/pack/auth-check
X-Company-Key: {companyApiKey}
```

预检成功后再上传：

```text
POST {serverUrl}/api/v1/companies/{companyId}/pack/releases
X-Company-Key: {companyApiKey}
Content-Type: multipart/form-data
```

字段映射：

| 服务端字段 | 插件来源 |
|---|---|
| `payloadId` | 插件固定值 `app-main` |
| `payloadVersion` | AGP variant `versionCode` |
| `packageName` | AGP variant `applicationId` |
| `versionCode` | AGP variant output |
| `certificateSha256Digest` | 可重复字段；有序允许证书集合 |
| `businessDexSha256` | D8 最终业务 DEX canonical 摘要 |
| `resourcesSha256` | 最终 Manifest/resources/res/assets canonical 摘要 |
| `nativeLibsSha256` | 各 ABI 最终 `.so` canonical 摘要，不含 `liblog_ext.so` |
| `payload` | 插件生成的标准 Payload 原文 |

创建成功后将响应保存为敏感度为“公开元数据”的本地中间文件，其中至少包含：

```json
{
  "formatVersion": 2,
  "companyId": "acme",
  "releaseId": "...",
  "payloadId": "app-main",
  "payloadVersion": 5,
  "packageName": "com.example.app",
  "versionCode": 5,
  "certificateSha256Digests": ["..."],
  "certificateSetSha256": "...",
  "businessDexSha256": "...",
  "resourcesSha256": "...",
  "nativeLibsSha256": "...",
  "releaseBuildSha256": "...",
  "payloadPlaintextSha256": "...",
  "payloadKeyVersion": 1
}
```

插件必须核对全部响应字段。服务端解析 JG3 复算 business DEX 摘要，并根据三类组件摘要复算 releaseBuildSha256；任何字段不一致都终止构建。

### 5.2 发布时机

release 创建后状态为 `DRAFT`。只有最终 APK/AAB 已成功产生并完成基本校验后，才能调用：

```text
POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish
```

规则：

- 上传成功但后续构建失败：保留 DRAFT，记录 releaseId，不能自动发布；
- APK/AAB 成功且 RuntimeConfig 校验通过：发布为 PUBLISHED；
- 服务端确认发布成功后，构建结束 Flow 必须在默认 Gradle 日志中输出公司、包名、versionCode、releaseId 和 `PUBLISHED` 状态；
- 发布失败：整个构建失败，不能交付一个运行时永远无法授权的 APK；
- 唯一身份为 `packageName + versionCode`；DRAFT 相同内容复用、变化内容原地更新并轮换 Key；
- PUBLISHED 相同内容复用，任一绑定变化返回 `PUBLISHED_VERSION_MODIFIED`；
- REVOKED 永久禁止复用相同 package/version；
- APK、ABI Split、资源 Split 和 AAB 聚合同一 Variant 输入，所有 Output 必须使用相同 versionCode；
- 同一次构建同时请求 APK 和 AAB 时，全部目标成功后才能发布。

`pack_count` 只在首次创建新的 `packageName + versionCode` 时增加。DRAFT 更新、重试、PUBLISHED 复用和失败请求不增加。

## 6. RuntimeConfig 设计

壳需要的配置均为公开绑定信息，建议生成版本化二进制或 JSON，再由 Java 和 Native 双方读取并交叉校验：

```json
{
  "configVersion": 2,
  "serverUrl": "https://jiagu.example.com:8761",
  "companyId": "acme",
  "releaseId": "...",
  "payloadId": "app-main",
  "payloadVersion": 5,
  "packageName": "com.example.app",
  "versionCode": 5,
  "certificateSha256Digests": ["..."],
  "certificateSetSha256": "...",
  "payloadPlaintextSha256": "...",
  "releaseBuildSha256": "...",
  "payloadKeyVersion": 1,
  "serverKeyId": "company-sign-v1",
  "serverPublicKey": "Base64URL Ed25519 public key",
  "wrapAlgorithm": "RSA-OAEP-SHA1",
  "protocolVersion": 2
}
```

安全要求：

- `companyApiKey` 绝不能出现在 RuntimeConfig；
- Native 内编译一份关键绑定摘要，RuntimeConfig 再保存一份；两者不一致直接拒绝；
- 不信任运行时调用 `public-config` 返回的新公钥；公钥轮换必须通过新版本插件和新 APK 发布；
- Manifest 仅保留启动壳需要的最少信息，例如 `REAL_APPLICATION`；安全绑定不能只依赖 Manifest；
- `serverUrl` 可被修改，但攻击者没有固定私钥，无法伪造有效 Credential/Grant；正式环境仍应使用 HTTPS并禁止明文流量。

## 7. 壳工程改造

### 7.1 Keystore 身份

首次运行生成两个不可导出密钥对：

- ECDSA P-256：签名 ENROLL/AUTHORIZE canonical message；
- RSA-3072 OAEP SHA-1：解封服务端下发的设备专属 Payload Key；MGF1 同样使用 SHA-1，Label 为空。

Alias 必须至少绑定 `companyId + packageName`，避免同一设备上的不同壳应用误用：

```text
jiagu.sign.v1.{SHA-256(companyId|packageName)}
jiagu.wrap.v1.{SHA-256(companyId|packageName)}
```

私钥不得导出。公钥使用 X.509 SubjectPublicKeyInfo DER 后 Base64URL 编码。

### 7.2 首次注册 ENROLL

1. 请求 `purpose=ENROLL` 的 challenge；
2. 按服务端文档构造包含实际证书、certificateSetSha256 和 releaseBuildSha256 的 `ENROLL-V2` canonical message；
3. `requestHash = Base64URL(SHA-256(message))`；
4. 获取 Play Integrity Standard token；
5. 使用 ECDSA Keystore 私钥签名 message；
6. 调用 `/unpack/enroll`；
7. 使用 APK 中固定的 Ed25519 公钥验证 `deviceCredential`；
8. 验证 companyId、packageName、证书摘要、公钥和有效期；
9. 缓存 Credential。Credential 是签名令牌，不是秘密。

Credential 过期或 Key 被系统清除时重新 ENROLL。不能在 Integrity 失败时降级到旧通用 Key。

### 7.3 每次授权 AUTHORIZE

1. 请求 `purpose=AUTHORIZE` 的 challenge；
2. 构造绑定 releaseBuildSha256 和 payloadKeyVersion 的 `AUTHORIZE-V2` canonical message；
3. 获取绑定 requestHash 的 Integrity token；
4. 使用同一 ECDSA 私钥签名；
5. 调用 `/unpack/authorize`；
6. 验证 Grant 的 Ed25519 签名和所有绑定字段；
7. 验证 `wrappedPayloadKey` 摘要等于 Grant 中的摘要；
8. 使用 RSA-OAEP-SHA1、MGF1-SHA1、`PSource.PSpecified.DEFAULT` 和空 Label 解封设备 Payload Key；
9. 调用 `/unpack/download` 获取 JGPD。

### 7.4 下载、解密和加载

Native 必须执行：

1. 再次验证 Grant 签名、有效期和 RuntimeConfig 绑定；
2. 严格解析 JGPD Header、版本和长度；
3. 构造包含实际证书、releaseBuildSha256 和 payloadKeyVersion 的 `DEVICE-PAYLOAD-V2` AAD；
4. AES-256-GCM 解密；
5. 验证明文 SHA-256；
6. 使用 `InMemoryDexClassLoader` 加载；
7. 立即清零 Payload Key、明文缓冲和临时签名材料。

禁止将明文 DEX 写入普通文件。可以缓存以下内容：

- `deviceCredential`；
- Grant 和 wrapped Payload Key（不超过有效期）；
- JGPD 设备密文。

缓存文件必须绑定 releaseId、deviceId 和摘要。缓存复制到另一台设备后，因为没有原 Keystore RSA 私钥，无法解封 Key。

`/unpack/download` 不应在每次 App 启动时调用：

- 首次安装、releaseId 变化、缓存丢失或 JGPD 校验失败时才下载；
- 普通启动可以刷新短期 AUTHORIZE，以执行撤销检查并取得同一设备 Payload Key，然后直接解密本地 JGPD；
- Grant 尚未过期时，可以直接复用 Grant、wrapped Key和 JGPD；
- 只有真正重新下载设备 Payload 时才增加 `delivery_count`。

### 7.5 Java 与 Native 职责

| Java/Kotlin 层 | Native 层 |
|---|---|
| HTTP、JSON、超时、错误映射 | 固定信任根和关键配置摘要复核 |
| Android Keystore 调用 | Grant 关键字段二次校验 |
| Play Integrity token 获取 | JGPD 严格解析 |
| Credential/Grant 缓存 | AES-GCM 解密和 hash 校验 |
| 生命周期和有限重试 | 内存加载及敏感缓冲清零 |

Java 层必须先验签，但不能把 Java 验证当作唯一安全边界。Native 和服务端绑定校验同时存在。

Native anti-debug 只能使用不改变当前进程跟踪状态的检测。禁止在同一进程中先执行 `PTRACE_TRACEME` 再读取 `TracerPid`：被跟踪进程无法自行执行有效的 `PTRACE_DETACH`，否则会把自身刚建立的跟踪关系误判为调试器。防护主动终止进程前必须输出不包含密钥、凭据或完整安全响应的原因日志。

## 8. 错误处理与配额行为

插件应将服务端错误翻译为明确的 Gradle 构建错误：

| HTTP/错误 | 插件行为 |
|---|---|
| 401 `COMPANY_UNAUTHORIZED` | 停止；提示公司 Key 无效，不打印 Key |
| 403 `COMPANY_NOT_AUTHORIZED` | 停止；提示公司状态或授权时间无效 |
| 409 `RELEASE_NOT_PUBLISHED` | 停止；提示当前 APK 对应 Release 仍为 DRAFT，需要启用发布或手动发布 |
| 409 `PUBLISHED_VERSION_MODIFIED` | 停止；列出变化组件，提示提升 versionCode 或为 Debug 修改 applicationId |
| 409 `REVOKED_VERSION_REUSE_FORBIDDEN` | 停止；提示被撤销版本永久不能复用，必须提升 versionCode |
| 413 | 停止；提示 Payload 超过服务端限制 |
| 429 | 停止；提示公司 `pack_limit` 已用尽 |
| 5xx/网络超时 | 停止；创建接口不自动重试，查询接口可有限重试 |

壳运行时策略：

- 401/403：清除过期 Credential/Grant 后最多重新走一次完整流程；
- 429：按服务端策略退避，不进行高频重试；
- 网络不可用：只允许使用仍在有效期内且校验通过的设备缓存；
- Integrity 拒绝、签名不一致、Grant 验签失败、GCM/Hash 失败：立即停止加载，不降级；
- 公司被暂停、过期或撤销：新 challenge/授权失败，从而停止新设备或新授权使用；
- `delivery_limit` 在服务端成功生成设备 Payload 时计数。

服务端所有 JSON 成功和失败响应统一为：

```json
{"code":"STABLE_CODE","message":"Human readable message.","details":{}}
```

Runtime HTTP 层必须保留非 2xx body 并解析该信封。服务端返回 expected 绑定，Runtime 使用本地 RuntimeConfig 和实际 APK 身份补充 actual 日志。不得打印完整 Credential、Grant、wrappedPayloadKey、Integrity Token 或 authorize 响应。

Runtime 的 `minSdk` 为 29，而 Android 平台只从 API 33 起保证提供 Ed25519 `Signature`。因此 Credential/Grant 验签必须使用 Runtime 随包提供的兼容实现，不能直接依赖 `KeyFactory/Signature.getInstance("Ed25519")`。该兼容实现属于启动壳代码，必须在业务 Payload 解密前可用。

## 9. 服务端接口对应关系

### 构建期接口

| 分类 | API | 调用者 |
|---|---|---|
| 健康检查 | `GET /healthz` | 插件，可选预检 |
| 公钥配置 | `GET /api/v1/companies/{companyId}/public-config` | 插件，仅用于核对固定公钥 |
| 公司鉴权预检 | `GET /api/v1/companies/{companyId}/pack/auth-check` | 插件，公司 Key；必须先于 Payload 上传 |
| 创建版本 | `POST /api/v1/companies/{companyId}/pack/releases` | 插件，公司 Key |
| 查询版本 | `GET /api/v1/companies/{companyId}/pack/releases` | 插件，公司 Key |
| 发布版本 | `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish` | 插件，公司 Key |
| 撤销版本 | `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke` | 运维/插件，公司 Key |

### 运行期接口

| 分类 | API | 调用者 |
|---|---|---|
| 一次性随机数 | `POST /api/v1/companies/{companyId}/unpack/challenges` | 壳 |
| 设备注册 | `POST /api/v1/companies/{companyId}/unpack/enroll` | 壳 |
| Payload 授权 | `POST /api/v1/companies/{companyId}/unpack/authorize` | 壳 |
| 设备密文下载 | `POST /api/v1/companies/{companyId}/unpack/download` | 壳 |

运行时接口绝不携带公司 API Key。

## 10. 服务端建议补强项

插件和壳正式接入前，建议在现有单服务中补充以下小改动：

1. 给 `company_api_keys` 增加 `company_id` 并在鉴权时同时匹配，保留文件级隔离的同时增加显式关联；
2. 打开公司数据库时校验 `company_info.company_id` 与文件名/URL 参数一致；
3. 增加 API Key 创建、轮换、撤销接口，明文只在创建时返回一次；
4. `pack/releases` 通过 `packageName + versionCode` 唯一键和绑定摘要实现天然幂等，网络结果不明确时相同请求复用原 Release；
5. 所有 JSON 响应统一 `code/message/details`，Release 返回稳定 protocolVersion 和全部绑定字段；
6. 视部署需要增加 `Retry-After`、请求速率限制和最大并发限制；
7. 为 public-config 的公钥轮换增加 `keyId` 列表和明确的过渡期策略。

这些补强不改变单服务和每公司 SQLite 的总体设计。

## 11. 实施顺序

### P0：打通安全主链

1. 定义共享 canonical、JWS claims、JGPD 和 RuntimeConfig 测试向量；
2. 插件增加 serverUrl、companyId、companyApiKey 三项配置；
3. 插件改为上传 Payload、创建/发布 release；
4. 删除旧 Master Key 派生和 `bksBlob` 生成逻辑；
5. 壳实现 Keystore 双密钥对、ENROLL、AUTHORIZE 和下载；
6. Native 固定 Ed25519 公钥并实现 Grant/JGPD 双重验证；
7. 删除旧 JSON Key 拉取和降级通道。

### P1：可靠性和运维

1. 服务端增加幂等创建和 API Key 轮换；
2. 插件增加 DRAFT 恢复、明确错误映射和脱敏日志；
3. 壳增加有效期缓存、网络退避和 Key 失效恢复；
4. 管理页展示 pack/delivery 使用量、DRAFT、失败原因和撤销状态。

### P2：增强防护

1. 正式构建强制 HTTPS和公钥固定；
2. 支持服务端签名 Key 轮换和双公钥过渡；
3. 按风险等级缩短 Grant TTL、拒绝设备或限制下发；
4. 在支持设备上叠加 Key Attestation；
5. 增加异常授权频率、Integrity 失败和重复下载审计。

## 12. 验收标准

### 插件

- 未提供公司 Key 时构建立即失败；
- 错误公司 Key返回 401，正确 Key 才能创建 release；
- `pack_limit` 用尽返回 429，插件停止构建；
- APK、AAB、Manifest、assets、strings、Native strings 和日志中搜索不到公司 Key；
- 业务 DEX、Manifest/resources/assets、所有 ABI Native、包名、versionCode 和允许签名证书集合与服务端 Release 完全一致；
- AAB 使用 Play App Signing 证书完成真实 Play 安装授权；
- 正式版发布后同 package/version 的 Debug 构建收到明确 409 和升级提示；
- ZIP/JAR 时间戳、压缩级别和 Entry 顺序变化不会导致摘要变化；
- 构建失败不会发布 DRAFT，发布失败不会产出可交付成功状态。

### 壳

- 两台设备得到不同的 deviceId、wrappedPayloadKey 和 JGPD 密文；
- 把 A 设备缓存复制到 B 设备无法解封；
- challenge 重放、过期 Grant、错误包名/versionCode/证书、篡改 Payload 均加载失败；
- 修改 Manifest 中配置不能绕过 Native 固定公钥和绑定校验；
- 不产生落盘明文 DEX；
- Credential 过期、Keystore Key 丢失和网络临时失败具有明确恢复路径。

### 服务端

- `pack_count` 只在首次创建唯一 package/version 时增加，DRAFT 更新和复用不增加；
- `delivery_count` 只在设备 Payload 成功生成时增加；
- 公司暂停、过期、撤销和额度用尽能立即影响后续请求；
- 操作日志可通过 requestId 关联构建、授权和下载失败；
- 服务端、插件和壳共享的协议测试向量全部通过。

## 13. Release 构建一致性锁最终方案

本设计的构建锁、证书集合、统一响应、OAEP-SHA1 和 Split/AAB 细节，以 [Release 构建一致性锁实施计划](02-release-build-lock-implementation-plan.md) 为准。关键结论：

- 一个 `packageName + versionCode` 只有一个 Release 和一个 Payload；
- DRAFT 原地修订并轮换 Key，PUBLISHED/REVOKED 不可替换；
- 锁定最终业务 DEX、Manifest/resources/assets 和所有 ABI Native，排除 `liblog_ext.so`；
- 构建锁不等于运行时扫描 Split APK；
- AAB 必须配置 Play App Signing 证书，Release 支持有序允许证书集合；
- 所有 Output versionCode 必须一致，APK/Split/AAB 使用 Variant 级聚合摘要；
- RSA-OAEP 固定 SHA-1、MGF1-SHA1 和空 Label；
- 所有 JSON 响应固定为 `code/message/details`，下载成功的二进制响应除外。

## 14. 明确不提供的能力

一机一码可以显著提高通用静态提取、密钥复制和批量破解成本，但无法保证在攻击者完全控制的设备上永远不出现明文。业务 DEX 在执行前必然需要在目标进程内成为明文。

本方案的实际安全收益是：

- APK 静态分析得不到通用解密 Key；
- 单设备动态提取的 Key不能用于其他设备；
- 服务端可以按公司、版本和设备风险实时停止新授权；
- 攻击者必须针对具体设备和具体运行时持续绕过，而不能一次破解后批量复用。
