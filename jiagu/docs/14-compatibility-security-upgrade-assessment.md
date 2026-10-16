# 加固插件与本地环境升级评估

评估日期：2026-10-12。范围：当前工作区源码、构建配置、实际工具链与只读设备信息；主线为 0.1.25 local。本文是升级建议，不代表已实现对应能力。

## 结论

继续沿用 AppComponentFactory + InMemoryDexClassLoader 的公开 API 架构。下一步优先补环境安全更新、发布门禁、分包/设备验收及性能预算，再开发业务混淆与本地完整性校验。不要为了覆盖 API 28 以下重新引入内部字段注入。

当前 local 加固提供加密存储和内存加载，密钥可以从 APK 恢复；不提供不可提取、可信授权或不可重签名保证。旧 jiagu-runtime 与 server 源码保留，但没有进入当前 settings 的运行库模块及主插件任务图，不能把旧安全功能算作现有 local 能力。

## 实测环境

| 项目 | 当前值 | 建议 |
| --- | --- | --- |
| 系统 | Windows 10 Pro，19045 | 核对补丁与支持状态；建设独立、可复现 CI 构建机 |
| 构建 JDK | OpenJDK 21+35，2023-09-19 初始发行版 | 首先换为维护中的 JDK 21 安全更新，保留大版本，固定发行商/完整版本/校验值 |
| Gradle / AGP | 9.5.0 / 9.3.1 | 保留为当前基线，Wrapper 已有 SHA-256；升级前运行成对兼容矩阵 |
| Android SDK | compile/target 36，另装 33、35、36.1、37.0 | 37 作为独立兼容验证，安装不等于通过；正式构建固定 SDK 和 Build Tools |
| native | NDK 28.2.13676358；CMake 配置 3.22.1 | 保留基线，逐个检查所有第三方 SO 的 ELF 与 APK 对齐 |
| 连接设备 | Samsung SM-G990U2，API 34，4096 页，安全补丁 2024-09-01 | 可做兼容样本；不能代表新补丁、API 36 或 16 KB 设备 |
| Go | go1.26.2 windows/amd64 | 不在 local 启动链路，在线分支重新启用时单独评估 |

AGP 9.3 的官方 Gradle 基线为 9.5.0，当前配对合理。Java 源码目标 11 不意味着构建 JVM 可降到 11。插件编译使用 AGP API 8.12.2；注释中的跨 9.x 兼容声明应通过消费工程矩阵证明。

## 已有优势与主要缺口

1. **公开加载 API**：保留真实 Application，由系统执行生命周期；minSdk 29，拒绝 dynamic feature 等不支持组合。降低系统内部实现变更风险，但仍需覆盖 OEM、进程与 native 加载差异。
2. **容器校验**：AES-256-GCM，随机密钥/nonce，配置纳入 AAD，包名、长度、摘要、DEX 表校验，失败禁止回退。两个密钥片段都在同一配置中，通过 XOR 恢复密钥，攻击者可重新生成合法容器；不能视为发布者认证。
3. **类加载边界**：已有 ASM 静态引用、成员访问、重复类与 SPI 校验。反射字符串、JNI FindClass、序列化、动态生成代码仍需业务回归。AndroidX 工厂有方法级拒绝检查，但尚不足以声明任意版本/继承链兼容。
4. **业务优化缺失**：LocalModePlugin 会把选中变体的 minifyEnabled 设为 false，业务只做 D8；Runtime-only R8 不等于业务混淆。应显式报告最终策略，防止宿主误以为全局 R8 仍生效。
5. **发布风险**：示例 Release 显式使用 signingConfigs.debug。演示可以保留，正式发布任务应拒绝 debug 证书、debuggable 与不合格产物；支持 Play App Signing 时区分上传证书和安装证书。
6. **内存风险**：128 MiB 是明文长度上限，不是进程峰值预算。ZIP 读取使用 ByteArrayOutputStream + toByteArray，随后还分配 DirectBuffer；密码提供者与 ART 也可能分配额外内存。应按真实大 Payload 和低内存设备测量，不能声称端到端零拷贝。
7. **明文边界**：设备没有主动写出明文 DEX，但构建端存在 payload.jg4、业务输入及审计文件。限制构建机、工作区、日志和 CI artifact 的访问与保留期；禁止上传敏感中间产物到共享缓存。
8. **事件配置**：运行库默认关闭事件，但仓库 gradle.properties 将其开启。示例上传器固定真实 URL、业务 appId/bundle/channel，并尝试读取 OAID；应区分演示与生产配置，按实际应用注入字段，默认关闭标识符采集，发布日志不输出完整事件。此次没有发送事件。
9. **供应链**：现有 Wrapper 哈希与发布签名是基础。未发现依赖锁文件/Gradle verification metadata；增加锁定、校验、依赖清单与漏洞审查。未执行全面 CVE/秘密扫描，不能据此宣布依赖无漏洞。
10. **验证范围**：已有记录覆盖 API 29/34/36，但文档明确 AAB splits 安装、完整设备矩阵和性能验收尚未完成。本次 JVM 与打包检查不能替代这些设备测试。

