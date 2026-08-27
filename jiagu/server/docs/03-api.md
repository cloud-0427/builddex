# 03. API 分类与协议

## 通用约定

服务地址：

```text
http://host:8761
```

生产环境必须通过 HTTPS 暴露。JSON 请求使用 `Content-Type: application/json`，每个响应包含 `X-Request-Id`；客户端可传入同名请求头用于链路追踪。

### 统一 JSON 响应

所有 JSON 成功和失败响应统一为：

```json
{
  "code": "STABLE_MACHINE_CODE",
  "message": "Human readable message.",
  "details": {}
}
```

约束：

- `code` 是稳定机器码；客户端不得解析 `message`；
- `message` 用于开发者和运维日志；
- `details` 永远是 JSON Object，没有内容时返回 `{}`；
- 列表统一放在 `details.items`；
- HTTP 状态码继续表达成功、权限、冲突和服务端错误；
- `/unpack/download` 成功时返回 `application/octet-stream`，失败时返回上述 JSON。

成功示例：

```json
{
  "code": "RELEASE_CREATED",
  "message": "Draft release created.",
  "details": {
    "releaseId": "...",
    "status": "DRAFT",
    "operation": "CREATED",
    "keyRotated": false,
    "payloadKeyVersion": 1
  }
}
```

失败示例：

```json
{
  "code": "PUBLISHED_VERSION_MODIFIED",
  "message": "Published application version cannot be modified. Increase versionCode.",
  "details": {
    "packageName": "com.example.app",
    "versionCode": 105,
    "changedComponents": ["BUSINESS_DEX", "RESOURCES"]
  }
}
```

权限分类：

| 类型 | 请求凭据 |
|---|---|
| 系统公开接口 | 无 |
| 管理员接口 | `Authorization: Bearer {JIAGU_ADMIN_TOKEN}` |
| 公司打包接口 | `X-Company-Key: {companyApiKey}` |
| 设备解包接口 | challenge、设备签名、Play Integrity 和服务端签名 Credential/Grant |

### 稳定 code 分类

实现中应集中定义 code 常量，禁止 Handler 临时拼接。核心分类：

| 分类 | code 示例 |
|---|---|
| 成功 | `HEALTHY`、`COMPANY_CREATED`、`RELEASE_CREATED`、`RELEASE_UPDATED`、`RELEASE_REUSED`、`RELEASE_PUBLISHED`、`DEVICE_ENROLLED`、`PAYLOAD_AUTHORIZED` |
| 请求格式 | `INVALID_JSON`、`INVALID_MULTIPART`、`INVALID_RELEASE`、`INVALID_DIGEST`、`PAYLOAD_REQUIRED`、`PAYLOAD_TOO_LARGE` |
| 构建校验 | `BUSINESS_DEX_HASH_MISMATCH`、`PUBLISHED_VERSION_MODIFIED`、`REVOKED_VERSION_REUSE_FORBIDDEN`、`OUTPUT_VERSION_CODE_MISMATCH` |
| 权限 | `ADMIN_UNAUTHORIZED`、`COMPANY_UNAUTHORIZED`、`COMPANY_NOT_AUTHORIZED`、`INVALID_DEVICE_CREDENTIAL`、`INVALID_DEVICE_PROOF` |
| Runtime 身份 | `APP_IDENTITY_MISMATCH`、`RELEASE_BINDING_MISMATCH`、`INTEGRITY_REJECTED`、`DEVICE_REVOKED` |
| 配额/服务 | `PACK_LIMIT_EXCEEDED`、`DELIVERY_LIMIT_EXCEEDED`、`NOT_FOUND`、`INTERNAL_ERROR` |

具体 Handler 可以增加同类稳定 code，但删除或改变既有 code 语义属于协议变更。

## A. 系统接口

### `GET /healthz`

功能：健康检查并返回当前 Integrity 模式。

```json
{
  "code": "HEALTHY",
  "message": "Jiagu server is healthy.",
  "details": {
    "status": "UP",
    "port": 8761,
    "integrityMode": "google"
  }
}
```

## B. 公司管理接口

### `POST /api/v1/companies`

权限：管理员。功能：创建公司 SQLite 文件和初始公司 API Key。

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

`authorizedFrom` 省略或为 0 时使用当前时间，两个 limit 为 0 表示不限。响应 code 为 `COMPANY_CREATED`，只在 `details.companyApiKey` 返回一次明文公司 Key。

### `GET /api/v1/companies`

权限：管理员。响应 code 为 `COMPANIES_LISTED`，公司列表位于 `details.items`。

### `GET /api/v1/companies/{companyId}`

权限：管理员。响应 code 为 `COMPANY_FOUND`，公司授权、限额和计数位于 `details`。

### `PATCH /api/v1/companies/{companyId}`

