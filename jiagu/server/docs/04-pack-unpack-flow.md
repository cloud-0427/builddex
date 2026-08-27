# 04. 打包与解包流程

## Variant 构建与发布

```text
Gradle 分离业务 Class
        ↓
D8 生成最终业务 DEX 和 JG3 Payload
        ↓
计算 businessDexSha256
        ├─────────────────────┐
资源收缩/确定性混淆           │
        ↓                     │
Manifest/res/assets 摘要 ─────┤
                              ├─→ create/update DRAFT Release
合并并 Strip Native 输入       │
        ↓                     │
各 ABI .so 摘要（排除          │
liblog_ext.so）───────────────┘
        ↓
服务端复算 businessDexSha256、certificateSetSha256、releaseBuildSha256
        ↓
服务端原子执行 Release 状态机和 pack_count
        ↓
Gradle 取得 release metadata 和构建期固定的公司 Ed25519 公钥
        ↓
生成 RuntimeConfig V2 和各 ABI liblog_ext.so
        ↓
完成 APK / ABI Split / 资源 Split / AAB
        ↓
校验最终产物
        ↓
publish Release
```

一个 Variant 的所有 Output 必须使用同一 versionCode。APK、Split APK 和 AAB 聚合 Variant 全部 ABI、语言和 density 输入，共用 `packageName + versionCode` 唯一 Release。

`pack_count` 只在首次创建唯一版本时增加；DRAFT 更新和重试不增加。构建失败保留 DRAFT，但不能提前 PUBLISH。

## 构建摘要

三个组件摘要使用排序后的路径、长度和内容摘要，不使用 ZIP/JAR 原始字节：

```text
businessDexSha256 = SHA-256(canonical(
    "JIAGU-BUSINESS-DEX-V1",
    dexPath, dexLength, SHA-256(dexBytes), ...
))

resourcesSha256 = SHA-256(canonical(
    "JIAGU-RESOURCES-V1",
    resourcePath, resourceLength, SHA-256(resourceBytes), ...
))

nativeLibsSha256 = SHA-256(canonical(
    "JIAGU-NATIVE-LIBS-V1",
    abiAndSoPath, soLength, SHA-256(soBytes), ...
))

releaseBuildSha256 = SHA-256(canonical(
    "JIAGU-RELEASE-BUILD-V1",
    businessDexSha256,
    resourcesSha256,
    nativeLibsSha256
))
```

资源包含最终 `AndroidManifest.xml`、`resources.arsc`、`res/**` 和 `assets/**`，排除 `META-INF/**`。Native 包含应用、依赖和 `libjiagu-core.so`，排除嵌入 Release 配置的 `liblog_ext.so`。

## 签名证书集合

Release 保存排序去重后的证书集合，并计算：

```text
certificateSetSha256 = SHA-256(canonical(
    "JIAGU-CERTIFICATE-SET-V1",
    sortedCertificateSha256Digests...
))
```

APK/Debug 默认使用 signingConfig 证书；AAB 正式发布必须显式加入 Play App Signing 证书。设备实际证书只需命中允许集合之一。Credential 和 Grant 记录当前设备实际证书，Release 加密绑定使用证书集合摘要。

## Canonical Payload 存储

公司 KEK 不存数据库：

```text
companyKEK = HMAC-SHA256(
    serverMasterKey,
    "jiagu-company-key-v1" || companyId || "canonical-key-wrap-v1"
)
```

服务端为首次创建或 DRAFT 更新生成随机 CanonicalPayloadKey，使用 AES-256-GCM 加密标准 JG3 Payload，再使用公司 KEK 封装 CanonicalPayloadKey。

Canonical Payload AAD 升级为：

```text
CANONICAL-PAYLOAD-V2
companyId
releaseId
payloadId
payloadVersion
packageName
versionCode
certificateSetSha256
releaseBuildSha256
payloadPlaintextSha256
payloadKeyVersion
```

DRAFT 更新保留 releaseId，但任何 Payload、构建组件或证书集合变化都令 `payloadKeyVersion+1`，并使用新 AAD 重新加密。PUBLISHED/REVOKED 不存在替换路径。

所有 canonical 字段使用：

```text
{UTF-8字节长度}:{值}\n
```

## RuntimeConfig V2

插件写入壳的公开配置至少包含：

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

`companyApiKey` 不得写入 RuntimeConfig、Manifest、资源、Native 字符串或 APK/AAB。

## 首次设备注册

