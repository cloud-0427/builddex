# 插件、Runtime 与服务端集成协议

## 1. 目标架构

业务 DEX 只以 AES-256-GCM 密文存在于 APK 的 `liblog_ext.so` 中。服务端不接收、不保存、也不下载 Payload，只保存 Release 元数据、本地密文摘要以及由主密钥封装的 32 字节 Payload Key。

运行时保留当前设备身份体系：

- Android Keystore ECDSA P-256 密钥签署 ENROLL/AUTHORIZE challenge；
- Android Keystore RSA-3072 OAEP 密钥接收服务端封装的 Payload Key；
- Device Credential 和 Payload Grant 使用公司 Ed25519 Key 签名；
- 支持公司、Release 和设备撤销；
- `integrityMode=google` 时额外验证 Play Integrity；`disabled` 时不提供安装真实性证明。

```text
Gradle Plugin
  ├─ 根据 Variant minifyEnabled 选择 D8 或目标 App 自己的 R8 配置
  ├─ 生成 JG3 业务 DEX 容器
  ├─ POST /pack/releases：只提交摘要和 Release 元数据
  ├─ 取得该 Release 的 32 字节 Payload Key
  ├─ 本地 AES-GCM 加密 JG3，生成 JGLP
  ├─ POST /pack/releases/{releaseId}/seal：只提交 JGLP 摘要和大小
  └─ 将 RuntimeConfig + JGLP 写入各 ABI 的 liblog_ext.so

Android Runtime
  ├─ 从 liblog_ext.so 只读段读取 RuntimeConfig + JGLP
  ├─ 验证实际包名、versionCode 和签名证书
  ├─ ENROLL / AUTHORIZE
  ├─ 用 Android Keystore RSA 私钥解封 Payload Key
  ├─ 校验 JGLP 摘要并执行 AES-GCM 解密
  └─ 校验 JG3 明文摘要，内存加载业务 DEX

Server
  ├─ 保存 Release 构建摘要和 JGLP 摘要，不保存 JG3/JGLP
  ├─ 使用公司 KEK 封装 Payload Key
  ├─ 验证 challenge、设备签名、Credential、撤销和可选 Integrity
  └─ 将同一 Release Key 用设备 RSA 公钥封装后返回
```

## 2. R8 行为

R8 是 Variant 自身的可选能力，不由加固插件强制开启：

- `minifyEnabled=false`：业务 JAR 走 D8；
- `minifyEnabled=true`：使用目标 Variant 的 ProGuard/R8 文件、依赖 consumer rules、minSdk、debuggable 和 boot classpath；
- 插件只追加壳运行所需的最小安全保留规则，不替换 App 配置；
- 业务 R8 的重命名结果隔离到 `io.github.xjc.jiagu.payload.r8`，避免它与壳 APK 的独立 R8 输出产生同名类描述符；目标 App 的 keep、裁剪和优化规则仍然生效；
- R8 输出业务 mapping 到 `build/intermediates/jiagu/<variant>/business-mapping.txt`。

## 3. 构建协议

### 3.1 准备 Release

`POST /api/v1/companies/{companyId}/pack/releases` 使用 Company Key 和 JSON 请求，只包含 Payload/应用标识、允许证书集合、三个构建摘要、`payloadPlaintextSha256` 和 packer。请求不包含 JG3、DEX 或任何 Payload 字节。

服务端创建、更新或复用 Release，并在认证响应中返回 `payloadKey`。该 Key 只能在可信构建环境通过 HTTPS 获取，禁止写入日志和持久化构建缓存。

相同 `packageName + versionCode` 的行为：

- 首次创建：生成 ReleaseID、Payload Key，KeyVersion=1；
- DRAFT 内容变化：保留 ReleaseID，轮换 Key，KeyVersion+1；
- DRAFT 内容不变：复用 Release 和 Key；
- PUBLISHED 内容不变：复用 Release 和 Key，保证重复构建可重现；
- PUBLISHED 内容变化或 REVOKED 版本复用：拒绝，必须增加 versionCode。

### 3.2 本地加密

本地 Payload 使用 JGLP：

```text
offset  size  value
0       4     ASCII "JGLP"
4       4     version=1，大端
8       4     encryptedLength，大端
12      12    AES-GCM nonce
24      N     ciphertext + 16-byte GCM tag
```

AAD：

