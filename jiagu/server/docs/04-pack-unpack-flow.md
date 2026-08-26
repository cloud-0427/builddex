# 04. 打包与解包流程

## 构建打包

```text
Gradle 提取核心 DEX/SO
        ↓
调用 pack/releases
        ↓
服务端检查公司状态、授权时间和 pack_limit
        ↓
生成随机 CanonicalPayloadKey
        ↓
AES-256-GCM 加密标准 Payload
        ↓
用公司 KEK 再加密 CanonicalPayloadKey
        ↓
密文、被封装 Key和版本元数据写入公司 SQLite
        ↓
pack_count + 1
        ↓
发布 release
        ↓
Gradle 将 releaseId、Payload hash 和公司服务端公钥写入壳
```

公司 KEK 不存数据库：

```text
companyKEK = HMAC-SHA256(
    serverMasterKey,
    "jiagu-company-key-v1" || companyId || "canonical-key-wrap-v1"
)
```

## Canonical Payload AAD

标准 Payload 的 AES-GCM AAD 绑定：

```text
CANONICAL-PAYLOAD-V1
companyId
releaseId
payloadId
payloadVersion
packageName
versionCode
certificateSha256
plaintextSha256
```

字段使用长度前缀序列化：

```text
{UTF-8字节长度}:{值}\n
```

示意：

```text
20:CANONICAL-PAYLOAD-V1
4:acme
...
```

客户端生成设备签名和 Play Integrity `requestHash` 时也必须使用同样的长度前缀规则，不能自行使用 JSON 序列化。

## 首次设备注册

```text
客户端申请 ENROLL challenge
        ↓
Android Keystore 生成 ECDSA P-256 签名 Key
Android Keystore 生成 RSA-3072 OAEP 解封 Key
        ↓
客户端构造 ENROLL-V1 canonical message
        ↓
requestHash = Base64URL(SHA-256(message))
        ↓
获取 Play Integrity Standard token
        ↓
ECDSA 私钥签名 SHA-256(message)
        ↓
服务端验证 Integrity、设备签名和一次性 challenge
        ↓
返回签名 DEVICE_CREDENTIAL
```

ENROLL 消息字段顺序：

```text
ENROLL-V1
companyId
challengeId
challenge
releaseId
packageName
versionCode
certificateSha256
signPublicKey
wrapPublicKey
```

`deviceId` 由服务端计算：

```text
Base64URL(SHA-256(signPublicKeyDER || wrapPublicKeyDER))
```

服务端不保存 Credential 和设备公钥。

## Payload 授权

客户端申请 AUTHORIZE challenge 后构造：

```text
AUTHORIZE-V1
companyId
challengeId
challenge
releaseId
SHA-256(deviceCredential)
deviceId
```

其中 SHA-256 输出使用 Base64URL 无填充编码。

服务端验证后动态派生：

```text
DevicePayloadKey = HMAC-SHA256(
    serverMasterKey,
    "jiagu-device-payload-v1" ||
    companyId || deviceId || releaseId || payloadId || payloadVersion ||
    packageName || versionCode || certificateSha256 || plaintextSha256 ||
    payloadKeyVersion
)
```

服务端用设备 `wrapPublicKey` 执行：

```text
RSA-OAEP-SHA256(DevicePayloadKey, label=empty)
```

响应中的 `wrappedPayloadKey` 是 Base64URL 编码。为了兼容 Android KeyStore，RSA-OAEP 必须使用空标签（Empty Label/PSource.PSpecified.DEFAULT）。响应中的 `wrapLabel` 将始终为空字符串。

## Grant

Grant 使用 Ed25519 JWS，算法标识为 `EdDSA`，服务端 Key由主密钥和 companyId 派生。

Native 必须固定构建期取得的公司服务端公钥，并校验：

- companyId；
- deviceId；
- wrap public key摘要；
- releaseId；
- payloadId 和 payloadVersion；
- packageName 和 versionCode；
- certificateSha256；
- plaintextSha256；
- payloadKeyVersion；
- wrappedPayloadKey 摘要；
- issuedAt 和 expiresAt。

## 设备专属 Payload 生成

下载接口执行：

1. 验证 Grant JWS；
2. 验证 Grant 有效期；
3. 检查 DEVICE 撤销；
4. 确认 release 仍为 PUBLISHED；
5. 检查 Grant 与数据库 release 的全部字段；
6. 派生公司 KEK；
7. 解封 CanonicalPayloadKey；
8. 解密标准 Payload并验证 plaintextSha256；
9. 重新派生 DevicePayloadKey；
10. 用 DevicePayloadKey 加密；
11. 事务增加 delivery_count；
12. 返回 JGPD 容器。

设备 Payload AAD：

```text
DEVICE-PAYLOAD-V1
companyId
deviceId
releaseId
payloadId
payloadVersion
packageName
versionCode
certificateSha256
payloadPlaintextSha256
payloadKeyVersion
```

## JGPD 二进制格式

```text
Offset  Size  内容
0       4     ASCII "JGPD"
4       4     Big Endian formatVersion，当前为 1
8       4     Big Endian encryptedLength
12      12    AES-GCM nonce
24      N     AES-GCM ciphertext + 16-byte tag
```

当前版本整个 Payload 使用一个 AES-GCM 消息。客户端必须先验证长度，再执行 GCM 解密和明文 SHA-256 校验。

## Android 解包顺序

```text
验证 Grant Ed25519 签名
        ↓
验证本机包名、versionCode 和签名证书
        ↓
验证 deviceId 和本机 wrap public key摘要
        ↓
Keystore RSA 私钥解封 DevicePayloadKey
        ↓
解析 JGPD Header
        ↓
AES-256-GCM + AAD 解密
        ↓
验证 Payload 明文 SHA-256
        ↓
通过 InMemoryDexClassLoader 加载
        ↓
立即清零 Key 和中间缓冲
```

不得将明文 DEX 写入普通文件。Grant、wrapped Key和设备密文可以缓存，因为复制到其他设备后没有 Keystore 私钥无法解封。
