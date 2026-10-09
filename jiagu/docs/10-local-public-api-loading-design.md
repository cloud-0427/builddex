# 本地加密模式公开 API 加载改造设计

状态：本地 MVP 已落地；已验证范围与尚未完成的设备矩阵见 [实现记录](11-local-mvp-implementation.md)。  
日期：2026-10-08  
范围：本地加密与公开 API 加载的 MVP，包含可选异步事件上报。在线加固、网络取钥及授权流程移出本次实现范围；本文修改不代表已经删除仓库中的在线代码。

## 1. 目标与设计决定

在保留业务 DEX 加密存储、离线启动、内存解密和加载能力的前提下，移除本地模式对 Android 内部类成员及 Application 替换的依赖。

采用以下目标架构：

1. 最低 API 为 29，使用公开 `AppComponentFactory.instantiateClassLoader()`。
2. 本地加密容器放入 base APK 的固定 assets 条目，通过 `ApplicationInfo.sourceDir` 和 `ZipFile` 读取，不需要 Context。
3. 使用平台 JCA AES-256-GCM 解密，经严格校验后通过 `InMemoryDexClassLoader` 加载。
4. 系统直接创建真实 Application，系统负责 attach、Provider 初始化和 onCreate。
5. 保留业务白名单分流与 Runtime-only R8，不将全部第三方依赖默认移入 Payload。
6. 增加 Shell→Payload 依赖检查。不支持的类加载组合在构建阶段失败，不靠 hidden API 补救。
7. 本地运行库独立为无 JNI、无 KeyStore 的轻量 AAR，事件采集与异步投递独立于解密链路。
8. MVP 只实现本地加密，不接入在线 Runtime、服务端 Release、网络取钥、设备授权或 Play Integrity。网络只用于可选事件上传；上传结果不能改变密钥、Payload 或加载决策。

这不是把 JNI 反射改写成 Java 反射，也不是在失败时回退到旧注入路径。目标本地 APK 必须不包含 Jiagu 内部字段访问代码。

### 1.1 保留的保护能力

- 被选择的业务 class 仅以加密 DEX 形式进入最终 APK。
- 每次实际执行加密任务使用新的随机密钥和 nonce。
- AES-GCM tag 与明文摘要校验通过后才创建 ClassLoader。
- 不将明文 DEX 写入文件、缓存、SharedPreferences 或日志。
- 多 DEX、业务资源、正常 Android 组件生命周期继续作为目标能力。
- 本地构建及首次启动均不需要 Jiagu 服务端。
- 事件通过有界队列异步投递；无网、上报超时和上传器故障不影响应用启动。

本地解密材料随 APK 分发，仍可被逆向恢复；本方案不提供在线授权、不可重签名或不可提取的安全保证。AES-GCM 认证不等于 APK 发布者身份校验。

### 1.2 首阶段边界

- 只支持 API 29 及以上；拒绝低于 29 的变体，不通过 manifest overrideLibrary 放宽。
- 只支持显式 `payloadSelectionMode=allowlist`；新加载路径拒绝 legacy 分流。
- MVP 支持普通 base APK；后续目标支持无动态 feature 的 AAB 生成的常规 ABI/语言/密度 splits。
- 首阶段拒绝 dynamic feature、isolated split loading、自定义 `android:classLoader` 和 sharedUserId 组合。
- 组件工厂支持无工厂、平台默认工厂，以及完成专项验证的 AndroidX CoreComponentFactory。其它自定义工厂先明确拒绝。
- Instrumentation 使用单独的验证路径；不能据此承诺所有测试框架的自定义类加载机制兼容。
- 不增加业务 R8、不实现在线加固/授权、不重命名业务资源、不提供热更新。
- MVP 默认支持普通 base APK；常规 AAB splits 作为后续扩展验证项，不作为 MVP 完成条件。第 7 节是扩展兼容设计，不要求首阶段覆盖全部组合。
- MVP 事件队列只保存在进程内，暂不引入 SQLite、跨进程队列、持久化补传或独立事件服务端开发。

## 2. 当前实现与需要替换的环节

| 环节 | 当前代码行为 | 新本地模式 |
| --- | --- | --- |
| Manifest | `ManifestTransformerTask` 改为 ProxyApplication，追加 REAL_APPLICATION | 保留原 Application，注入本地组件工厂 |
| 密文载体 | `JiaguReleaseTask.createLocalPayload()` 生成 JGRC/JGLP，链接进四个 ABI 的 liblog_ext.so | 生成单份版本化 assets 容器，无 ELF 链接 |
| 启动入口 | ProxyApplication.attachBaseContext → nativeAttach | 工厂 instantiateClassLoader，在创建组件前完成加载 |
| DEX 可见性 | JNI 修改系统 dexElements | Payload ClassLoader 作为系统使用的应用 ClassLoader |
| Application | 手动构造、修改 ActivityThread/LoadedApk、调用内部 attach | 系统通过工厂实例化真实 Application |
| 生命周期 | nativeOnCreate 手动调用并清除部分异常 | 不转发生命周期，异常按系统原行为传播 |
| 依赖 | 当前 Runtime 无条件依赖网络、Integrity、Tink、Conscrypt | 本地 AAR 只用公开 Android/Java API；事件 HTTP 实现由应用侧提供 |
| 构建身份 | 本地仍求值签名证书、注册发布 Flow | 本地不求值签名配置，不注册服务器发布行为 |
| 事件 | 当前 local 清空上传器配置，旧 Reporter 与 native 阶段绑定 | 独立本地事件模型、有界内存队列、后台上传器 |

