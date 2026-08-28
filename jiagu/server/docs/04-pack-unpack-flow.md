# 04. 打包与解包流程

## 构建

1. 插件根据 Variant 的 `minifyEnabled` 选择 D8 或目标 App 自己配置的 R8。
2. 最终业务 DEX 被压缩成 JG3，插件计算业务 DEX、资源、Native、ReleaseBuild 和 JG3 明文摘要。
3. 插件调用 `/pack/releases`，只上传上述元数据和允许证书集合。
4. 服务端创建、更新或复用 Release，并返回 32 字节 Payload Key。
5. 插件使用 AES-256-GCM 在本地把 JG3 加密成 JGLP。
6. 插件调用 `/seal`，只提交 JGLP 摘要和大小。
7. 插件把 RuntimeConfig v3 和 JGLP 组成 JGRC，写入四个 ABI 的 `liblog_ext.so`。
8. 所有构建步骤成功后，按配置发布 Release。

若相同构建内容对应的 Release 已经 PUBLISHED，插件复用服务端返回的 Payload Key 和封存摘要并跳过重复 publish。服务端 publish 本身也支持幂等重试，但不会重复更新时间戳或写发布审计记录。

Payload Key 在服务端使用以下 KEK 和 AAD 保护：

```text
KEK = DeriveCompanyKey(masterKey, companyId, "payload-key-wrap-v3")
AAD = canonical("PAYLOAD-KEY-V3", companyId, releaseId)
```

服务端不接收 JG3/JGLP 字节，因而不能独立重新解析 DEX；构建摘要的真实性依赖经过 Company Key 认证的可信构建环境。构建 API 必须部署在 HTTPS 后。

## 首次设备注册

1. Runtime 从 `liblog_ext.so` 读取 JGRC，解析 RuntimeConfig 和本地 JGLP。
2. 校验 APK 的 packageName、versionCode 和实际签名证书。
3. 在 Android Keystore 中生成 ECDSA P-256 签名 Key 和 RSA-3072 wrapping Key。
4. 获取 ENROLL challenge，签署 `ENROLL-V2` canonical message。
5. `integrityMode=google` 时获取 Play Integrity Standard token。
6. 服务端验证 challenge、设备签名、Release、证书和可选 Integrity，返回 Ed25519 签名 Credential。

Credential 客户端缓存作用域为公司、包名和实际签名证书，不包含 Release。后续版本只要上述身份不变且 Credential 有效，就跳过 ENROLL，直接为新 Release 执行 AUTHORIZE。

## Key 授权与本地解密

1. 获取 AUTHORIZE challenge。
2. Runtime 用设备 ECDSA Key 签署绑定 Credential、deviceId、ReleaseBuild 和 KeyVersion 的消息。
3. 服务端验证 Credential、撤销、Release、设备签名和可选 Integrity。
4. 服务端从数据库解封 Release Payload Key，并使用设备 RSA 公钥进行 OAEP-SHA1 封装。
5. 服务端返回 Ed25519 签名 Grant 和 wrapped Key，同时增加 Key 下发计数。
6. Runtime 验证 Grant 对 Release、JGLP 摘要和 wrapped Key 的绑定。
7. Runtime 用 Android Keystore RSA 私钥解封 Payload Key。
8. Runtime 通过 ELF 映射对应的 `DirectByteBuffer` 校验 JGLP 完整容器摘要，使用 `LOCAL-PAYLOAD-V3` AAD 做 AES-GCM 解密，明文直接写入直接内存。
9. Runtime 校验 JG3 明文摘要，Native 直接解析该 Buffer，并只在内存中解压和加载业务 DEX。

有效 Grant 与 wrapped Key 缓存在私有 SharedPreferences。默认 Grant 为 7 天，因此普通启动可以直接解封本地 JGLP，不需要网络；Grant 过期、Release/KeyVersion 变化或缓存失效时才重新 AUTHORIZE。

## 失败处理

- JGRC/JGLP Header、长度或摘要错误：拒绝启动业务代码；
- AES-GCM tag 或 JG3 明文摘要错误：清除明文并拒绝加载；
- 未 seal Release：服务端返回 `LOCAL_PAYLOAD_NOT_SEALED`；
- Release/设备撤销：拒绝新的 AUTHORIZE；
- `/unpack/download`：接口不存在并返回 404。