```text
canonical(
  "LOCAL-PAYLOAD-V3", companyId, releaseId, payloadId, payloadVersion,
  packageName, versionCode, certificateSetSha256, releaseBuildSha256,
  payloadPlaintextSha256, payloadKeyVersion
)
```

nonce 使用 Payload Key 对同一 AAD 做 HMAC-SHA256 后取前 12 字节。只要任一受保护内容变化，服务端就轮换 Payload Key；因此不会在同一 Key 下对不同明文复用 nonce，同时相同已发布版本可生成完全一致的 JGLP。

### 3.3 封存本地摘要

`POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/seal` 只提交 `localCiphertextSha256` 和 `localPayloadSize`。未 seal 的 Release 不允许发布、注册或授权；已经 seal 的摘要不可修改。

## 4. APK 内嵌格式

`liblog_ext.so` 的 `.jg_payload` 只读段保存 JGRC：

```text
offset  size  value
0       4     ASCII "JGRC"
4       4     version=1
8       4     RuntimeConfig JSON 长度
12      4     JGLP 长度
16      C     RuntimeConfig JSON（configVersion=3）
16+C    P     JGLP
```

RuntimeConfig v3 新增 `localCiphertextSha256` 和 `localPayloadSize`，并固定服务端 Ed25519 公钥、ReleaseID、构建摘要、证书集合、Payload 明文摘要、KeyVersion 和 Integrity 配置。

## 5. 设备授权

ENROLL 保留一次性 challenge、ECDSA 设备签名、实际应用身份绑定和可选 Play Integrity，成功后返回签名 Device Credential。

Device Credential 按 `companyId + packageName + 实际签名证书` 缓存，不绑定 `releaseId`。同一公司、包名和证书升级到新 Release 时复用有效 Credential，只重新执行 AUTHORIZE；证书变化、Credential 过期或校验失败时才重新 ENROLL。

AUTHORIZE 验证 Credential、设备签名、Release、撤销和可选 Integrity；服务端解封 Release Payload Key，再用设备 RSA 公钥做 OAEP-SHA1 封装。Runtime 验证签名 Grant，用 Android Keystore RSA 私钥解封，然后校验本地 JGLP 摘要、AES-GCM tag 和 JG3 明文摘要。

Runtime 将 ELF 只读映射中的 JGLP 直接暴露为 `DirectByteBuffer`，AES-GCM 明文也写入直接内存，再由 Native 原位解析 JG3 并解压到最终 DEX Buffer；不再为整个 Payload 创建 native vector、JNI `byte[]`、Java ciphertext 数组和回传副本。

不存在 `/unpack/download`。AUTHORIZE 成功即视为一次 Key 下发并执行 delivery 计数；DRAFT 同一 Release 只首次消耗公司额度，PUBLISHED 每次新授权消耗额度。有效 Grant 与 wrapped Key 可以缓存，默认 7 天内普通启动无需访问网络。

## 6. 数据库存储边界

`payload_releases` 只保存 Release/构建身份字段、明文与本地密文摘要、JGLP 大小、受主密钥保护的 Payload Key、状态和计数。不保存 JG3、JGLP、DEX BLOB 或每设备 Payload 文件。

服务端只接受已经升级完成的 Schema v6，不再包含运行时自动迁移代码。数据库中不得存在 `canonical_payload` 与 `canonical_ciphertext_sha256` 列。旧协议 APK 因下载接口被移除而不再兼容，需要用新插件重新构建。

## 7. 安全边界

- 构建 API 必须使用 HTTPS；它会返回 Release Payload Key。
- Company API Key、Payload Key、Credential、Grant、wrappedPayloadKey 和 Integrity token 不得写日志。
- APK 本地密文不能阻止已获得有效 Payload Key 的攻击者保存明文；撤销也无法让攻击者忘记历史 Key。
- `integrityMode=disabled` 时，包签名摘要属于客户端声明，不能证明正版 Play 安装。
- 主密钥和数据库必须分别备份；主密钥丢失后无法解封已有 Release Key。

## 8. 验证要求

- Go 全量测试覆盖 prepare、seal、enroll、authorize、Key RSA 解封、下载接口 404、配额和 schema 版本拒绝；
- 插件单测和 Runtime 四 ABI 编译通过；
- Debug/Release 实际构建应在 `liblog_ext.so` 中发现 JGRC 和 JGLP；
- 服务端 Release 列表的 `localPayloadSize` 应与 JGLP 长度一致；
- SQLite 中不得存在 `canonical_payload` 或任何 Payload BLOB 列。