现有 `07` 文档包含 API 23 目标和部分未实现 DSL。本文新本地加载路径以 API 29 为唯一最低版本，不继承那些预留开关。现有 `08` 文档中 Provider 强制留 Shell 的策略也不能直接照搬，具体调整见第 5 节。

## 3. 运行时架构

```text
系统原始 APK ClassLoader L0
  ├─ 本地 Jiagu bootstrap / 工厂 / 容器解析与密码实现
  ├─ 第三方 SDK 与其它 Shell 类
  └─ APK Java 资源、SPI 描述文件
                 ↑ parent delegation
InMemoryDexClassLoader L1
  └─ 已解密业务 DEX（Application、业务组件与业务实现）

系统应用 ClassLoader = L1
真实 Application / Activity / Provider 由系统与工厂使用 L1 创建
```

L1 使用标准 parent-first 行为，不实现互相回调的双向 ClassLoader，不修改 L0。Payload→Shell 的公开 ABI 引用可工作；Shell 自己解析类时仍使用其定义加载器 L0，不能因为系统选择了 L1 就自动看到业务类。

类身份由描述符和定义加载器共同决定。同名类只能在一个 lane 中定义；不在 Shell 中生成业务类的空壳，不在两个 lane 中复制共享接口，也不采用 child-first 覆盖已有 SDK 类。

### 3.1 公开接口及职责（拟新增）

| 类/方法 | 职责 | 输入限制 |
| --- | --- | --- |
| `JiaguLocalComponentFactory.instantiateClassLoader(cl, appInfo)` | 唯一本地加载入口，返回 L1 | 无 Context，无网络，无 Application 对象 |
| `LocalPayloadReader.read(appInfo)` | 从 base APK 读取指定 ZIP 条目 | 只读 sourceDir；不扫描外部存储 |
| `LocalPayloadCrypto.decrypt(config, ciphertext)` | GCM 解密与摘要验证 | 不再接收被忽略的 Context；不要求 ELF DirectBuffer |
| `LocalDexContainer.parse(buffer, limits)` | 校验 DEX 表、边界和总内存预算 | 默认仅接受未压缩 JG4 |
| `LocalLoaderRegistry.getOrCreate(cl, appInfo)` | 每进程、每 APK 身份一次加载，保留 buffers | 缓存键含原始 loader 身份和 APK 路径 |
| `LocalNativeLibraryPaths.resolve(appInfo)` | 构造业务 native 库搜索路径 | 使用公开信息，禁止读取 pathList/nativeLibraryDirectories |
| `LocalEventReporter.emit(event)` | 捕获 bootstrap 阶段事件并进入内存队列 | 无 Context、无 IO；采集故障不能替换原始加载结果 |
| `LocalEventInitializer` | 以普通 Provider 获取已 attach 的 Application Context，启动异步投递 | 不代理 Application，不同步调用上传器 |
| `LocalEventUploader.upload(context, event)` | 应用提供的后台事件上传接口 | 独立于在线授权；不得访问 Payload 密钥 |

接口名为设计建议，实施时可按现有命名调整；职责和约束不能通过命名调整而省略。

### 3.2 启动时序

1. 系统通过 L0 加载、构造本地组件工厂。工厂构造器及静态初始化不得解密、调用 SDK 或创建 Context。
2. 系统调用 instantiateClassLoader，传入 L0 与 ApplicationInfo。
3. Registry 检查是否已加载同一 APK；命中返回同一 L1。
4. Reader 打开 sourceDir，读取唯一固定条目，关闭 ZipFile 和流。
5. 解析外层版本和长度，恢复本地密钥，执行 GCM 认证及解密。
6. 校验明文摘要、JG4 表和 DEX，生成各 DEX 的 ByteBuffer slice。
7. 构造 `InMemoryDexClassLoader(buffers, nativeSearchPath, L0)`，保存强引用并返回 L1。
8. 系统后续使用 L1 创建真实 Application，正常 attach；Provider 初始化和 onCreate 由系统执行。
9. 后续组件实例化继续走工厂，传入的 loader 按验证结果使用 L1。
10. 事件 Initializer Provider 在 Application attach 后初始化队列消费者；有网时后台投递早期事件。它不代表 Application.onCreate 已完成，不等待网络结果。

不在第 2～7 步创建 Application，也不手动调用 attach/onCreate，不写 ActivityThread，不把加载成功等同于业务启动成功。

### 3.3 工厂实例重建与状态

不能假设系统整个进程只构造一个工厂实例。状态保存在由 L0 定义的 Registry 中，不保存在某个工厂实例字段里；Payload 不包含 bootstrap 的第二份定义。

