# 06. 多环境配置与日志滚动

日志实现使用 `go.uber.org/zap` v1.x 负责结构化日志，使用 `gopkg.in/natefinch/lumberjack.v2` 负责文件滚动、历史保留和 gzip 压缩。服务端不再包含自研日志文件滚动器。

## 配置加载顺序

服务按照以下优先级加载配置，后面的值覆盖前面的值：

```text
程序默认值
  ↓
config/application.json
  ↓
config/application.{env}.json
  ↓
环境变量 JIAGU_*
```

生产密钥不支持写进 JSON 配置，只能通过环境变量或外部 Secret 注入：

```text
JIAGU_ADMIN_TOKEN
JIAGU_MASTER_KEY_B64
GOOGLE_APPLICATION_CREDENTIALS
```

## 已提供环境

| 环境 | 配置文件 | 数据目录 | Integrity | 日志 |
|---|---|---|---|---|
| dev | `application.dev.json` | `data/dev/companies` | disabled | debug、文本、`logs/dev` |
| test | `application.test.json` | `data/test/companies` | disabled | debug、文本、`logs/test` |
| prod | `application.prod.json` | `data/prod/companies` | disabled（线上过渡阶段） | info、JSON、`logs/prod` |

公共配置在 `application.json`，默认端口为 `8761`。日志由 Zap 输出、Lumberjack 滚动，历史备份默认最多保留 2 天。

## 启动时指定环境

开发环境：

```powershell
go run ./cmd/jiagu-server -env dev -config-dir ./config
```

测试环境：

```powershell
go run ./cmd/jiagu-server -env test -config-dir ./config
```

生产环境：

```powershell
$env:JIAGU_ADMIN_TOKEN = "secure-admin-token"
$env:JIAGU_MASTER_KEY_B64 = "base64-random-master-key"
$env:JIAGU_INTEGRITY_MODE = "disabled"
./jiagu-server.exe -env prod -config-dir ./config
```

生产环境当前暂时关闭 Play Integrity，但必须使用独立的 Admin Token 和随机 Master Key，禁止依赖开发默认值。后续启用时将 `JIAGU_INTEGRITY_MODE` 设置为 `google`，并配置 `GOOGLE_APPLICATION_CREDENTIALS`。

也可以使用：

```text
JIAGU_ENV=prod
```

命令行 `-env` 优先于 `JIAGU_ENV`。

## 构建不同环境的二进制

不指定构建环境时，运行阶段默认选择 dev。也可以将默认环境写入二进制：

```powershell
go build -ldflags "-X main.buildEnvironment=dev" `
  -o build/jiagu-server-dev.exe ./cmd/jiagu-server

go build -ldflags "-X main.buildEnvironment=test" `
  -o build/jiagu-server-test.exe ./cmd/jiagu-server

go build -ldflags "-X main.buildEnvironment=prod" `
  -o build/jiagu-server-prod.exe ./cmd/jiagu-server
