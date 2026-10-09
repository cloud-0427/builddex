# 本地公开 API 加载 MVP 实现与验证

日期：2026-10-08。对应 [详细设计](10-local-public-api-loading-design.md) 与 [接入配置](09-dexreport-configuration.md)。当前交付本地 APK 路径；下文区分已经验证和仍待覆盖的范围。

## 已实现

- 新增 `jiagu-local-runtime` 轻量运行库，没有 Jiagu JNI、网络取钥、授权、Integrity、Tink、Conscrypt、OkHttp 或联网权限。旧运行库和在线任务源码保留在仓库作历史实现，不进入当前 MVP 的依赖与任务图。
- Manifest 保留真实 Application，使用平台或 AndroidX 公开 AppComponentFactory，在组件创建前读取 APK 内 assets、校验并通过 InMemoryDexClassLoader 返回应用 ClassLoader。由系统执行 Application attach、Provider 初始化和 onCreate，无 ProxyApplication 或内部字段注入。
- 新 JGLA v1 容器携带严格解析的配置和 AES-256-GCM 密文。每次加密实际执行生成随机密钥/nonce，配置作为 AAD 认证；解密后校验摘要、JG4 DEX 表和边界。明文业务 DEX 只进入 DirectBuffer，不在设备写盘。
- 构建分流采用显式 allowlist。Runtime-only R8 保留，业务与第三方不作为该 R8 program。ASM 检查 Shell→Payload 静态依赖、非 public 跨 ClassLoader 访问和上传器入口，最终 APK 校验壳/业务重复类、SPI 与密文 asset。
- 新 LocalManifestTask、CreateLocalPayloadTask 使用 AGP artifacts 与 generated assets 接入。普通 assemble 自动校验产物；MVP 显式拒绝 bundle。插件使用宿主 AGP 的 R8/D8，发布 POM 不再带入旧 R8 覆盖宿主工具链。
- 可选事件默认关闭。开启时注册独立 Provider 和 public ActivityLifecycleCallbacks，事件进入 64 条内存队列，由 Jiagu-LocalEvents 单线程异步投递。失败隔离、最多三次尝试、1/5 秒延迟、稳定 eventId；不持久化、不跨进程汇聚。
- 上传器由应用实现 LocalEventUploader，示例使用 HttpsURLConnection。上传协议与超时由适配器负责；不接入旧授权或密钥接口。

## 已执行验证

| 项目 | 结果与范围 |
| --- | --- |
| 插件 JVM 测试 | 30 项通过，涵盖容器生产编码器/解码器互通、多 DEX、随机加密、认证损坏、包名校验、长度/重叠越界、类边界、SPI 与 Runtime R8 |
| 同仓示例 APK | Debug / Release 完成构建，最终 APK 自动校验通过；最新 Release 在 API 34 真机再次通过 JNI/SPI/协程 Instrumentation |
| 独立 Maven 消费工程 | 使用生成的 JAR/AAR/POM 在临时仓库接入，无 composite/project 依赖、无 AndroidX，构建及 configuration cache 复用通过 |
| 配置负向验证 | online、minSdk 28、保留 asset 冲突均被明确拒绝 |
| API 29 模拟器 | 最新 Release 的真实 Application/Activity、JNI、SPI 和协程验证通过；无 AndroidX 独立工程冷启动通过 |
| API 34 Samsung 真机 | 普通 APK 启动；Instrumentation 验证真实 Application/Activity 与 Payload ClassLoader 身份，JNI、SPI 和协程调用通过 |
| API 36 / 16 KB 模拟器 | PAGE_SIZE=16384；最终工具链构建的事件版再次通过 Application/Activity、JNI、SPI 和协程验证 |
| 事件故障隔离 | debug 专用 LocalProbeUploader 注入永久拒绝与一次临时超时，后续启动事件继续；同 eventId 延迟重试成功；上传发生在后台线程 |