权限：管理员。未传字段保持不变，状态支持 `ACTIVE`、`SUSPENDED`、`EXPIRED`、`REVOKED`；公司进入 REVOKED 后不可恢复。响应 code 为 `COMPANY_UPDATED`。

### `DELETE /api/v1/companies/{companyId}`

权限：管理员。逻辑删除公司，不删除 SQLite 文件、版本数据和操作记录。重复调用幂等成功，响应 code 为 `COMPANY_REVOKED`。

### 公司管理页面

访问 `http://host:8761/admin/`。管理页面必须解析统一 JSON 信封，管理员 Token 仅保存在当前标签页 `sessionStorage`。

### `GET /api/v1/companies/{companyId}/public-config`

权限：公开。响应 code 为 `PUBLIC_CONFIG_FOUND`，公司 Ed25519 验签公钥和协议算法位于 `details`。正式 APK 必须在可信构建阶段固定公钥，运行时不能动态获取后立即信任。

## C. 打包和发布接口

### `GET /api/v1/companies/{companyId}/pack/auth-check`

权限：公司 API Key。

功能：上传 Payload 前的轻量鉴权预检。成功返回 HTTP 200 和 `COMPANY_AUTHORIZED`；Key 无效返回 HTTP 401 和 `COMPANY_UNAUTHORIZED`。插件必须在 multipart 上传前调用，使 4xx JSON body 能在大请求体尚未发送时可靠返回。正式上传接口仍会再次鉴权。

### `POST /api/v1/companies/{companyId}/pack/releases`

权限：公司 API Key。

功能：上传标准 JG3 Payload 和 Variant 构建摘要，创建、更新或复用唯一 Release。

唯一身份：

```text
packageName + versionCode
```

请求类型：`multipart/form-data`。

| 字段 | 类型 | 说明 |
|---|---|---|
| payloadId | text | 固定为 `app-main` |
| payloadVersion | integer | 必须等于 versionCode |
| packageName | text | 最终 Android applicationId |
| versionCode | integer | Variant 所有 Output 的统一 versionCode |
| certificateSha256Digest | repeated text | 一个或多个允许证书 SHA-256 Base64URL 摘要 |
| businessDexSha256 | text | 最终业务 DEX 集合摘要 |
| resourcesSha256 | text | 最终 Manifest/resources/res/assets 摘要 |
| nativeLibsSha256 | text | 最终 Native 集合摘要，不含 `liblog_ext.so` |
| payload | file | JG3 业务 DEX Payload 原文 |

服务端必须：

1. 校验每个摘要可 Base64URL 解码为 32 字节；
2. 对证书集合排序、去重并计算 `certificateSetSha256`；
3. 解析 JG3 并复算 `businessDexSha256`；
4. 根据三个组件摘要复算 `releaseBuildSha256`；
5. 使用原子事务执行状态机和 `pack_count`。

```text
curl -X POST http://127.0.0.1:8761/api/v1/companies/acme/pack/releases \
  -H "X-Company-Key: COMPANY_KEY" \
  -F "payloadId=app-main" \
  -F "payloadVersion=105" \
  -F "packageName=com.example.app" \
  -F "versionCode=105" \
  -F "certificateSha256Digest=PLAY_APP_SIGNING_CERT" \
  -F "certificateSha256Digest=OPTIONAL_SIDELOAD_CERT" \
  -F "businessDexSha256=..." \
  -F "resourcesSha256=..." \
  -F "nativeLibsSha256=..." \
  -F "payload=@payload.jg3"
```

状态机：

| 现有状态 | 请求 | HTTP | code | 行为 |
|---|---|---:|---|---|
| 不存在 | 合法 | 201 | `RELEASE_CREATED` | 创建 DRAFT，KeyVersion=1，pack_count+1 |
| DRAFT | 全部绑定字段相同 | 200 | `RELEASE_REUSED` | 原样复用 |
| DRAFT | 任一绑定字段变化 | 200 | `RELEASE_UPDATED` | 保留 releaseId，KeyVersion+1，重加密 Payload |
| PUBLISHED | 全部绑定字段相同 | 200 | `RELEASE_REUSED` | 原样复用 |
| PUBLISHED | 任一绑定字段变化 | 409 | `PUBLISHED_VERSION_MODIFIED` | 拒绝并要求提升 versionCode |
| REVOKED | 任意 | 409 | `REVOKED_VERSION_REUSE_FORBIDDEN` | 永久拒绝复用该版本 |

成功 `details` 至少返回：

```json
{
  "releaseId": "...",
  "payloadId": "app-main",
  "payloadVersion": 105,
  "packageName": "com.example.app",
  "versionCode": 105,
  "certificateSha256Digests": ["..."],
  "certificateSetSha256": "...",
  "businessDexSha256": "...",
  "resourcesSha256": "...",
  "nativeLibsSha256": "...",
  "releaseBuildSha256": "...",
  "payloadPlaintextSha256": "...",
  "payloadKeyVersion": 1,
  "status": "DRAFT",
  "operation": "CREATED",
  "keyRotated": false
}
```