Registry 使用同步状态机 `NEW → LOADING → READY / FAILED`。重入加载明确失败，不等待自己；并发调用不重复解密；FAILED 不回退 L0，也不偷偷重试旧路径。保存稳定错误及原始 cause。每个进程独立加载，不建立跨进程锁或持久化明文缓存。

## 4. Manifest 与原有工厂

### 4.1 本地变换

- 保留、规范化原 `android:name`。没有自定义 Application 时保留系统默认语义，不写 ProxyApplication。
- 将 `android:appComponentFactory` 设置为本地 Jiagu 工厂。
- 不追加 REAL_APPLICATION、ENABLE_ANTI_DEBUG、ENABLE_SIGNATURE_CHECK、EXPECTED_SIGNATURE 或在线 uploader metadata。
- 保留 Provider、Initializer、backupAgent、进程名、directBootAware、extractNativeLibs 等业务声明。
- 不修改业务库需要的 INTERNET 权限；本地 AAR 自身不声明网络权限。
- 启用事件上报时注入专用 LocalEventInitializer Provider 和上传器配置；Provider 固定留 Shell，authority 使用 applicationId 加固定后缀且 exported=false。事件功能关闭时不注入该 Provider。应用上传器需要联网时由应用显式声明 INTERNET。
- 变换必须幂等。输入出现旧 ProxyApplication 或 Jiagu 工厂的二次嵌套时明确诊断，不能层层包装。

### 4.2 工厂兼容策略

无工厂/平台默认工厂：使用 AppComponentFactory 的标准组件创建实现。

AndroidX CoreComponentFactory：生成一个继承该原工厂的专用 Jiagu 本地工厂，仅覆盖 instantiateClassLoader，继承其 Application、Activity、Service、Receiver、Provider 的包装逻辑。该生成类及原工厂的 bootstrap 依赖必须在 Shell。无 AndroidX 的应用不应被强制添加 AndroidX。

构建时检测原工厂及其继承链，验证支持的 AndroidX 实现没有不兼容的 ClassLoader 改写。将验证过的类与依赖版本记录到报告中，不只按类名放行任何实现。

自定义工厂：首阶段失败并指出原类名。不默认反射委托，也不忽略原工厂。后续扩展必须验证其 ClassLoader 返回行为、五类组件创建行为、构造器依赖和重入风险，另写适配器及测试后才能进入支持清单。

## 5. 分流与类加载边界

### 5.1 分流优先级

1. Jiagu bootstrap、生成工厂、事件模型/Reporter/Initializer/上传器及必需共享 ABI 固定留 Shell。
2. 用户 shellKeepClasses/shellKeepPackages 显式留 Shell。
3. 用户 payloadIncludePackages 命中的业务 class 进入 Payload。
4. 其它 class 默认留 Shell，非 class 资源走原 AGP 打包。

Provider、Application、Initializer 不再仅因 Manifest 中出现就强制留 Shell：系统在组件创建前已获得 L1，它们可进入 Payload。工厂本身及其创建前必需的类仍须在 Shell。backupAgent 和特殊系统入口需要专项验证，未验证的组合明确拒绝。

### 5.2 必须新增的边界检查

在生成 DEX 前分析实际 Shell/Payload class 集合；生成 DEX 后再次检查 lane 间描述符及 synthetic。报告记录引用来源、目标、成员、类别和建议处理方式。

| 引用类型 | 默认处理 |
| --- | --- |
| Payload→Shell 公开类、公开成员 | 允许；Runtime R8 生成 ABI keep 规则 |
| Shell→Payload 的父类、接口、字段/方法类型、字节码类/成员引用 | 构建失败，不能只警告 |
| Payload↔Shell 跨运行时包的 package-private 访问 | 失败；同包名不同 loader 仍不是同一运行时包 |
| 不符合 Java 访问规则的 protected/嵌套类/nestmate 跨 lane 访问 | 失败，或通过实际 D8/R8 转换后的专项验证证明合法 |
| Shell 中静态 initializer 提前解析业务类 | 失败，不允许通过提前解密后注入 L0 解决 |
| 反射字符串、JNI FindClass、动态资源名 | 静态检查不能证明完整；列入人工审计与设备测试 |

发生冲突后可将相关业务类明确移回 Shell，或扩大业务 Payload 白名单，使引用方与目标位于同一 lane。插件不静默把大量第三方迁入 Payload，也不静默降低业务保护范围。

共享回调接口定义在 Shell，业务实现位于 Payload，SDK 通过接口调用业务对象是合法方向；SDK 使用自己的类加载器按字符串寻找业务实现则不自动兼容。

### 5.3 SPI、反射与资源

- 继续合并 META-INF/services，并在最终 APK 校验描述文件及 provider 类存在。
- Payload provider 必须通过 L1（显式传入或已经验证的线程 Context ClassLoader）解析；SDK 显式使用 L0 的 SPI 路径不支持。
- 不把修改当前线程 Context ClassLoader 当作全局兼容方案；它不改变其它类的定义加载器，也不覆盖所有线程。
- APK Java 资源通过 parent delegation 获取；Android res/assets 按原 AGP 资源链处理。
- 自定义线程、序列化、WebView 桥、反射注册及布局中的自定义 View 均需真实调用路径验证。

## 6. 本地容器与密码契约