## 分阶段方案与验收

### P0：发布基线，建议 3–5 个工作日

- 更新 JDK 21 安全补丁；固定构建 JDK、AGP、Gradle、SDK、NDK、CMake，记录版本及哈希。
- 建立正式发布签名门禁；证书、debuggable、壳/业务重复类、容器、SPI、ABI/SO 对齐均纳入最终产物报告。
- 将事件端点和产品字段改为显式配置，开发默认禁用；移除默认设备标识符采集及 Release 完整事件日志。
- 明确不支持的 DSL：antiDebug、signatureCheck、compression、publish 等字段在 local 中不能悄悄被忽略；设置了未支持功能时失败并给出原因。
- 固定依赖与校验元数据，隔离旧 runtime/server 的发布入口。
- 本次构建提示存在 Gradle 10 不兼容的弃用功能；使用 `--warning-mode all` 归因并清理，再安排 Gradle 大版本升级。

验收：使用更新后的 JDK 完成插件测试、独立 Maven 消费工程、APK/AAB 与 configuration cache 复用；不合格签名和配置产生明确构建失败；默认启动不发事件。

### P1：兼容性与性能，建议 1–2 周

- 设备主矩阵：API 29、34、35、36；37 独立候选；arm64 为主，armeabi-v7a/x86_64 按实际分发覆盖；至少 AOSP 与两个实际目标 OEM。
- 使用 bundletool 从 AAB 生成并安装实际 splits，验证 JNI 在 ABI split 内、语言/密度切换、覆盖安装、进程重启。普通 APK、universal APK 和 splits 分别验收。
- 覆盖 Application、Activity、Service、Receiver、Provider、多进程、Direct Boot、JNI、SPI、协程、反射和序列化；不支持组合保持构建期拒绝。
- 同一业务生成未加固与加固 Release，固定设备条件，每组至少 30 次冷启动，测首帧/可交互时间 P50/P95、解密时间、Java/native/PSS 峰值；FIRST_ACTIVITY_RESUMED 不能代替首帧。
- 增加多 DEX、大 Payload、低内存和损坏容器压力样本。当前示例本次 Payload 只有约 3.46 KiB，不能代表真实业务成本。

建议初始性能门槛：P95 增量不超过 100 ms 且不超过 10%，内存增量在项目约定预算内，无 ANR/OOM。此为讨论用目标，需按业务基线调整。

验收：矩阵中每个支持组合有设备/构建日志；16 KB 同时通过 ELF、ZIP 对齐和实际启动；公开支持清单与拒绝清单。

### P2：保护能力，建议 2–4 周

- 优先设计业务 R8：业务作为 program，壳/API 作为 library，明确跨边界 keep、反射/JNI/SPI/序列化规则与 mapping 归档；保持宿主 R8/D8 版本一致。业务 DEX 完成后再加密。
- 如业务要求检测重打包，新增独立的证书白名单与容器数字签名设计，覆盖 APK 签名轮换、Play App Signing、多签名者、版本/包名绑定。可设计 JGLA v2 与明确旧容器兼容策略。
- 在发布工具中将新增开关默认关闭；只有通过专项验收后启用，不把强制失败策略直接推广到所有消费者。
- 根据 P1 测量优化读取复制与内存预算；保留失败时清理，避免以关闭认证换性能。

验收：错误证书、版本不一致、替换/损坏容器等负向样本被拒绝；签名轮换与官方分发正常启动；业务混淆后反射/JNI/SPI 仍通过。客户端签名校验可被补丁绕过，不承诺不可破解；可信付费/授权判断另需服务端方案。

## 本次验证与限制

- 执行命令：`./gradlew.bat :dex-report-plugin:test :jiagu-local-runtime:testDebugUnitTest :app:assembleRelease :app:bundleRelease --console=plain`。
- 插件 45 项 JVM 测试、本地运行库 1 项单元测试通过；Release APK/AAB 构建成功，最终容器、SPI 与壳/业务 DEX 自动校验通过。构建耗时 2 分 3 秒，部分任务复用已有输出；configuration cache 已存储，本次未重跑验证复用。
- 设备仅做只读属性查询，没有安装/启动应用，没有调用事件服务端；历史设备记录与本次实测分别表述。
- 没有修改业务实现、升级工具链或发布版本。P0/P1/P2 工期是估计，不是完成承诺。

参考：[AGP 9.3 官方兼容说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[AppComponentFactory](https://developer.android.com/reference/android/app/AppComponentFactory)、[InMemoryDexClassLoader](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)、[16 KB 官方验证流程](https://developer.android.com/guide/practices/page-sizes)。
