# Jiagu 单体授权服务

这是 Jiagu 的服务端实现，默认监听 `8761` 端口。服务负责公司授权、Release 元数据、Payload Key 托管、设备凭证、Play Integrity 校验、设备 Key 封装、撤销和计数。业务 Payload 加密后内置于 APK，服务端不接收、不保存也不提供下载。

主要约束：

- 单体 Go HTTP 服务，不依赖 Redis、MySQL 或独立对象存储。
- 每家公司一个 SQLite 文件，默认位于 `data/companies/{companyId}.db`。
- 不建立全量设备表，不保存每设备 Payload 或每设备明文 Key。
- 标准 Payload 加密后作为 BLOB 保存到公司 SQLite。
- 同一 `packageName + versionCode` 只有一个 Release；DRAFT 可修订，PUBLISHED/REVOKED 不可替换。
- Release 锁定最终业务 DEX、Manifest/resources/assets、Native Library 和允许签名证书集合。
- 设备身份凭证保存在客户端，服务端通过签名验证。
- 设备 Payload Key 由服务端主密钥和设备/版本上下文动态派生。

## 启动

`disabled` 本地模式可以不配置环境变量直接启动：

```powershell
go run ./cmd/jiagu-server
```

此时服务会使用公开、固定的开发 Admin Token 和 Master Key，并打印安全警告。开发数据库不得用于生产。

需要自定义本地配置时，PowerShell 示例：

```powershell
$env:JIAGU_ADMIN_TOKEN = "replace-with-a-long-admin-token"
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$env:JIAGU_MASTER_KEY_B64 = [Convert]::ToBase64String($bytes)
$env:JIAGU_INTEGRITY_MODE = "disabled"
go run ./cmd/jiagu-server
```

生产环境应使用：

```text
JIAGU_INTEGRITY_MODE=google
GOOGLE_APPLICATION_CREDENTIALS=/secure/path/play-integrity-service-account.json
```

健康检查：

```text
GET http://127.0.0.1:8761/healthz
```

## 文档

- [总体架构](docs/01-architecture.md)
- [SQLite 数据库设计](docs/02-database.md)
- [API 分类与协议](docs/03-api.md)
- [打包与解包流程](docs/04-pack-unpack-flow.md)
- [安全、配置与运维](docs/05-security-operations.md)
- [多环境配置与日志滚动](docs/06-environments-logging.md)
- [Release 构建一致性锁实施计划](../docs/02-release-build-lock-implementation-plan.md)

## 验证

```powershell
go test ./...
go build ./cmd/jiagu-server
```
