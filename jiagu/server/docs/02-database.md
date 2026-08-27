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
transaction lock = IMMEDIATE
```

同一公司最多保留 4 个数据库连接，适合单实例、小规模并发。写事务从开始时取得保留锁，配合 `busy_timeout` 排队等待，避免并发请求从读事务升级为写事务时直接产生 `SQLITE_BUSY`。不要让多个服务实例同时通过网络共享盘写同一个 SQLite 文件。

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

当前设计版本：**5**。不定义运行时旧 Schema 自动迁移路径；现有 `acme` 数据库直接升级并回填为版本 5，全新数据库直接使用版本 5。

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
| pack_count | 首次成功创建唯一应用版本的累计次数 |
| delivery_count | 已消耗的公司下发额度；DRAFT 的同一 Release 最多计入一次 |
| status | ACTIVE、SUSPENDED、EXPIRED 或 REVOKED |
| ext_json | 公司级扩展 JSON，用于联系人、渠道、合同号等后续字段 |
| created_at | 创建时间 |
| updated_at | 更新时间 |

计数规则：

- `pack_count` 只在首次插入新的 `package_name + version_code` 且加密 Payload 成功写入时增加。
- DRAFT 更新、幂等重试、PUBLISHED 复用和失败请求不增加 `pack_count`。
- DRAFT 的同一 `release_id` 第一次成功生成设备 Payload 时增加公司 `delivery_count`，后续草稿更新和再次下发不重复消耗公司额度。
- PUBLISHED 每次成功生成设备 Payload 都增加公司 `delivery_count`。
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
| certificate_sha256_digests_json | 排序去重后的允许签名证书 SHA-256 Base64URL JSON 数组 |
| certificate_set_sha256 | 允许证书集合的 canonical SHA-256 摘要 |
| business_dex_sha256 | 最终业务 DEX 集合摘要 |
| resources_sha256 | 最终 Manifest/resources.arsc/res/assets 摘要 |
| native_libs_sha256 | 所有 ABI 最终 Native Library 集合摘要，不含 `liblog_ext.so` |
| release_build_sha256 | 三个构建组件摘要的 canonical 总摘要 |
| plaintext_sha256 | 原始 Payload SHA-256 Base64URL |
| canonical_ciphertext_sha256 | 标准密文 SHA-256 Base64URL |
| canonical_payload | AES-256-GCM 加密后的标准 Payload BLOB |
| canonical_key_ciphertext | 使用公司 KEK 加密后的随机标准 Payload Key |
| payload_key_version | 设备 Key 派生版本，用于轮换 |
| packer | 编译插件获取的本机机器名，最多 64 个字符；不属于 Payload 绑定字段 |
| delivery_count | 该 Release 实际成功生成设备 Payload 的累计次数 |
| draft_delivery_charged | DRAFT 是否已经消耗过一次公司下发额度，0 否、1 是 |
| status | DRAFT、PUBLISHED 或 REVOKED |
| created_at | 首次创建时间；DRAFT 更新时保持不变 |
| updated_at | 最近一次 DRAFT 更新或状态变更时间 |
| published_at | 发布时间 |
| revoked_at | 撤销时间 |

唯一约束：

```text
package_name + version_code
```

状态和更新规则：

- 首次创建为 DRAFT，`payload_key_version=1`；
- DRAFT 所有绑定字段相同时原样复用；
- DRAFT 任一构建摘要、Payload 或证书集合变化时保留 `release_id`，重新加密并令 `payload_key_version+1`；
- PUBLISHED 相同请求可以复用，任何绑定变化返回 409；
- REVOKED 永久禁止复用该 package/version；
- 读取、状态判断、KeyVersion 递增、Payload 更新和 `pack_count` 必须位于同一事务或等价 CAS 中。

`certificate_sha256_digests_json` 只用于保存和展示有序集合。所有密码学绑定使用 `certificate_set_sha256`，设备 Credential/Grant 另外记录本次实际安装证书摘要。

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

用途：只保存 Release 和公司的关键生命周期审计，不保存每次草稿更新或下发事件，也不保存完整请求、设备公钥、Integrity token 或任何 Key。

| 字段 | 说明 |
|---|---|
| id | 自增主键 |
| operation | 仅允许 PACK_CREATE、PACK_PUBLISH、PACK_REVOKE、COMPANY_REVOKE |
| result | 固定为 SUCCESS |
| request_id | HTTP 请求追踪 ID |
| detail | Release 操作保存 releaseId，公司撤销保存 companyId |
| packer | Release 操作发生时记录的最近打包机器名；公司撤销为空 |
| created_at | 操作时间 |

`PACK_UPDATE`、`UNPACK_DELIVERY` 和 `REVOCATION_CREATE` 不写入该表。下发次数直接累计在 `payload_releases.delivery_count`，避免按下发次数持续增长日志数据。

## 查询和索引策略

- `payload_releases` 的 `UNIQUE(package_name, version_code)` 同时服务版本复用查询和唯一性校验。
- `idx_payload_release_created(created_at DESC)` 服务 Release 列表和统计分页，避免排序临时表。
- Release ID、challenge ID 和 revocation ID 均由主键索引查询。
- `company_api_keys.key_hash` 的唯一索引服务 API Key 鉴权。
- `idx_challenge_expiry(expires_at)` 服务过期 challenge 清理。
- `idx_revocation_target(target_type, target_hash, effective_at)` 服务撤销检查。
- `idx_operation_created(created_at)` 服务生命周期审计的时间查询和清理。
- Release 列表只读取元数据，不加载 `canonical_payload` 和 `canonical_key_ciphertext` 大 BLOB。

## 备份

因为启用了 WAL，不能只在运行中复制 `.db` 主文件。应采用以下任一方式：

1. 停止服务后复制 `.db`；
2. 使用 SQLite Online Backup API；
3. 执行 `VACUUM INTO` 生成一致性备份。

备份必须同时保护 `JIAGU_MASTER_KEY_B64`。只有数据库没有主密钥时，标准 Payload Key 无法恢复；只有主密钥没有数据库时，也没有 Payload 数据和授权配置。
