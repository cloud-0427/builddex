# 05. 安全、配置与运维

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| JIAGU_LISTEN_ADDR | `:8761` | HTTP 监听地址 |
| JIAGU_DATA_DIR | `data/companies` | 公司 SQLite 目录 |
| JIAGU_ADMIN_TOKEN | 见下文 | 管理员 Bearer Token；google 模式必填 |
| JIAGU_MASTER_KEY_B64 | 见下文 | 至少 32 随机字节的 Base64；google 模式必填 |
| JIAGU_MAX_PAYLOAD_MB | `64` | 单 Payload 最大 MB |
| JIAGU_CHALLENGE_TTL_SECONDS | `180` | challenge 有效期 |
| JIAGU_GRANT_TTL_SECONDS | `604800` | Payload Key Grant 有效期，默认 7 天 |
| JIAGU_DEVICE_CREDENTIAL_TTL_SECONDS | `2592000` | 设备 Credential 有效期，默认 30 天 |
| JIAGU_INTEGRITY_MODE | `disabled` | `disabled` 或 `google` |
| GOOGLE_APPLICATION_CREDENTIALS | 无 | Google 模式使用的服务账号 JSON 路径 |
| JIAGU_ENV | `dev` | 未传 `-env` 时使用的环境名 |
| JIAGU_LOG_LEVEL | 配置文件 | debug、info、warn 或 error |
| JIAGU_LOG_FORMAT | 配置文件 | text 或 json |
| JIAGU_LOG_DIR | 配置文件 | Lumberjack 日志目录 |
| JIAGU_LOG_MAX_SIZE_MB | `3072` | 单个活动日志达到该大小时滚动 |
| JIAGU_LOG_ROTATE_DAILY | `true` | 是否在每个本地自然日零点强制滚动 |
| JIAGU_LOG_MAX_AGE_DAYS | `2` | 历史备份最大保留天数 |
| JIAGU_LOG_MAX_BACKUPS | `10` | 历史备份最大数量 |
| JIAGU_LOG_COMPRESS | `true` | 是否 gzip 压缩滚动文件 |
| JIAGU_LOG_LOCAL_TIME | `true` | 滚动文件名是否使用本地时间 |
| JIAGU_LOG_CONSOLE | `true` | 是否输出控制台日志 |
| JIAGU_LOG_FILE_ENABLED | `true` | 是否输出滚动文件日志 |

当 `JIAGU_INTEGRITY_MODE=disabled` 时，如果没有配置 Admin Token 或 Master Key，服务会自动使用固定的本地开发值并打印警告。这使 `go run` 和普通 VS Code Go 调试可以直接启动。开发默认值公开且可预测，使用它生成的数据库不得迁移到生产环境。

当 `JIAGU_INTEGRITY_MODE=google` 时，两项仍然强制必填，缺少任一配置都会拒绝启动。

启动时服务会打印最终生效的监听地址、数据目录、Payload 上限、各项 TTL、Integrity 模式以及敏感配置的来源。Admin Token 和 Master Key只打印 SHA-256 短指纹，不打印明文；可以用该指纹判断 VS Code 环境变量是否确实生效。

## Play Integrity

`google` 模式通过 Google Application Default Credentials 调用：

```text
POST https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken
```

服务端验证：

- requestPackageName；
- requestHash；
- token 时间不超过 5 分钟；
- PLAY_RECOGNIZED；
- packageName；
- versionCode；
- certificateSha256Digest；
- MEETS_DEVICE_INTEGRITY。

Release 保存排序去重后的允许证书集合。APK/Debug 默认使用本地 signingConfig 证书；AAB 正式发布必须在插件配置中加入 Play App Signing 证书。Play Integrity 返回的实际证书必须命中该集合。证书轮换时可以同时保留当前证书和历史允许证书，但 PUBLISHED Release 的集合不可修改。

`disabled` 只允许本地联调。该模式仍校验 challenge、设备签名、应用绑定和设备公钥封装，但不具备正版 Play 安装和设备完整性保证。生产环境必须使用 `google`。

## 主密钥

`JIAGU_MASTER_KEY_B64` 同时用于派生：

- 公司标准 Key的 KEK；
- 公司 Ed25519 服务端签名 Key；Android Runtime 使用随 AAR 提供的兼容验证实现，不能依赖仅在 API 33+ 保证存在的平台 Ed25519 Provider；
- 每设备 Payload Key。

因此：

- 不得提交到 Git；
- 不得写入普通配置文件或日志；
- 应由云 Secret Manager、Kubernetes Secret 加密存储或系统凭据管理器注入；
- 必须做离线备份；
- 丢失后无法解密已有 Payload；
- 未设计迁移流程前不能直接替换。

生产增强建议是把这三个用途拆成不同 KMS/HSM Key。当前单主密钥是为单服务和最少依赖做出的明确简化。

