# 01. 总体架构

## 目标

服务端在不保存全量设备数据、不保存每设备 Payload 文件的前提下，实现：

- 按公司隔离授权和数据；
- 管理授权起止时间、打包次数、下发次数及限额；
- 标准 Payload 加密存储；
- 同一 `packageName + versionCode` 唯一 Release 的构建一致性锁；
- 锁定最终业务 DEX、资源/Manifest/assets、Native Library 和允许签名证书集合；
- 不同设备获得不同 Payload Key 和不同密文；
- 服务端响应签名、设备持有证明和防重放；
- 支持 Play Integrity、撤销和后续字段扩展。

## 部署结构

```text
Android / Gradle / 管理端
              │
              ▼
       Jiagu Go HTTP Server :8761
       ├── 公司管理模块
       ├── 打包发布模块
       ├── 设备凭证模块
       ├── Play Integrity 模块
       ├── 授权与撤销模块
       ├── Payload 重加密模块
       └── 审计与计数模块
              │
              ▼
       data/companies/
       ├── company-a.db
       ├── company-b.db
       └── company-c.db
```

服务是一个进程、一个部署单元。SQLite 驱动采用纯 Go 实现，不需要安装本机 SQLite 或启用 CGO。

## 数据隔离

`companyId` 只允许：

```text
[A-Za-z0-9][A-Za-z0-9_-]{1,63}
```

公司数据库路径固定为：

```text
{JIAGU_DATA_DIR}/{companyId}.db
```

不接受斜杠、点号或任意文件路径，因此不能利用公司标识跨目录访问文件。

服务没有全局公司数据库。查询公司列表时扫描数据目录内符合规则的 `.db` 文件，再读取各自的 `company_info`。

## 无设备存储设计

正常设备不写入长期设备表。首次注册成功后，服务端签发 `DEVICE_CREDENTIAL` JWS，其中包含：

- companyId；
- deviceId；
- 设备签名公钥；
- 设备 Key 封装公钥；
- packageName；
- 签名证书摘要；
- 签发和过期时间。

Credential 由客户端保存。后续服务端只验证 Credential 签名和设备请求签名，不查询设备记录。

一个 Release 可以配置多个允许签名证书摘要，用于同时支持 Play App Signing、受控侧载和证书轮换历史。Credential 只记录当前设备实际安装包使用的证书摘要；该摘要必须属于 Release 的允许集合。

只有需要撤销的设备才写入 `revocations`。因此长期存储规模与公司数、Payload 版本数和异常设备数有关，而不与正常设备总数线性相关。

## 一机一码

设备 Payload Key 通过服务端主密钥动态派生：

```text
HMAC-SHA256(
    masterKey,
    domain || companyId || deviceId || releaseId || payloadId ||
    payloadVersion || packageName || versionCode || actualCertificateDigest ||
    releaseBuildHash || payloadHash || payloadKeyVersion
)
```

派生结果不写数据库。不同设备、不同 Payload 或不同 Key 版本得到不同 Key。

加密 Payload 内置于 APK 的 `liblog_ext.so`，SQLite 只保存本地密文摘要和由公司 KEK 封装的 Release Payload Key。AUTHORIZE 时服务端将该 Key 用设备 RSA 公钥封装后返回，不接收、不保存、不转换 Payload。

## Release 构建一致性锁

同一公司数据库内，Release 唯一身份为：

```text
packageName + versionCode
```

一个版本只有一个 Release 和一个标准 Payload。DRAFT 可以保留 releaseId 原地修订；PUBLISHED 和 REVOKED 永不允许替换。正式版本发布后，同 package/version 的 Debug 构建若内容不同，将收到明确的 409，必须提升 versionCode 或使用不同 applicationId。

Release 构建摘要由三部分组成：

```text
releaseBuildSha256 = SHA-256(
    canonical(
        "JIAGU-RELEASE-BUILD-V1",
        businessDexSha256,
        resourcesSha256,
        nativeLibsSha256
    )
)
```

- `businessDexSha256`：D8 最终业务 DEX；
- `resourcesSha256`：最终 Manifest、resources.arsc、res 和 assets；
- `nativeLibsSha256`：所有 ABI 的应用、依赖和 Jiagu Runtime `.so`，排除包含 Release 配置的 `liblog_ext.so`。

哈希使用排序后的路径、长度和内容摘要，不依赖 ZIP/JAR 时间戳、压缩级别或 Entry 顺序。APK、ABI/资源 Split 和 AAB 聚合同一 Variant 的全部输入，使用同一个 Release。

该机制是构建期版本锁，不在设备启动时重新扫描 Play 生成的 Split APK。运行时继续通过 Play Integrity、签名证书、versionCode、Grant 和 Payload 加密完整性建立信任。

## 信任边界

| 边界 | 信任内容 | 不信任内容 |
|---|---|---|
| 管理接口 | 服务端管理员 Token | 普通客户端请求 |
| 打包接口 | 公司 API Key、公司授权状态 | 客户端提交的统计和权限字段 |
| 设备注册 | 服务端 challenge、设备签名、Play Integrity | 客户端自报设备 ID |
| Payload 授权 | 服务端签名 Credential、设备私钥持有证明 | Manifest 中的期望签名值 |
| Android 运行时 | Native 内置服务端公钥、Keystore 私钥 | 本地文件、系统时间、Java 层判断 |

JSON API 统一返回 `code/message/details`。`code` 是稳定机器码，`message` 仅供人阅读，`details` 始终为对象。协议不再包含二进制 Payload 上传或下载接口。

## 当前实现边界

- 单 Payload 默认最大 64 MB，可通过环境变量调整。
- 当前加密实现以单个 AES-GCM 消息处理完整 Payload，因此打包和下发时会占用约数倍 Payload 大小的瞬时内存。
- 如果后续 Payload 增长到数百 MB，应升级为分块容器和分块 AES-GCM，但不需要改变 API 和数据库主体。
- 客户端一旦在已授权设备内取得明文 Key，服务端无法让攻击者遗忘旧 Key；撤销主要阻止未来授权和新版本下发。
