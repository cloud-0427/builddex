# 03. API

所有 JSON 响应使用：

```json
{"code":"STABLE_CODE","message":"human readable","details":{}}
```

## 构建接口

构建接口使用 `X-Company-Key`。

### `GET /api/v1/companies/{companyId}/pack/auth-check`

在构建前验证公司授权和 Company Key。

### `POST /api/v1/companies/{companyId}/pack/releases`

准备、更新或复用 Release。Content-Type 为 `application/json`，不允许上传 Payload 文件。

```json
{
  "payloadId": "app-main",
  "payloadVersion": 10,
  "packageName": "com.example.app",
  "versionCode": 10,
  "packer": "build-host",
  "certificateSha256Digests": ["..."],
  "businessDexSha256": "...",
  "resourcesSha256": "...",
  "nativeLibsSha256": "...",
  "payloadPlaintextSha256": "..."
}
```

认证响应除 Release 元数据外包含 Base64 `payloadKey`。它是敏感构建凭据，只能经 HTTPS 返回，不得记录。

### `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/seal`

在插件完成本地 AES-GCM 加密后封存 APK 内 JGLP 摘要：

```json
{"localCiphertextSha256":"...","localPayloadSize":4286851}
```

已 seal 的摘要只能幂等重试，不能替换。未 seal 的 Release 不能发布或进行设备授权。

### 生命周期接口

- `GET /api/v1/companies/{companyId}/pack/releases`
- `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish`
- `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke`

## Runtime 接口

### `GET /api/v1/companies/{companyId}/public-config`

返回公司 Ed25519 公钥、Integrity 模式和 Cloud Project Number，供构建期固定进 RuntimeConfig。

### `POST /api/v1/companies/{companyId}/unpack/challenges`

创建 `ENROLL` 或 `AUTHORIZE` 一次性 challenge。

### `POST /api/v1/companies/{companyId}/unpack/enroll`

验证实际应用身份、设备 ECDSA 签名、challenge 和可选 Play Integrity，返回签名 Device Credential。

### `POST /api/v1/companies/{companyId}/unpack/authorize`

验证 Credential、设备签名、Release、撤销和可选 Play Integrity。服务端解封 Release Payload Key，使用设备 RSA 公钥做 OAEP-SHA1 封装并返回：

```json
{
  "grant": "eyJ...",
  "wrappedPayloadKey": "...",
  "wrapAlgorithm": "RSA-OAEP-SHA1",
  "wrapLabel": "",
  "expiresAt": 1788490000
}
```

Grant 绑定 deviceId、Release、证书集合、构建摘要、JG3 明文摘要、JGLP 密文摘要、KeyVersion 和 wrapped Key 摘要。

`/unpack/download` 已删除，访问返回 HTTP 404 `API_NOT_FOUND`。Runtime 直接解密 APK 内置的 JGLP。

## 管理接口

管理接口使用 `Authorization: Bearer <JIAGU_ADMIN_TOKEN>`，包括公司管理、逻辑删除和撤销管理。生产环境应同时使用 HTTPS、来源网络限制和长随机 Admin Token。