## 服务端验签公钥

公司 Ed25519 Key按 `masterKey + companyId` 确定性派生。构建插件应在可信构建阶段调用 `public-config`，把公钥编译进 Native。

不能在运行时通过不受信任网络获取公钥后立即用它验证同一服务器响应，否则中间人可以同时替换公钥和响应。

## API 权限

- 管理接口仅允许后台管理网络访问，并使用长随机 Admin Token。
- 公司 API Key只用于打包和版本管理，不放进 Android APK。
- 建议在服务前增加 TLS 终止层；生产环境禁止明文 HTTP。
- 服务不输出 Key、Payload 明文、Integrity token、设备签名或完整公钥到日志。
- Runtime 和服务端不得输出完整 Credential、Grant、wrappedPayloadKey 或 authorize 响应；只允许记录 requestId、稳定错误 code、版本号、KeyVersion 和 Release 短指纹。

## SQLite 运维

适用范围：

- 单实例；
- 公司数量有限；
- 同一公司构建和设备授权并发中低；
- 核心 Payload 控制在几十 MB。

不适合：

- 多实例共同写网络共享盘；
- 单公司数百到数千并发授权请求；
- 超大 Payload 长期保存在数据库 BLOB；
- 跨地域强一致部署。

每个公司文件独立，因此某一公司高并发不会锁住其他公司的数据库。SQLite WAL 会产生临时的 `.db-wal` 和 `.db-shm` 文件，这是正常行为。

## 计数与授权

打包前检查：

```text
status == ACTIVE
authorized_from <= now
authorized_until == 0 || now <= authorized_until
pack_limit == 0 || pack_count < pack_limit
```

需要消耗公司下发额度时额外检查：

```text
delivery_limit == 0 || delivery_count < delivery_limit
```

计数使用 SQLite 事务，避免并发请求越过限额。

`pack_count` 采用唯一版本计数：仅首次成功插入 `packageName + versionCode` 时增加。DRAFT 更新、幂等重试、PUBLISHED 复用和失败请求不增加。

DRAFT 同一 `release_id` 只在首次下发时消耗一次公司额度；PUBLISHED 每次下发都消耗公司额度。Release 自身的 `delivery_count` 始终累计实际下发次数。

## 撤销

当前支持的强制路径：

- 公司：将 status 改成 SUSPENDED/REVOKED；
- Payload：调用 release revoke；
- 设备：写入 DEVICE revocation。

撤销不能让已经获得旧 Payload Key的攻击者失去该 Key。要提高撤销效果：

- 新应用版本提升 payloadKeyVersion；
- 缩短 Grant；
- 不允许高风险功能离线运行；
- 业务 API 同时检查设备授权；
- 将最高价值逻辑放在服务端。

## Payload Key 轮换

Release 状态规则：

1. 首次创建 DRAFT 时 `payloadKeyVersion=1`；
2. DRAFT 的业务 DEX、资源、Native、Payload 或允许证书集合变化时，保留 releaseId 并令 KeyVersion+1；
3. PUBLISHED 和 REVOKED 永不允许替换；
4. 新正式内容必须提升 Android versionCode，创建新的唯一 Release；
5. 灰度完成后可以撤销旧 Release，撤销后相同 package/version 永久禁止复用。

BuildHash、实际设备证书、Payload 明文摘要和 KeyVersion 都参与设备 Key 派生和 Grant/AAD 绑定。资源/Native 锁属于构建期版本一致性机制，不在 Android 启动时扫描 Play 生成的 Split APK。

主密钥轮换需要双主密钥读取、旧数据重封装和双公钥过渡，当前代码尚未实现，不能直接修改环境变量完成。

## 审计与清理

建议每日执行：

```sql
DELETE FROM challenges
WHERE expires_at < unixepoch() - 86400;

DELETE FROM operation_logs
WHERE created_at < unixepoch() - 15552000;
```

第二条示例保留 180 天。正式清理前应结合合同和审计要求调整。

## 生产上线检查

1. `JIAGU_INTEGRITY_MODE=google`。
2. 服务只通过 HTTPS 暴露。
3. Admin 接口限制来源网络。
4. 主密钥和 Google 凭据不进入代码仓库。
5. 公司 Ed25519 公钥已编译进 Native。
6. Native 同时验证包名、版本、证书、Payload hash 和 deviceId。
7. AAB 配置包含正确的 Play App Signing 证书摘要和允许轮换历史。
8. RuntimeConfig/Grant 同时绑定 certificateSetSha256、releaseBuildSha256 和 payloadKeyVersion。
9. RSA-OAEP 使用 SHA-1、MGF1-SHA1 和空 Label，服务端、Android 和测试一致。
10. 明文 DEX 不落盘。
11. SQLite 和主密钥分别备份。
12. 对数据库目录启用最小文件权限。
13. 定期执行恢复演练，而不只是备份。