```

即使二进制内置了默认环境，仍可通过 `-env` 显式覆盖，方便应急验证。

## VS Code

`.vscode/launch.json` 已包含：

- `Debug Jiagu Server (dev :8761)`；
- `Debug Jiagu Server (test :8761)`。

两个配置使用不同的数据目录和本地密钥，避免调试数据互相污染。prod 不提供默认 VS Code 调试入口，防止误用开发密钥启动生产配置。

## 日志文件

活动日志文件名：

```text
{directory}/{filePrefix}.log
```

例如：

```text
logs/dev/jiagu-server.log
logs/dev/jiagu-server-2026-08-26T15-30-00.000.log.gz
```

生产环境启用 `rotateDaily` 后，每个本地自然日的 00:00 必然执行一次滚动；服务启动时如果发现活动日志属于前一个自然日，也会立即滚动。`maxSizeMB` 是附加条件：当天文件达到该大小时会额外滚动。当前文件始终为 `jiagu-server.log`，历史备份按照时间戳重命名，并可进行 gzip 压缩。

使用 `run.sh` 启动时，`logs/prod/console.log` 只是 `nohup` 的标准输出/错误启动日志，不由 Lumberjack 管理。生产环境的应用日志及滚动结果应检查 `logs/prod/jiagu-server.log`；修改配置后需要完整停止并重新启动进程。

默认 `maxAgeDays=2`，删除超过 2×24 小时的历史备份；`maxBackups=10` 同时限制最多保留的备份数量。清理在 Lumberjack 执行滚动时触发，活动文件不会被删除。

## 日志配置

```json
{
  "logging": {
    "level": "info",
    "format": "json",
    "console": true,
    "fileEnabled": true,
    "directory": "logs/prod",
    "filePrefix": "jiagu-server",
    "maxSizeMB": 3072,
    "rotateDaily": true,
    "maxAgeDays": 2,
    "maxBackups": 10,
    "compress": true,
    "localTime": true,
    "successSampleRate": 0.1,
    "slowRequestThresholdMs": 500
  }
}
```

支持级别：

```text
debug
info
warn
error
```

支持格式：

```text
text
json
```

可以使用环境变量覆盖：

| 环境变量 | 示例 |
|---|---|
| JIAGU_LOG_LEVEL | `debug` |
| JIAGU_LOG_FORMAT | `json` |
| JIAGU_LOG_DIR | `D:\logs\jiagu` |
| JIAGU_LOG_MAX_SIZE_MB | `3072` |
| JIAGU_LOG_ROTATE_DAILY | `true` |
| JIAGU_LOG_MAX_AGE_DAYS | `2` |
| JIAGU_LOG_MAX_BACKUPS | `10` |
| JIAGU_LOG_COMPRESS | `true` |
| JIAGU_LOG_LOCAL_TIME | `true` |
| JIAGU_LOG_CONSOLE | `true` |
| JIAGU_LOG_FILE_ENABLED | `true` |
| JIAGU_LOG_SUCCESS_SAMPLE_RATE | `0.1` |
| JIAGU_LOG_SLOW_REQUEST_MS | `500` |

成功响应采样基于 requestId 确定性计算；同一个 requestId 的采样结果稳定。4xx、5xx、慢请求以及发生 panic 的请求不受成功采样率影响。快速成功的 `/healthz` 和 `/readyz` 不写访问日志。

## 数据库与后台维护配置

| JSON 字段 | 默认值 | 环境变量 |
|---|---:|---|
| `challengeCleanupIntervalSeconds` | 300 | `JIAGU_CHALLENGE_CLEANUP_INTERVAL_SECONDS` |
| `challengeCleanupBatchSize` | 500 | `JIAGU_CHALLENGE_CLEANUP_BATCH_SIZE` |
| `maxOpenCompanyDatabases` | 128 | `JIAGU_MAX_OPEN_COMPANY_DATABASES` |
| `companyDatabaseIdleSeconds` | 900 | `JIAGU_COMPANY_DATABASE_IDLE_SECONDS` |
| `databaseMaxOpenConns` | 2 | `JIAGU_DATABASE_MAX_OPEN_CONNS` |
| `databaseMaxIdleConns` | 1 | `JIAGU_DATABASE_MAX_IDLE_CONNS` |
| `databaseConnMaxIdleSeconds` | 300 | `JIAGU_DATABASE_CONN_MAX_IDLE_SECONDS` |
| `databaseConnMaxLifetimeSeconds` | 1800 | `JIAGU_DATABASE_CONN_MAX_LIFETIME_SECONDS` |

后台任务只清理已经打开的企业数据库；未访问数据库中的过期 Challenge 会在该数据库重新进入活跃集合后的清理周期处理。数据库句柄超过空闲时间或缓存容量时，只回收没有活动请求租约的句柄。

## 日志内容

启动日志记录：

- 最终环境名；
- 监听地址和数据目录；
- Payload 上限；
- challenge、Grant、Credential TTL；
- Integrity 模式；
- 日志级别、格式、目录、滚动大小、保留时间和备份数；
- Admin Token 和 Master Key来源及短指纹。

HTTP 访问日志记录：

```text
requestId
method
path
status
code
bytes
duration
```

Release 构建日志可以额外记录：

```text
releaseIdShort
packageName
versionCode
operation=CREATED|REUSED|UPDATED|PUBLISHED|REVOKED
payloadKeyVersion
changedComponents
```

只记录变化组件名称，不记录完整旧/新 Hash。服务端 JSON 响应统一为 `code/message/details`，访问日志中的 `code` 使用同一稳定机器码。

日志不记录：

- 请求体；
- Admin Token 或公司 API Key；
- Master Key或 Payload Key；
- Integrity token；
- Device Credential 或 Grant 全文；
- wrappedPayloadKey 和 authorize 完整响应；
- 完整业务 DEX、资源、Native 或证书集合 Hash；
- Payload 内容。

## 单实例约束

Lumberjack 明确面向单进程写入。多个实例不得同时写同一个活动日志文件。多实例部署时应让每个实例写独立文件，或只输出 JSON 到 stdout 并由平台统一采集和保留。