设备事件测试使用无网络的 probe，不向真实服务端发送事件。示例已按旧 userEvent 请求格式适配，详见 [事件协议兼容说明](12-local-event-service-compatibility.md)；真实服务端接收及新阶段编号支持尚未验证。FIRST_ACTIVITY_RESUMED 不代表首帧。

构建验证使用 Gradle 9.5.0、AGP 9.3.1、JDK 21；示例本身有业务 JNI，使用 NDK 28.2.13676358。AndroidX 测试范围为示例实际依赖版本，不扩大到任意自定义工厂。

## 当前边界与待验证项

1. minSdk 门槛为 29，本次设备实测覆盖 29/34/36；设计中的完整 API/ABI/OEM/低内存/更新恢复矩阵未完成。
2. AAB、dynamic feature、isolated splits、自定义 ClassLoader/sharedUserId 和任意自定义组件工厂不在 MVP 支持范围。普通 JNI 已验证，不表示所有第三方 linker 或 split native 组合均可用。
3. 静态边界分析不能证明所有反射、JNI、动态类名和运行时生成代码安全。应用自身的这些调用必须使用正确 ClassLoader 并单独验证；不提供双向注入或 hidden API 回退。
4. 事件为内存 best effort。进程退出、队列溢出、构造器异常、永久失败或重试耗尽会丢事件；解密前致命失败可能来不及上传。上传器必须自行限制单次 10 秒并由服务端按 eventId 去重。
5. 冷启动 P50/P95、旧 local 对比和内存峰值的完整性能报告尚未完成；单次设备启动不能替代性能验收。
6. 本地密钥随 APK 分发，可逆向恢复；AES-GCM 不等同于发布者身份校验、在线授权或防重签名。
7. 未上传 Maven Central/JitPack、未提交或推送代码。正式发布仍执行项目原有签名流程。

## 复现入口

```powershell
.\gradlew.bat :dex-report-plugin:test :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest '-Pjiagu.telemetry=true' '-Pjiagu.uploader=lows.dgeon.ightr.jiagu.LocalProbeUploader'
adb -s <serial> shell am instrument -w lows.dgeon.ightr.jiagu.test/lows.dgeon.ightr.jiagu.LocalMvpInstrumentation
```

测试 APK 与目标 APK 需要分别安装。debug probe 仅在 debug source set，不进入 Release。后续扩展前先补性能验收和完整设备矩阵，并根据业务真实组件/反射/native 使用补回归样例。
## 2026-10-09 三项差异修正

- 成员边界检查沿 Shell 与 Payload 合并的类索引解析真实声明，覆盖 symbolic owner 位于 Payload 的继承成员；protected 实例成员通过 ASM 数据流分析校验接收者，静态成员与构造器分别按访问约束处理。不能确定合法接收者的情况在构建期拒绝，不加载用户类到 Gradle JVM。
- Manifest 对显式 android:backupAgent 和 android:cantSaveState=true 添加 LOCAL_SPECIAL_ENTRY_UNSUPPORTED 门禁，诊断属性及规范化类名。保留普通 allowBackup、directBootAware 等声明；不添加隐藏 API 回退。
- 读取与外层配置校验完成后才上报 LOCAL_PAYLOAD_READ 成功；GCM/摘要与 DEX 解析独立计时、独立终态。认证失败使用 LOCAL_PAYLOAD_AUTH_FAILED 并保留原 cause。ClassLoader 和 Application 创建失败分别归入自身阶段；不报告 attach/onCreate 完成。
- 解码器的事件观察器采用故障隔离，采集异常不能改变成功结果或替换原始异常。进程内失败状态仍禁止返回原 ClassLoader 继续启动。

这些修改仅针对上述三项，不表示完整设计验收或其它审计差距已关闭。
验证结果：插件 JVM 测试共 42 项通过，新增 12 项回归；Debug/Release 构建及最终 APK 校验通过；API 29 模拟器再次通过真实 Application/Activity、JNI、SPI 和协程 Instrumentation。新增错误阶段归属与观察器故障隔离由生产解码器的 JVM 测试验证。
