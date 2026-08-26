# 03. API 分类与协议

## 通用约定

服务地址：

```text
http://host:8761
```

JSON API 使用：

```text
Content-Type: application/json
```

错误格式：

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "error description"
  }
}
```

每个响应包含 `X-Request-Id`。客户端可以传入同名请求头用于链路追踪。

权限分类：

| 类型 | 请求头 |
|---|---|
| 系统公开接口 | 无 |
| 管理员接口 | `Authorization: Bearer {JIAGU_ADMIN_TOKEN}` |
| 公司打包接口 | `X-Company-Key: {companyApiKey}` |
| 设备解包接口 | challenge、设备签名和服务端签名 Credential/Grant |

## A. 系统接口

### `GET /healthz`

功能：健康检查并返回当前 Integrity 模式。

```json
{
  "status": "UP",
  "port": 8761,
  "integrityMode": "google"
}
```

## B. 公司管理接口

### `POST /api/v1/companies`

权限：管理员。

功能：创建公司 SQLite 文件和初始公司 API Key。

请求：

```json
{
  "companyId": "acme",
  "description": "ACME Android 产品线",
  "authorizedFrom": 1780000000,
  "authorizedUntil": 1810000000,
  "packLimit": 100,
  "deliveryLimit": 100000,
  "ext": {
    "contractNo": "C-2026-001",
    "contact": "security@example.com"
  }
}
```

`authorizedFrom` 省略或为 0 时使用当前时间。两个 limit 为 0 表示不限。

响应中的 `companyApiKey` 只返回一次。

### `GET /api/v1/companies`

权限：管理员。

功能：扫描公司数据库目录并返回公司列表、授权状态和计数。

### `GET /api/v1/companies/{companyId}`

权限：管理员。

功能：查询单个公司的说明、授权时间、限额、打包次数、下发次数和扩展字段。

### `PATCH /api/v1/companies/{companyId}`

权限：管理员。

功能：修改公司授权。

可修改字段：

```json
{
  "description": "新的说明",
  "authorizedFrom": 1780000000,
  "authorizedUntil": 1810000000,
  "packLimit": 200,
  "deliveryLimit": 200000,
  "status": "ACTIVE",
  "ext": {}
}
```

未传字段保持不变。状态支持 ACTIVE、SUSPENDED、EXPIRED、REVOKED。公司进入 `REVOKED` 后不可恢复为其他状态。

### `DELETE /api/v1/companies/{companyId}`

权限：管理员。

功能：逻辑删除公司。接口将公司状态修改为 `REVOKED`，不删除公司 SQLite 文件、版本数据和操作记录。重复调用返回成功。

### 公司管理页面

服务端内置公司管理页面，启动后访问：

```text
http://host:8761/admin/
```

管理员 Token 仅保存在当前浏览器标签页的 `sessionStorage` 中。

### `GET /api/v1/companies/{companyId}/public-config`

权限：公开。

功能：返回该公司的服务端验签公钥和算法，供构建插件写入 Native。

注意：运行时从网络动态获取该公钥不能建立信任。正式 APK 必须在可信构建阶段固定该公钥。

## C. 打包和发布接口

### `POST /api/v1/companies/{companyId}/pack/releases`

权限：公司 API Key。

功能：上传 Payload，创建 DRAFT 版本并增加公司 `pack_count`。

**唯一性约束**：同一公司下 `(packageName, payloadId, payloadVersion)` 必须唯一。

**幂等性说明**：
- 如果尝试创建的记录已存在且状态为 `DRAFT`：服务器将使用本次请求的内容**更新**现有草稿（主要用于构建失败后的重试），返回 201。
- 如果尝试创建的记录已存在且状态为 `PUBLISHED`：
    - 如果上传内容的 SHA-256 摘要与已有记录一致：服务器返回 `200 OK` 及已有记录信息。
    - 如果内容不一致：返回 `409 Conflict`，提示需要升级版本号。

请求类型：`multipart/form-data`。

| 字段 | 类型 | 说明 |
|---|---|---|
| payloadId | text | Payload 逻辑名称 |
| payloadVersion | integer | Payload 版本 |
| packageName | text | Android 包名 |
| versionCode | integer | APK versionCode |
| certificateSha256 | text | Play Integrity 返回格式的证书 SHA-256 摘要 |
| payload | file | 核心 DEX/SO/容器原文 |

PowerShell/curl 示例：

```text
curl -X POST http://127.0.0.1:8761/api/v1/companies/acme/pack/releases \
  -H "X-Company-Key: COMPANY_KEY" \
  -F "payloadId=app-main" \
  -F "payloadVersion=1" \
  -F "packageName=com.example.app" \
  -F "versionCode=5" \
  -F "certificateSha256=PLAY_INTEGRITY_CERT_DIGEST" \
  -F "payload=@payload.bin"