### 6.1 存储选择

固定路径建议 `assets/jiagu/local-payload.jgl`。只放在 base 模块，由 generated assets source 接入 AGP；不在每个 ABI split 中复制，不依赖 nativeLibraryDir 读取密文。

读取使用 ZipFile.getEntry，不提取到磁盘；拒绝同名重复 ZIP 条目、目录条目、不支持的压缩方法及缺失条目。按流实际字节数执行硬上限，不能信任 ZipEntry.size 或对 -1 做无界读取。可选 STORE 以降低重复压缩开销，但读取器必须支持 AGP 常规 DEFLATE，不能假设有固定 ZIP offset。

### 6.2 新格式（拟定 JGLA v1）

不让旧 JGRC/JGLP reader 模糊兼容新载体。外层格式采用固定二进制头与 UTF-8 配置块；所有整数 big-endian，长度为非负、受限 uint32，用 long 进行加法与范围检查。

```text
magic[4] = JGLA
formatVersion u32 = 1
configLength u32
ciphertextLength u32  // 包含 GCM tag
config[configLength]
ciphertext[ciphertextLength]
```

config 严格包含：mode=local、cipherAlgorithm=AES-256-GCM、AAD 版本、构建绑定 packageName/versionCode、明文总长度、明文 SHA-256、keyPartA、keyPartB、nonce，以及原工厂适配类型。keyPartA/keyPartB 各 32 字节；nonce 12 字节；tag 128 bit。Base64URL 编码规则固定且严格解析。config 上限 64 KiB，拒绝重复关键字段、未知必需版本、不合法类型和尾随字节。

key = keyPartA XOR keyPartB。两片都随 APK 分发，只用于延续本地分片方案，不宣称其产生额外密码学安全等级。不引入“隐藏密钥存放位置”或另一个 native bootstrap。

AAD 使用固定域分隔符 `JIAGU-LOCAL-ASSET-V1` 加配置原始字节，以绑定整个配置块。构建与运行时必须逐字节相同，不在运行时重新序列化 JSON。配置长度受上限约束，tag 认证前不根据配置执行任意类名或路径。

packageName 可以与 ApplicationInfo 的公开信息核对；versionCode 在此阶段只是认证后的构建元数据，不承诺通过 PackageManager 比对安装身份。APK 身份、防重签名不属于本地模式目标。

### 6.3 明文与内存

沿用未压缩 JG4 多 DEX 容器，使用数值 DEX 顺序：classes.dex、classes2.dex、…、classes10.dex；不得按字符串排序。认证后校验 DEX 数量、表大小、每项 offset/length/plainLength、无重叠、连续覆盖、总长度和 DEX header/file_size。不同 DEX 中重复类描述符在构建期拒绝。

初始硬上限建议：明文总计 128 MiB、最多 128 个 DEX、单项不超过总预算；这些是待设备验证的产品约束，不是 Android 保证。实施时配置项、默认值和错误信息同步确定，不开放无界读取。

优先解密到单个 DirectBuffer，再以 slice 提供各 DEX，避免每 DEX 再复制。密码 provider 的 ByteBuffer 支持及峰值复制必须在 API 29/30/35/36 测量，不能把零拷贝写成已保证行为。明确统计密文、明文、provider 临时数组及 ART 使用的峰值。

密钥与可写临时数组在 finally 清零。失败时尽量清零未使用的明文 buffer；成功后 Registry 保留承载 DEX 的 buffer，不因“清理”提前破坏有效 DEX。不使用内部 DirectBuffer cleaner，避免新增 non-SDK 依赖。不承诺 JVM/provider 内部副本可完全擦除。

## 7. 业务 Native 库与 split

移除的是 Jiagu 本地模式的 core/payload `.so`，不是移除应用自身或第三方 SDK 的 native 库。

使用 API 29 的带 librarySearchPath 构造器。搜索路径包含公开 nativeLibraryDir；未提取的 native 库还需验证 base/split APK 的归档内路径以及正确进程 ABI。不得通过反射复制 L0 的内部 native 路径。

ABI 选择不能只取 Build.SUPPORTED_ABIS[0]：必须结合 Process.is64Bit、APK 实际已安装 native 条目及打包 ABI，校验唯一可用的进程架构。如公开信息不能消除 ABI/namespace 歧义，应拒绝该组合，不能猜测错误路径。

首个实现原型必须覆盖：

- extractNativeLibs=true 的目录库；false 的 APK 内未压缩库。
- arm64-v8a、armeabi-v7a、x86_64、x86 中实际发布的架构。
- base 内 native 库、ABI split 内 native 库、DT_NEEDED 依赖。
- Payload 类调用 System.loadLibrary 后的 JNI 注册和 FindClass。
- 同一 `.so` 被 Shell 与 Payload 两个定义加载器加载的冲突。

跨 loader 重复加载同一库、SDK 按绝对路径自建 namespace、JNI 从无 Java 调用栈的线程寻找业务类均作为受限场景，不以“文件存在”认定兼容。APK 内路径的可用性与 linker namespace 属于发布前原型验证项；若失败须调整设计，不能落盘明文 DEX或恢复 hidden API。

