# 02. SQLite 数据库设计

## 文件组织

每家公司对应一个数据库文件：

```text
data/companies/{companyId}.db
```

SQLite 配置：

```text
journal_mode = WAL
synchronous = NORMAL
foreign_keys = ON
busy_timeout = 5000 ms
```

同一公司最多保留 4 个数据库连接，适合单实例、小规模并发。不要让多个服务实例同时通过网络共享盘写同一个 SQLite 文件。

## 表说明机制

SQLite 没有原生 `COMMENT ON TABLE`。因此每个公司数据库均包含 `schema_descriptions` 表，用来保存所有表的中文用途说明。

可直接查询：

```sql
SELECT table_name, description
FROM schema_descriptions
ORDER BY table_name;
```

## `schema_meta`

用途：记录数据库 Schema 版本，为后续自动迁移保留入口。

当前最新版本：**2**

| 字段 | 说明 |
|---|---|
| schema_version | 当前数据库结构版本 |
| updated_at | 最近一次迁移时间，Unix 秒 |

## `schema_descriptions`

用途：保存每张表的中文说明，使单个 SQLite 文件自身具备数据字典。

| 字段 | 说明 |
|---|---|
| table_name | 表名，主键 |
| description | 表的中文用途说明 |

## `company_info`

用途：保存本文件所属公司的身份、授权时间、限额和累计计数。每个数据库只有一行。

| 字段 | 说明 |
|---|---|
| company_id | 公司唯一标识，同时决定数据库文件名 |
| description | 公司说明 |
| authorized_from | 授权开始时间，Unix 秒 |
| authorized_until | 授权结束时间；0 表示不设置结束时间 |
| pack_limit | 最大允许打包次数；0 表示不限 |
| delivery_limit | 最大允许成功下发次数；0 表示不限 |
| pack_count | 成功创建 Payload 版本的累计次数 |
| delivery_count | 成功生成并准备返回设备 Payload 的累计次数 |
| status | ACTIVE、SUSPENDED、EXPIRED 或 REVOKED |
| ext_json | 公司级扩展 JSON，用于联系人、渠道、合同号等后续字段 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

计数规则：

- `pack_count` 只在加密 Payload 成功写入时增加。
- `delivery_count` 在设备 Payload 成功生成后、HTTP Body 写出前增加。
- 客户端断开连接时，该次下发仍可能计数，因为服务端已经完成生成。

## `company_api_keys`

用途：保存公司调用打包接口的 API Key 摘要。

| 字段 | 说明 |
|---|---|
| id | 自增主键 |
| key_hash | API Key 的 SHA-256 Base64URL 摘要，不保存明文 |
| description | Key 用途说明 |
| status | ACTIVE 或 REVOKED |
| created_at | 创建时间 |
| revoked_at | 撤销时间，0 表示未撤销 |

创建公司时只返回一次明文公司 API Key。遗失后不应读取数据库恢复，而应扩展 Key 轮换接口生成新 Key。

## `payload_releases`

用途：保存构建产生的标准加密 Payload 和版本绑定信息。

| 字段 | 说明 |
|---|---|
| release_id | 服务端生成的版本 ID |
| payload_id | Payload 逻辑标识，如 `app-main` |
| payload_version | Payload 版本号 |
| package_name | 允许使用该 Payload 的 Android 包名 |
| version_code | 允许使用该 Payload 的 APK versionCode |
| certificate_sha256 | Play Integrity 格式的签名证书 SHA-256 摘要 |
| plaintext_sha256 | 原始 Payload SHA-256 Base64URL |
| canonical_ciphertext_sha256 | 标准密文 SHA-256 Base64URL |
| canonical_payload | AES-256-GCM 加密后的标准 Payload BLOB |
| canonical_key_ciphertext | 使用公司 KEK 加密后的随机标准 Payload Key |
| payload_key_version | 设备 Key 派生版本，用于轮换 |
| status | DRAFT、PUBLISHED 或 REVOKED |
| created_at | 打包时间 |
| published_at | 发布时间 |
| revoked_at | 撤销时间 |

唯一约束：

```text
package_name + payload_id + payload_version
```

## `challenges`

用途：保存短期一次性 challenge，防止设备注册和授权请求被重放。

| 字段 | 说明 |
|---|---|
| challenge_id | challenge ID |
| challenge | 32 字节随机值的 Base64URL |
| purpose | ENROLL 或 AUTHORIZE |
| expires_at | 过期时间 |
| used_at | 消费时间；0 表示未使用 |
| created_at | 创建时间 |

消费使用单条条件 UPDATE，只有未使用、未过期且 purpose 匹配时才能成功。

## `revocations`

用途：只保存需要拒绝的对象，避免建立全量设备表。

| 字段 | 说明 |
|---|---|
| revocation_id | 撤销记录 ID |
| target_type | DEVICE、DEVICE_PUBLIC_KEY、CERTIFICATE、APP_VERSION、PAYLOAD 等 |
| target_hash | 被撤销对象的 ID 或摘要 |
| reason | 撤销原因 |
| effective_at | 生效时间 |
| expires_at | 失效时间；0 表示永久 |
| created_at | 创建时间 |

当前运行时已强制检查 `DEVICE`；其他类型已预留表结构，接入时应在授权入口统一检查。

## `operation_logs`

用途：保存轻量关键审计，不保存完整请求、设备公钥、Integrity token 或任何 Key。

| 字段 | 说明 |
|---|---|
| id | 自增主键 |
| operation | PACK_CREATE、PACK_PUBLISH、PACK_REVOKE、UNPACK_DELIVERY 等 |
| result | SUCCESS 或失败结果 |
| request_id | HTTP 请求追踪 ID |
| detail | releaseId、revocationId 等非敏感简要信息 |
| created_at | 操作时间 |

建议定期清理超过 90～180 天的普通日志，重要管理审计可长期保留。

## 备份

因为启用了 WAL，不能只在运行中复制 `.db` 主文件。应采用以下任一方式：

1. 停止服务后复制 `.db`；
2. 使用 SQLite Online Backup API；
3. 执行 `VACUUM INTO` 生成一致性备份。

备份必须同时保护 `JIAGU_MASTER_KEY_B64`。只有数据库没有主密钥时，标准 Payload Key 无法恢复；只有主密钥没有数据库时，也没有 Payload 数据和授权配置。