```text
客户端申请 ENROLL challenge
        ↓
Android Keystore 生成 ECDSA P-256 签名 Key
Android Keystore 生成 RSA-3072 OAEP 解封 Key
        ↓
读取实际 packageName、versionCode 和安装证书
        ↓
确认实际证书属于 RuntimeConfig 允许集合
        ↓
构造 ENROLL-V2 canonical message
        ↓
requestHash = Base64URL(SHA-256(message))
        ↓
获取 Play Integrity Standard token
        ↓
ECDSA 私钥签名 message
        ↓
服务端验证 Release、证书集合、Integrity、设备签名和一次性 challenge
        ↓
返回签名 DEVICE_CREDENTIAL
```

ENROLL-V2 字段顺序：

```text
ENROLL-V2
companyId
challengeId
challenge
releaseId
packageName
versionCode
actualCertificateSha256
certificateSetSha256
releaseBuildSha256
signPublicKey
wrapPublicKey
```

`deviceId`：

```text
Base64URL(SHA-256(signPublicKeyDER || wrapPublicKeyDER))
```

服务端不保存正常设备 Credential 和公钥。Credential 包含当前实际证书摘要，而不是整个允许集合。

## Payload 授权

客户端申请 AUTHORIZE challenge 后构造：

```text
AUTHORIZE-V2
companyId
challengeId
challenge
releaseId
SHA-256(deviceCredential)
deviceId
releaseBuildSha256
payloadKeyVersion
```

服务端验证后动态派生：

```text
DevicePayloadKey = HMAC-SHA256(
    serverMasterKey,
    "jiagu-device-payload-v2" ||
    companyId || deviceId || releaseId || payloadId || payloadVersion ||
    packageName || versionCode || actualCertificateSha256 ||
    releaseBuildSha256 || payloadPlaintextSha256 || payloadKeyVersion
)
```

服务端使用设备 wrapPublicKey：

```text
RSA-OAEP-SHA1(DevicePayloadKey, label=empty)
```

固定参数：

```text
JCA transformation = RSA/ECB/OAEPPadding
OAEP digest = SHA-1
MGF1 digest = SHA-1
PSource = PSpecified.DEFAULT
label = empty byte array
```

响应 `wrapAlgorithm` 为 `RSA-OAEP-SHA1`，`wrapLabel` 永远为空字符串。

## Grant

Grant 使用 Ed25519 JWS，算法标识 `EdDSA`。Runtime 必须使用构建期固定的公司服务端公钥，并校验：

- companyId、deviceId 和 wrap public key 摘要；
- releaseId、payloadId、payloadVersion；
- packageName、versionCode 和实际证书摘要；
- 实际证书仍属于 RuntimeConfig 允许集合；
- certificateSetSha256 和 releaseBuildSha256；
- payloadPlaintextSha256 和 payloadKeyVersion；
- wrappedPayloadKey 摘要；
- issuedAt 和 expiresAt。

## 设备专属 Payload 生成

下载接口执行：

1. 验证 Grant JWS 和有效期；
2. 检查 DEVICE 撤销；
3. 确认 Release 仍为 PUBLISHED；
4. 检查 Grant 与当前 Release 的全部绑定；
5. 解封 CanonicalPayloadKey；
6. 使用 V2 AAD 解密标准 Payload并验证明文摘要；
7. 重新派生 DevicePayloadKey；
8. 使用 DevicePayloadKey 加密设备 Payload；
9. 事务增加 delivery_count；
10. 返回 JGPD 容器。

Device Payload AAD：

```text
DEVICE-PAYLOAD-V2
companyId
deviceId
releaseId
payloadId
payloadVersion
packageName
versionCode
actualCertificateSha256
releaseBuildSha256
payloadPlaintextSha256
payloadKeyVersion
```

## JGPD 二进制格式

```text
Offset  Size  内容
0       4     ASCII "JGPD"
4       4     Big Endian formatVersion
8       4     Big Endian encryptedLength
12      12    AES-GCM nonce
24      N     AES-GCM ciphertext + 16-byte tag
```

当前整个 Payload 使用一个 AES-GCM 消息。客户端必须先验证长度，再执行 GCM 解密和明文 SHA-256 校验。

## Android 解包顺序

```text
解析 RuntimeConfig V2
        ↓
验证本机包名、versionCode 和证书集合
        ↓
验证 Grant Ed25519 签名和全部绑定
        ↓
验证 deviceId 和本机 wrap public key 摘要
        ↓
Keystore RSA-OAEP-SHA1 私钥解封 DevicePayloadKey
        ↓
解析 JGPD Header
        ↓
AES-256-GCM + DEVICE-PAYLOAD-V2 AAD 解密
        ↓
验证 Payload 明文 SHA-256
        ↓
通过 InMemoryDexClassLoader 加载
        ↓
立即清零 Key 和中间缓冲
```

不得将明文 DEX 写入普通文件。Grant、wrapped Key 和设备密文可以缓存，因为复制到其他设备后没有原 Keystore 私钥无法解封。
