# 02. 数据库

服务端按公司维护独立 SQLite 文件。Schema 当前版本为 6。

## payload_releases

| 字段 | 说明 |
|---|---|
| release_id | Release 唯一标识 |
| payload_id / payload_version | Payload 逻辑标识和版本 |
| package_name / version_code | Android 应用版本唯一键 |
| certificate_sha256_digests_json | 允许的签名证书集合 |
| certificate_set_sha256 | 证书集合 canonical 摘要 |
| business_dex_sha256 | 最终业务 DEX canonical 摘要 |
| resources_sha256 | 最终资源摘要 |
| native_libs_sha256 | 最终 Native 摘要，不含 `liblog_ext.so` |
| release_build_sha256 | 上述构建组件的总摘要 |
| plaintext_sha256 | JG3 明文摘要 |
| local_ciphertext_sha256 | APK 内 JGLP 完整容器摘要 |
| local_payload_size | APK 内 JGLP 字节数 |
| payload_key_ciphertext | 公司 KEK 保护后的 32 字节 Payload Key |
| payload_key_version | DRAFT 内容变化时递增 |
| status | DRAFT / PUBLISHED / REVOKED |
| packer / delivery_count | 构建机器和 Key 下发计数 |
| draft_delivery_charged | DRAFT 是否已经消耗过公司下发额度 |
| created_at / updated_at / published_at / revoked_at | 生命周期时间 |

数据库明确不保存：

- JG3 明文；
- JGLP 密文；
- DEX 或 APK Payload BLOB；
- 每设备 Payload 文件。

## Schema 版本要求

服务端只接受已经升级完成的 Schema v6，不执行运行时结构迁移。Schema 不是 6，或者缺少本地 Payload/Key 字段时，数据库打开会明确失败。Payload Key 只支持 v3 封装域；历史 v1 Key 不再兼容。

## 其他表

- `company_info`：公司授权、打包/Key 下发额度和累计计数；
- `company_api_keys`：Company API Key 的 SHA-256 摘要；
- `challenges`：短期一次性 ENROLL/AUTHORIZE challenge；
- `revocations`：设备、Release 等撤销记录；
- `operation_logs`：构建、发布、撤销审计；
- `schema_meta` / `schema_descriptions`：结构版本和表说明。

## 运维检查

```sql
SELECT schema_version FROM schema_meta;

SELECT name
FROM pragma_table_info('payload_releases')
WHERE name LIKE '%payload%' OR name LIKE '%cipher%';
```

结果中不应出现 `canonical_payload`。`payload_key_ciphertext` 约为 60 字节（32 字节 Key 加 AES-GCM nonce/tag），而 `local_payload_size` 只是整数元数据。