常规 AAB splits 中 Payload 固定来自 base；资源/native split 的路径和生命周期交由系统处理并专项验证。动态 feature 后安装会改变代码集合，首阶段不支持。

## 8. Gradle 与模块改造

### 8.1 模块划分

新增 `jiagu-local-runtime`：工厂、Reader、Crypto、容器解析、Registry、受控错误类型以及独立本地事件模型/Reporter/队列/Initializer/上传接口。无 externalNativeBuild，无网络权限，无 OkHttp/Conscrypt/Integrity/Tink，无在线授权 Reporter/协议依赖。上传器由应用实现，Runtime 只在后台调用接口。

MVP 插件只接入 `jiagu-local-runtime`；不新增或迁移在线 Runtime 路径。现有在线代码作为历史实现隔离，不进入 MVP 任务图、依赖或 APK；MVP 入口选择 online 时明确报出不支持，避免隐式执行旧流程。物理删除整个仓库在线模块不是本次文档要求，后续单独清理。

Maven 坐标、composite build 的 project 注册、publication properties 与本地开发 settings 同步支持新模块。新模块使用独立本地 namespace，禁止旧 namespace 硬编码导致错误 lane 或所有权检查。

Runtime-only R8 规则按运行库分别内置；保留 manifest 工厂入口、生成工厂及 bootstrap ABI。Payload→Runtime 的 ABI 规则仍生成。不得把旧 JNI NetworkHelper/ProxyApplication keep 规则当作本地必要规则。

### 8.2 任务图

```text
原始 class inputs ─→ lane 校验 / Payload D8 ─→ 明文 JG4（构建目录）
                 └→ Runtime-only R8 + Shell 合并 ─→ AGP D8 ─→ APK classes*.dex

明文 JG4 ─→ CreateLocalPayloadTask ─→ generated assets ─→ AGP mergeAssets
原 merged manifest ─→ LocalManifestTransformerTask ─→ 工厂声明
最终 APK/AAB ─→ 验证任务：密文、类归属、SPI、工厂、无旧 JNI路径
```

- local 不注册 JiaguReleaseTask、JiaguPublishFlowAction 或 native ELF 构建任务；不求值 certificateSha256/serverUrl/companyApiKey。
- 本地加密任务输入：Payload、包名、versionCode、格式版本及适配工厂类型；输出 generated assets 目录。禁止读取任务未声明的项目状态。
- 任务允许 up-to-date。随机密钥不作为输入；只有任务真正重跑才生成新 key/nonce。首阶段禁用远程 build cache，避免未审计的材料共享。随机产物不承诺跨清理构建字节级可复现。
- 不依赖资源 `.ap_` 摘要、不依赖 native 构建输出、不读取最终 merged assets，避免 assets 自循环。
- 使用 generated source/public Artifact wiring 传递依赖，不靠任务名拼接或 in-place 资源修改。
- 本地生成资产的基名固定。业务已有同名 asset 时失败；不覆盖。
- MVP verify 任务覆盖 assemble；bundle 未完成验证前明确提示不支持。扩展阶段 AAB 先校验容器/类分布，再通过 bundletool 安装 splits 验证运行；APK-only 校验不能代表 AAB 已验证。
- 本地基线关闭资源混淆及 shrinkResources；未实现的业务 R8 DSL 不在本次加入。

### 8.3 配置与迁移

MVP 只提供 component-factory 本地加载，不增加 legacy-jni 双路径配置。旧插件版本仅供构建版本回退，不打包为 Runtime fallback。

```groovy
dexReport {
    protectionMode = "local"
    payloadSelectionMode = "allowlist"
    payloadIncludePackages = ["com.example.business.**"]
    shellMinificationEnabled = true
    resObfuscationEnabled = false
    startupTelemetryEnabled = true // 拟新增：默认 false，示例显式启用
    startupLogUploaderClass = "com.example.telemetry.StartupEventUploader"
}
// Android DSL：minSdk >= 29；shrinkResources=false。
```

上述 telemetry 开关为拟新增，现有 startupLogUploaderClass 在新 local 中重新启用，但使用第 9 节的新本地上传接口。业务上传器及依赖必须留 Shell，且通过第 5 节边界校验。启用但缺少上传器时构建失败；关闭时不创建队列消费者或初始化上传器。

MVP 直接以本地公开工厂路径交付，不并行实现 online。业务事件 URL/普通 HTTP 上传配置由上传器负责，不能复用公司 API Key、Release URL 或设备授权配置。

## 9. 失败语义与日志

所有 bootstrap 失败抛出含稳定错误码与原 cause 的受控运行时异常，让系统终止组件创建。不得返回 L0 继续“未加固启动”，不得清除真实 Application 异常，不用 _exit(0)。

建议错误码：

| 类别 | 代码 |
| --- | --- |
| 容器 | LOCAL_PAYLOAD_MISSING / DUPLICATE / FORMAT_UNSUPPORTED / LENGTH_INVALID |
| 密码 | LOCAL_PAYLOAD_AUTH_FAILED / HASH_MISMATCH |
| DEX | LOCAL_DEX_TABLE_INVALID / MEMORY_BUDGET_EXCEEDED / ALLOCATION_FAILED |
| 加载 | LOCAL_CLASSLOADER_CREATE_FAILED / BOOTSTRAP_REENTRANT |
| 构建边界 | LOCAL_SHELL_TO_PAYLOAD_REFERENCE / CROSS_LOADER_ACCESS / FACTORY_UNSUPPORTED |
| Native | LOCAL_NATIVE_PATH_UNSUPPORTED |