```

### `GET /api/v1/companies/{companyId}/pack/releases`

权限：公司 API Key。

功能：查询公司所有 Payload 版本，不返回加密 BLOB 和封装 Key。

### `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish`

权限：公司 API Key。

功能：将 DRAFT 改为 PUBLISHED。只有 PUBLISHED 版本允许设备注册、授权和下载。

### `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke`

权限：公司 API Key。

功能：撤销 Payload 版本。撤销后新授权和下载立即失败。

## D. 解包和设备授权接口

这里的“解包”指 Android 获得设备专属 Key和专属加密 Payload，最终解密发生在 Android Keystore + Native 中。

### `POST /api/v1/companies/{companyId}/unpack/challenges`

权限：公开，但受公司授权状态限制。

请求：

```json
{
  "purpose": "ENROLL"
}
```

purpose 支持：

- `ENROLL`：首次签发设备 Credential；
- `AUTHORIZE`：申请 Payload Grant。

### `POST /api/v1/companies/{companyId}/unpack/enroll`

功能：验证设备私钥持有、Play Integrity 和一次性 challenge，返回无服务端设备记录的签名 Credential。

```json
{
  "challengeId": "...",
  "challenge": "...",
  "releaseId": "...",
  "signPublicKey": "Base64URL SubjectPublicKeyInfo ECDSA P-256",
  "wrapPublicKey": "Base64URL SubjectPublicKeyInfo RSA >= 2048",
  "integrityToken": "...",
  "deviceSignature": "Base64URL ECDSA ASN.1 signature"
}
```

生产建议使用 RSA-3072 wrap key。服务端最低接受 2048 位是为了兼容测试设备。

### `POST /api/v1/companies/{companyId}/unpack/authorize`

功能：验证 Credential、设备签名、Integrity、撤销状态和版本绑定，派生设备 Payload Key并用设备 RSA 公钥封装。

```json
{
  "challengeId": "...",
  "challenge": "...",
  "releaseId": "...",
  "deviceCredential": "signed JWS",
  "integrityToken": "...",
  "deviceSignature": "..."
}
```

响应：

```json
{
  "grant": "signed JWS",
  "wrappedPayloadKey": "...",
  "wrapAlgorithm": "RSA-OAEP-SHA256",
  "wrapLabel": "grantId",
  "downloadPath": "/api/v1/companies/acme/unpack/download",
  "expiresAt": 1780000000
}
```

客户端必须先验证 Grant 的 Ed25519 签名和所有绑定字段，再用 Keystore 私钥执行 RSA-OAEP 解封。

### `POST /api/v1/companies/{companyId}/unpack/download`

功能：验证 Grant，重新派生设备 Key，将标准 Payload 实时转换为设备专属 AES-GCM 密文，并增加 `delivery_count`。

请求：

```json
{
  "grant": "signed JWS"
}
```

响应类型：

```text
application/octet-stream
```

二进制格式见 [打包与解包流程](04-pack-unpack-flow.md)。

## E. 撤销接口

### `POST /api/v1/companies/{companyId}/admin/revocations`

权限：管理员。

```json
{
  "targetType": "DEVICE",
  "targetHash": "deviceId",
  "reason": "abnormal extraction activity",
  "expiresAt": 0
}
```

当前运行时强制执行 DEVICE 撤销。证书、版本和 Payload 撤销可直接使用版本 revoke 接口；其他 targetType 作为扩展保留。

## HTTP 状态

| 状态 | 含义 |
|---|---|
| 200 | 查询、授权或下载成功 |
| 201 | 公司、版本、challenge、Credential 或撤销项创建成功 |
| 400 | 请求格式错误 |
| 401 | 管理员、公司或设备证明无效 |
| 403 | 公司授权、Integrity、设备或应用身份被拒绝 |
| 404 | 公司或 Payload 不存在 |
| 409 | 重复资源、非法状态迁移或 challenge 重放 |
| 413 | Payload 超过上限 |
| 429 | 打包或下发额度用尽 |
| 500 | 服务端内部错误 |