冲突 `details.changedComponents` 可包含 `BUSINESS_DEX`、`RESOURCES`、`NATIVE_LIBS`、`SIGNING_CERTIFICATES`、`PAYLOAD`。不返回旧完整 Hash。

正式版发布后，同 package/version 的 Debug 构建会收到 `PUBLISHED_VERSION_MODIFIED`；插件应提示提升 versionCode 或修改 Debug applicationId。

### `GET /api/v1/companies/{companyId}/pack/releases`

权限：公司 API Key。响应 code 为 `RELEASES_LISTED`，列表位于 `details.items`，不返回加密 BLOB 和封装 Key。

### `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish`

权限：公司 API Key。只有最终 APK/Split APK/AAB 已生成并通过插件校验后才能调用。重复发布幂等成功，响应 code 为 `RELEASE_PUBLISHED`。

### `POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke`

权限：公司 API Key。REVOKED 永久禁止以相同 package/version 重新创建，响应 code 为 `RELEASE_REVOKED`。

## D. 解包和设备授权接口

### `POST /api/v1/companies/{companyId}/unpack/challenges`

权限：公开，但受公司授权状态限制。请求 purpose 支持 `ENROLL` 和 `AUTHORIZE`。响应 code 为 `CHALLENGE_CREATED`，challenge 字段位于 `details`。

### `POST /api/v1/companies/{companyId}/unpack/enroll`

功能：验证设备私钥持有、Play Integrity、实际安装证书和一次性 challenge，返回签名 Credential。

```json
{
  "challengeId": "...",
  "challenge": "...",
  "releaseId": "...",
  "actualCertificateSha256": "...",
  "signPublicKey": "Base64URL SubjectPublicKeyInfo ECDSA P-256",
  "wrapPublicKey": "Base64URL SubjectPublicKeyInfo RSA >= 2048",
  "integrityToken": "...",
  "deviceSignature": "Base64URL ECDSA ASN.1 signature"
}
```

实际证书必须属于 Release 的允许集合，并与 Play Integrity 结果一致。生产建议 RSA-3072。响应 code 为 `DEVICE_ENROLLED`，Credential 位于 `details.deviceCredential`。

### `POST /api/v1/companies/{companyId}/unpack/authorize`

功能：验证 Credential、设备签名、Integrity、撤销状态和 Release 绑定，派生设备 Payload Key，并使用设备 RSA 公钥封装。

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

成功响应：

```json
{
  "code": "PAYLOAD_AUTHORIZED",
  "message": "Payload access authorized.",
  "details": {
    "grant": "signed JWS",
    "wrappedPayloadKey": "...",
    "wrapAlgorithm": "RSA-OAEP-SHA1",
    "wrapLabel": "",
    "downloadPath": "/api/v1/companies/acme/unpack/download",
    "expiresAt": 1780000000
  }
}
```

客户端必须先验证 Grant 的 Ed25519 签名和全部绑定字段，再使用：

```text
RSA/ECB/OAEPPadding
OAEP SHA-1
MGF1 SHA-1
PSource.PSpecified.DEFAULT
empty label
```

身份或 Release 配置拒绝时，服务端在 `details` 返回 expected 信息；Runtime 使用本地 RuntimeConfig 和实际 APK 身份补充 actual 信息。服务端不信任客户端未签名的 actual 字段。

### `POST /api/v1/companies/{companyId}/unpack/download`

功能：验证 Grant，重新派生设备 Key，将标准 Payload 转换为设备专属 AES-GCM 密文，并增加 `delivery_count`。

```json
{
  "grant": "signed JWS"
}
```

成功响应为 `application/octet-stream`，二进制格式见 [打包与解包流程](04-pack-unpack-flow.md)。失败返回统一 JSON 信封。

## E. 撤销接口

### `POST /api/v1/companies/{companyId}/admin/revocations`

权限：管理员。当前运行时强制执行 DEVICE 撤销。证书、版本和 Payload 撤销使用 Release revoke；其他 targetType 作为扩展保留。响应 code 为 `REVOCATION_CREATED`。

## HTTP 状态

| 状态 | 含义 |
|---|---|
| 200 | 查询、更新、复用、授权、发布或撤销成功 |
| 201 | 公司、首次 Release、challenge、Credential 或撤销项创建成功 |
| 400 | 请求格式、字段或摘要无效 |
| 401 | 管理员、公司或设备证明无效 |
| 403 | 公司授权、Integrity、设备或应用身份被拒绝 |
| 404 | 公司或 Release 不存在 |
| 409 | Release 尚未发布、已发布版本修改、已撤销版本复用、非法状态迁移或 challenge 重放 |
| 413 | Payload 超过上限 |
| 429 | 首次版本创建或设备下发额度用尽 |
| 500 | 服务端内部错误 |