日志与事件只记录阶段、耗时、受控错误码和必要的进程/版本元数据，不记录 key、nonce、明文、完整配置、异常正文或服务器数据。本地模式不初始化旧 SQLite 启动 outbox。原始业务异常遵循 Android 原生传播和系统崩溃处理。

### 9.1 本地异步事件 MVP

复用 03/05 文档的 session、稳定事件 ID、后台上传和故障隔离原则，但不复用旧 12 阶段的 native/授权含义，也不在 MVP 实现 05 的持久化可靠投递。新增 `LocalStartupEvent` / `LocalEventUploader`，避免旧接口“跨重启补传”的契约被悄悄降级。

事件字段：schemaVersion、mode=local、sessionId、eventId、stage、status、resultCode、occurredAtMillis、elapsedSinceStartMs、stageDurationMs、packageName、versionCode、processName，以及受控 failureClass。sessionId 每进程启动生成，eventId 每逻辑事件生成一次，重试沿用；不收集设备密钥或安装标识。

| 阶段 | 插点与可观察边界 |
| --- | --- |
| STARTUP_ATTEMPT | 首次 instantiateClassLoader，记录 STARTED |
| LOCAL_PAYLOAD_READ | assets 读取和容器头校验终态 |
| PAYLOAD_DECRYPT | GCM 认证、解密和摘要校验终态 |
| BUSINESS_DEX_PARSE | JG4/DEX 校验终态 |
| BUSINESS_CLASSLOADER_CREATE | L1 构造终态，不称为注入 |
| REAL_APPLICATION_CREATE | 工厂正常创建真实 Application 后；不是 attach/onCreate 完成 |
| EVENT_REPORTER_READY | Initializer 获得 Context，异步消费者准备就绪 |
| FIRST_ACTIVITY_RESUMED | 注册公开 ActivityLifecycleCallbacks 后观察首次 resume；不是首帧提交 |

MVP 不自动声明 REAL_APPLICATION_ATTACH 或 REAL_APPLICATION_ON_CREATE 成功：公开工厂只有实例化插点。需要这些精确事件时，业务在自己的 attachBaseContext/onCreate 返回前显式调用报告 API；不开启字节码插桩、不重新代理生命周期。无 Activity 的后台进程不因缺少 resume 事件判定失败。

### 9.2 采集、初始化与投递

```text
工厂/解密阶段 ─→ 安全 emit ─→ 有界内存队列（无 Context、无文件 IO）
真实 Application attach ─→ LocalEventInitializer Provider
                         └→ 注册生命周期观察 + 启动后台消费者
后台单线程消费者 ─→ 应用 LocalEventUploader ─→ 事件接收端
```

- 所有采集调用包裹故障隔离；事件异常不改变解密成功/失败，不替换原始 throwable。
- emit 只记录轻量不可变数据并入队，不创建 HTTP 请求，不加载业务 SDK，不等待队列消费者。
- Initializer.onCreate 仅保存 Application Context、注册公开生命周期回调并调度后台任务；不调用 HTTP、不等待 Future、不写数据库。Provider 构造器无副作用。
- Provider 配置 `initOrder=1000`，只用于尽早初始化，不承诺早于任意第三方 Provider；必须检测 authority 冲突。默认只注入主应用进程，子进程只采集本地诊断，不承诺上传。
- 上传器构造/反射初始化也在后台进行。上传器应独立于业务 Application.onCreate 的全局状态，不能在后台等待主线程完成启动。
- 默认队列上限 64 条、单条序列化上限 4 KiB、单消费者；满队列保留失败终态优先，淘汰最早非失败事件，无可淘汰项则丢弃新事件并计数。所有路径非阻塞，不以队列容量阻断启动。
- 单事件至多 3 次尝试，失败退避 1 秒、5 秒；重试不生成新 eventId。单事件处理完毕后继续其它事件，非重试错误直接丢弃；调度器不在工作线程 sleep。
- 上传器正常返回表示接收端已确认，抛出受控投递异常表示失败。应用示例上传器必须设置连接/读取超时（各 3 秒）并遵守一次投递总时间预算（10 秒）；任意自定义实现不可由 Runtime 强制终止，因此其超时契约必须通过验收，禁止无限阻塞。
- 进程退出、强制结束和队列溢出可丢事件；MVP 明确不保证跨重启补传或 exactly-once。接收端按 eventId 去重，不要求开发新的在线加固服务。

### 9.3 早期失败与网络边界

解密/loader 创建失败发生在 Context/Initializer 可用前，事件最多留在内存并输出受控本地日志；若进程随后退出，不能保证上传。这是 MVP 的明确限制，不得为了失败上报创建临时 Application、访问内部字段或等待网络。

去除“网络加密流程”指去除在线 Payload 加密/取钥、bootstrap/enroll/authorize、Grant/JWS、RSA 包装密钥、KeyStore、Play Integrity、Release 创建/seal/publish 等流程。保留本地 AES-GCM；事件上传不参与这些流程，不引入应用层请求加密、加固鉴权或密钥交换。常规 HTTPS/TLS 仍作为传输保护，不因收敛 MVP 改成明文 HTTP；使用平台默认 TLS，不打包专用 Conscrypt。

关闭事件功能时 Jiagu 零网络调用；启用时只有事件上传器的异步请求。网络断开、4xx/5xx、错误响应、上传器初始化失败和超时均不影响应用启动。不把事件接收失败当作加固失败，也不将事件投递状态用于授权。

## 10. 兼容矩阵与验收

### 10.1 功能验收

| 编号 | 场景 | 必须证明 |
| --- | --- | --- |
| F01 | 无自定义 Application | 系统默认 Application 正常创建 |
| F02 | 自定义 Application 在 Payload | 构造、attachBaseContext、onCreate 各一次，身份一致 |
| F03 | Application attach/onCreate 主动抛异常 | 原 cause 对外可见，没有伪成功 |
| F04 | Payload Provider / AndroidX Startup | Provider 能使用真实 Application；时序保持 attach → Provider → onCreate |
| F05 | AndroidX CoreComponentFactory | 全组件包装语义保留，不只测试 Activity |
| F06 | 不支持的自定义工厂 / API 28 | 构建明确失败，不静默覆盖 |
| F07 | SDK Shell→业务静态引用 / 包级访问 | 构建失败并定位引用 |
| F08 | 回调接口 / SPI / 反射 / 自定义 View | 正确类身份，无重复类和 ClassCastException |
| F09 | 10 个以上 DEX | 数值顺序正确，所有类可加载 |
| F10 | 密文/config/tag 损坏、截断、恶意长度 | 有界读取并失败；不启动业务，不落盘明文 |
| F11 | 多进程 / Direct Boot | 每进程一次加载；不用解锁后私有存储和 KeyStore |
| F12 | 重复工厂实例 / 并发 / 重入 | loader 与类身份稳定，没有死锁 |
| F13 | Native 目录库、APK 内库、ABI split | System.loadLibrary、JNI、依赖库实际执行成功 |
| F14 | Instrumentation | 测试 APK 与业务类身份正确；不误解密测试 APK |
| F15 | R8 开关 true/false | bootstrap 可用，mapping 与外部 ABI 正确 |
| F16 | 无签名配置的 APK 构建 | local 不因求值签名而失败；安装签名仍按正常 Android 流程 |
| F17 | 关闭/启用事件上报 | 关闭零请求；启用仅后台上传，bootstrap 不联网 |
| F18 | 上传器抛异常、超时、无网、4xx/5xx | 解密和业务启动结果不变，重试有界，继续处理其它事件 |
| F19 | 队列满、重试、重复工厂实例 | 丢弃策略确定、重试 eventId 不变、无重复阶段终态 |
| F20 | 解密前致命失败 | 原始 cause 保留，本地记录稳定错误；不虚假承诺成功上传 |
| F21 | 事件采集/初始化自身故障 | 不改变 ClassLoader 结果，不阻断 Provider/业务启动 |
| F22 | Application onCreate 与首次 resume | 创建事件不冒充生命周期完成，resume 不冒充首帧 |

MVP 必测 API 29/36 的普通 APK、当前示例使用的 ABI、Application/Provider/AndroidX 工厂、边界失败、密文损坏及 F17～F22。F13 的 split 部分、F14 的广泛 Instrumentation 组合及完整设备矩阵作为扩展阶段验收，不阻塞基础 MVP；实际 MVP APK 若包含业务 native 库，其目录/APK 内加载必须验证。

完整支持声明前，API 29、30、35、36 必测，其余支持的 API 31～34 覆盖回归；发布 ABI、OEM、低内存、16 KB、AAB splits 均纳入扩展矩阵。未经验证不扩大支持范围。16 KB 要求仍适用于应用自身及第三方 native 库。

### 10.2 产物与静态门禁

- Manifest 保留真实 Application，包含正确本地工厂，无旧本地代理 metadata。
- 最终 classes*.dex 不含保护范围内业务类；密文 asset 唯一且位于 base。
- 本地 APK 不含 Jiagu core/liblog_ext、在线认证依赖或在线加固配置；本地 AAR 不添加联网权限。事件示例应用显式声明所需 INTERNET。
- Jiagu 本地 runtime 不含内部字段访问、反射绕过、JNI Application 替换或明文落盘代码。
- 基于最终 class/DEX 检查路由及重复描述符；校验结果作为 JSON artifact 保留。
- 打开 detectNonSdkApiUsage 做辅助观察，但以代码/产物审计和设备结果共同判定，不能把没有日志当作完全证明。
- 抓取 filesystem/network 行为确认无明文文件、无取钥/授权/Release 请求；事件关闭无 Jiagu 网络请求，启用仅出现异步事件投递。应用自身网络行为另行归属。

### 10.3 性能验收

相同业务 Payload、相同设备比较旧 local 与新 local，分别测量读取、GCM 解密、解析、ClassLoader 创建、真实 Application attach/onCreate、首帧和进程内存峰值。生命周期/首帧性能由测试工具测量，不由 MVP resume 事件替代。记录冷启动 P50/P95 与失败率，样本数及条件写入报告；另比较上报关闭、正常网络及无网/超时三种条件，确认上报不造成启动等待。

在组件创建前进行本地 CPU/IO 工作仍位于同步启动链路；公开 API 不消除耗时。初始目标是同设备 P95 启动不比旧 local 回退超过 10%，内存峰值不出现无法解释的额外整份 DEX 副本；最终数值在原型基线后固化。预算不达标先优化容器读取/复制，不引入异步半启动或 hidden API fallback。

## 11. 实施阶段与退出条件

### P0：公开 API 原型（先验证架构）

建立独立最小 fixture，使用固定测试加密 assets、公开工厂和 InMemory loader，验证 API 29/36 的 Application/Provider/AndroidX 工厂；有业务 native 时验证其搜索路径。确认 Shell→Payload 受限样例真实失败、正向样例真实成功。原型不实现网络加固流程。

退出条件：普通 APK 生命周期正确，工厂实例重建不导致重复解密，当前示例需要的 native 能运行；split 验证留到扩展阶段。

### P1：容器与轻量运行库

实现新本地 AAR、格式 reader/crypto、Registry、错误模型和容器损坏测试。接入原始工厂适配，完成内存上限和失败传播测试；增加独立事件模型、有界队列、Initializer、后台投递接口与应用示例上传器，验证无网/异常隔离。

### P2：插件接入与依赖边界

加入分流边界分析、LocalManifestTransformerTask、CreateLocalPayloadTask、generated assets wiring、新 runtime 坐标与发布。删除 MVP 图上的签名求值、在线发布、网络取钥、授权、native ELF、旧 SQLite reporter 依赖；接入事件开关及上传器 keep 规则。只交付本地加载路径。

### P3：MVP 交付验证

运行第 10 节明确的 MVP 必测项，产出 APK、依赖图、路由报告、设备日志和性能报告。消费方使用发布 AAR 的构建必须验证，不能只测同仓 project dependency。完成条件是“本地密文 → 公开工厂内存加载 → 正常业务启动 + 可选异步事件投递”；不以在线服务端、授权或 AAB 全矩阵作为依赖。

### P4：扩展验证与清理（MVP 后）

补齐常规 AAB splits、更多 ABI/OEM/Instrumentation/native 组合及完整性能矩阵。更新 07/08/09 和事件文档，明确本地 MVP 的投递能力低于历史持久化方案。清理 JiaguTask 不可达旧方法、重复分流规则和无效 attachToTask 配置；旧在线实现保持隔离，按后续任务决定物理删除范围。持久化事件队列作为独立增强项，不捆绑网络授权。

回退通过选择上一个插件/运行库版本并重新构建 APK 完成，不在已发布 APK 内切换 loader。新旧容器按版本拒绝误读，不提供降级到未加密业务的路径。

## 12. 待原型验证事项

| 问题 | 决策/关闭方式 |
| --- | --- |
| OEM / Instrumentation 的工厂调用差异 | 对各目标系统记录实际调用及类加载身份，未通过不宣称支持 |
| AndroidX 工厂适配版本范围 | 读取实际依赖实现并测试包装契约，建立版本清单 |
| APK 内 native 搜索路径 / linker namespace | P0 实际加载并执行 JNI；不可行组合明确限制或另设计 |
| 资源/反射/SPI 从错误 loader 解析业务类 | 定位调用方，调整边界或显式 loader；不做双向注入 |
| DirectBuffer provider 内部复制与 ART 持有 | 测量峰值、长期存活及退出清理，不用内部 cleaner |
| 原始工厂子类与 Runtime-only R8 组合 | 编译生成类并验证原父类和稳定入口保留 |
| APK 安装更新、系统恢复及特殊 backupAgent 入口 | 独立 fixture 验证；未通过时声明受限，不偷调用 ProxyApplication |

## 13. 官方依据与使用方式

- [AppComponentFactory](https://developer.android.com/reference/android/app/AppComponentFactory)：API 29 的 ClassLoader 扩展点在组件和 Context 初始化前调用；本设计利用该公开契约，不提供更早可用的 Context。
- [InMemoryDexClassLoader](https://developer.android.com/reference/dalvik/system/InMemoryDexClassLoader)：公开内存 DEX 加载 API；API 29 提供 native 搜索路径参数。本设计的 split/native 组合仍须验证。
- [non-SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)：目标是去掉 Jiagu 本地链路对此类接口的依赖，不把 JNI 当作豁免。
- [AOSP LoadedApk](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java)：仅用于理解和设计测试系统类加载流程；源码内部成员不构成本方案可调用的公共 API，也不作为跨 OEM 保证。

与既有文档的关系：本文是本地公开 API 加载与异步事件 MVP 的后续设计，07/08 和 03/05 的历史说明保留；存在范围冲突时，本次本地 MVP 以本文为准。旧在线协议、服务端发布和 native 诊断文档仅作历史参考，不纳入 MVP 实现依赖。
