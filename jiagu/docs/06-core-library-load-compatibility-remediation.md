# `jiagu-core` 加载失败：兼容性修复方案

## 1. 目标、范围与非目标

本方案解决 `ProxyApplication.attachBaseContext()` 中
`System.loadLibrary("jiagu-core")` 的线上失败无法归因，以及 Android ABI、16 KB
页设备、APK/AAB 打包和 JNI 装载路径的兼容性风险。

成功的定义不是“Stage 2 的失败事件变少”，而是同时满足：

1. 每个失败启动均可在不上传敏感信息的前提下归入稳定原因；
2. 每个发布 ABI 的最终 APK/AAB split 均包含可被系统加载的 `libjiagu-core.so`；
3. 16 KB 页设备上的 ELF 段对齐和 APK 内 zip 对齐均由构建门禁验证；
4. 上报、诊断和测试代码本身不会改变原始加载结果或掩盖原始异常。

本方案不改变加固策略、业务 DEX 加载策略或 Android 的标准 native 库加载机制。
尤其**不**采用“从 APK 手工解压 `.so` 后调用 `System.load(absolutePath)`”的兜底方案；这会绕过
split APK、linker namespace、安装优化及系统的完整性边界，兼容性通常更差。

## 2. 当前结论与问题分级

已对当前 arm64 构建产物做过静态抽查：

- `libjiagu-core.so` 的 `PT_LOAD` 对齐为 `0x4000`；
- 对应 arm64 split APK 的 `zipalign -c -P 16 -v 4` 验证通过；
- `liblog_ext.so` 构建时已传入 `-Wl,-z,max-page-size=16384`。

这说明本次抽查的产物可用于 16 KB 页设备；但 `jiagu-core` 的 CMake 没有显式声明同一链接约束，
仍依赖当前 NDK 的默认行为，不能作为长期保证。

线上当前将所有 `Throwable` 汇总为 `CORE_LIBRARY_LOAD_FAILED`。`System.loadLibrary` 失败既可能是
`libjiagu-core.so` 找不到，也可能是依赖解析、ELF 校验或 `JNI_OnLoad` 返回 `JNI_ERR`。在没有原始
linker 分类、ABI 和安装包信息时，不能将“Stage 2 失败率”直接解释为某一个兼容性问题。

| 优先级 | 风险 | 处理原则 |
| --- | --- | --- |
| P0 | 没有真实失败原因，无法制定有效修复 | 先完成错误归因、服务端兼容和灰度，再依据数据调整发布包 |
| P0 | 诊断/上报自身抛错，覆盖原始 `UnsatisfiedLinkError` | 诊断必须 best-effort，原始异常始终优先抛出 |
| P0 | 最终 APK/AAB 某 ABI 缺少 core 或 payload 库 | 对全部 split 执行自动化结构校验 |
| P1 | core 未显式声明 16 KB 对齐 | 固化链接参数，并检查最终 ELF 与 ZIP 对齐 |
| P1 | `JNI_OnLoad` 中的 `FindClass`/注册失败不可分类 | 增加原生失败点、清理 pending exception、以稳定代码映射 |
| P1 | 隐藏 API 和 ROM 内部字段变化 | 将其作为 Stage 3+ 的独立兼容性问题，不能混入 core-load 指标 |

## 3. 实施项

### 3.1 Java：原始异常优先、受控诊断

**修改位置：** `jiagu-runtime/src/main/java/io/github/xjc/jiagu/ProxyApplication.java` 的
`loadCore()`。

1. 保留延迟加载位置：仍在 `attachBaseContext()`、Reporter 初始化之后加载；不要移入静态初始化块。
2. `System.loadLibrary` 仅调用一次，不做手工绝对路径 fallback。
3. 捕获 `UnsatisfiedLinkError` 并生成结构化诊断；其他 `LinkageError` 单独归类；最后才处理其他
   `Throwable`。无论哪种情况，都必须原样重新抛出原始错误。
4. 将 `shellCoreLoadSucceeded` 和 `shellCoreLoadFailed` 放入独立 `try/catch(Throwable ignored)`。
   Reporter、SQLite、反射 uploader 或日志框架失败时不得替换已发生的 loader 错误，也不得使已加载成功
   的库变为启动失败。

建议新增内部结构 `CoreLoadFailure`，只保存有限字段：

```text
resultCode             // 下表定义的稳定枚举
failureClass           // 例如 java.lang.UnsatisfiedLinkError
loaderMessageCategory  // 经规则归类，不保存原始 message
sdkInt
supportedAbis          // Build.SUPPORTED_ABIS，数量限制
nativeLibraryDirMode   // directory / split / unknown；不上传实际路径
runtimeVersion
```

不保存异常堆栈、完整 `getMessage()`、本地文件路径、token、签名内容、payload 内容或服务器响应正文。
完整异常仅可输出到本地 debug 日志；release 只输出 `resultCode` 和 `failureClass`。

建议的原因映射如下。字符串匹配仅用于诊断，不能作为安全决策或启动 fallback 的依据。

| `resultCode` | 条件 |
| --- | --- |
| `CORE_LIBRARY_ABI_NOT_PACKAGED` | `UnsatisfiedLinkError` 显示库未找到，且当前 ABI 无对应库 |
| `CORE_LIBRARY_DLOPEN_FAILED` | linker/dlopen 拒绝加载，但不属于更具体类别 |
| `CORE_LIBRARY_ELF_ALIGNMENT_INVALID` | linker message 明确指出 ELF load segment 或 16 KB page alignment 问题 |
| `CORE_LIBRARY_DEPENDENCY_MISSING` | linker message 明确指出 `DT_NEEDED` 依赖无法解析 |
| `CORE_LIBRARY_JNI_ONLOAD_FAILED` | 库已定位但 `JNI_OnLoad` 返回 `JNI_ERR` 或注册 JNI 失败 |
| `CORE_LIBRARY_LINKAGE_ERROR` | 其他 `LinkageError` |
| `CORE_LIBRARY_UNEXPECTED_THROWABLE` | 非 Linkage 的异常；用于发现诊断/运行环境缺陷 |

服务端和 `JiaguStartupEvent` 须先以可选字段接收这些字段；旧客户端继续使用
`CORE_LIBRARY_LOAD_FAILED`。新字段缺失时按 `UNKNOWN` 聚合，禁止将缺失视为 ABI 故障。

### 3.2 Native：固化 ELF 对齐与可诊断的 `JNI_OnLoad`

**修改位置：** `jiagu-runtime/src/main/cpp/CMakeLists.txt`。

在 `target_link_options(jiagu-core ...)` 增加：

```cmake
-Wl,-z,max-page-size=16384
```

`liblog_ext.so` 已使用同一参数；两者必须保持一致。该修改应覆盖所有 ABI，不针对单独机型或
Android 版本分支。

**修改位置：** `jiagu-runtime/src/main/cpp/jiagu-core.cpp` 的 `JNI_OnLoad`。

1. 将 `FindClass(ProxyApplication)`、`RegisterNatives`、Reporter 方法缓存分别记录为独立 native
   诊断点；`JNI_OnLoad` 不能只打印泛化“failed”。
2. `FindClass` 或 `RegisterNatives` 失败时先检查并清除 pending Java exception，再记录固定的本地
   原因码（如 `JNI_ONLOAD_PROXY_CLASS_NOT_FOUND`、`JNI_ONLOAD_REGISTER_NATIVES_FAILED`），最后返回
   `JNI_ERR`。
3. `cache_startup_reporter()` 保持可选：Reporter 缓存失败只能禁用 native telemetry，不能使
   `JNI_OnLoad` 失败。
4. 不在 `JNI_OnLoad` 执行网络、磁盘 I/O、反射业务类、payload 解密或 anti-debug 逻辑。

`JNI_OnLoad` 的本地诊断可能无法在同一次进程启动上传；允许以 Logcat/Crash 平台辅助定位，或在下一次
正常启动读取受限的本地诊断摘要。它不能声称这次启动已成功上报。

### 3.3 构建与打包：ABI、ELF、ZIP 三层门禁

**修改位置：** `dex-report-plugin` 的 release 验证任务；建议新增独立 Gradle task，例如
`verifyJiaguNativeCompatibility<Variant>`，并令 `assemble`、`bundle`、`createJiaguRelease` 依赖它。

门禁必须基于**最终** APK 或从 `.apks` / bundletool 提取的每个 device split，而不是仅检查 CMake 的
中间目录。对每个实际发布 ABI（`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 中被 variant 保留的集合）执行：

1. 检查 `lib/<abi>/libjiagu-core.so` 存在；
2. 检查 `lib/<abi>/liblog_ext.so` 存在；
3. 使用同一 NDK 的 `llvm-readelf -lW` 检查两个库全部 `PT_LOAD` 段满足 16 KB 对齐；
4. 对最终 APK 执行 `zipalign -c -P 16 -v 4`；
5. 使用 `llvm-readelf -dW` 限定 core 的 `DT_NEEDED` 只能出现经过批准的系统库；当前基线为
   `liblog.so`、`libandroid.so`、`libz.so`、`libm.so`、`libdl.so`、`libc.so`；
6. 输出一个不含二进制内容的 JSON 摘要：variant、ABI、库大小、ELF 对齐、DT_NEEDED、校验结果和构建工具版本。

若 app 定义 ABI filter，门禁以 filter 后集合为准；若发布 AAB，必须用 bundletool 生成至少一套
ARM32、ARM64 和 x86/x86_64（如声明支持）的设备规格 APKS 来检查。ABI 列表为空、任一 core/payload
不成对、readelf/zipalign 失败均应使构建失败。

#### `android:extractNativeLibs` 决策

**默认决策：不在最终 Manifest 强制注入 `android:extractNativeLibs="true"`。**

该属性控制安装器是否把 native 库从 APK 解压到文件系统：

| 设置 | 打包及加载行为 | 对本项目的结论 |
| --- | --- | --- |
| `false`（现代 AGP 的通常行为） | `.so` 未压缩并按 ZIP page alignment 存在 APK 内，linker 可直接从 APK 映射 | 当前正式策略；配合 16 KB ELF 段对齐和 16 KB ZIP 对齐，安装占用和加载路径更优 |
| `true` | `.so` 以压缩形式打包，安装时解压到应用 native library 目录后由 linker 加载 | 不是常规修复；会增加安装时 I/O、已安装占用及低存储设备安装失败风险 |

它**不能**修复以下根因：ABI split 缺库、`JNI_OnLoad`/`RegisterNatives` 失败、`DT_NEEDED` 缺失、
ELF 自身的 16 KB `PT_LOAD` 对齐错误，或后续 hidden-API 路径失败。即使库被抽取到文件系统，16 KB
设备仍要求 ELF 段本身正确对齐。

唯一可接受的例外是：旧 AGP/bundletool 链无法将未压缩的 native 库以 16 KB ZIP boundary 打包，并且
无法在短期内升级到具备该能力的 AGP 时，可将“压缩并安装时抽取”作为**临时发布缓解措施**。这不是通过
Manifest Transformer 直接写属性实现，而应使用 AGP 的 DSL：

```groovy
android {
    packaging {
        jniLibs {
            useLegacyPackaging true
        }
    }
}
```

`android:extractNativeLibs` 已由 `useLegacyPackaging` DSL 取代；直接在
`ManifestTransformerTask` 强制写属性可能与 AGP 的最终打包决策冲突，也会绕过构建配置的可见性。
当前工程使用 AGP 9.3.1，满足官方对 16 KB 未压缩 native library 打包的工具链要求，因此不应启用
`useLegacyPackaging` 或注入该 Manifest 属性。

若未来确实触发此例外，必须以显式的、按 variant 控制的 `useLegacyPackaging` 开关实现，并同时满足：

1. 有 CI 证据表明升级 AGP/bundletool 或修复 ZIP 对齐在当前发布窗口不可行；
2. 所有 `.so` 的 16 KB ELF 对齐仍通过；
3. APK/APKS 安装、冷启动和低可用存储测试通过；
4. 该开关有所有者、失效日期和移除任务；工具链升级后恢复未压缩打包；
5. 不将“设置为 true 后失败率变化”直接归因于 core loader，仍按原因码、ABI、SDK 对照分析。

### 3.4 后续 Native 启动链：隔离 hidden-API 兼容性

**修改位置：** `jiagu-runtime/src/main/cpp/jiagu-core.cpp` 中的
`prepare_legacy_keystore_context()`、`inject_dex_elements()`、`bind_real_application()`。

这些代码依赖 `ActivityThread`、`LoadedApk`、`BaseDexClassLoader.pathList` 和
`DexPathList.dexElements` 等非 SDK 内部实现。它们发生在 core library 已加载之后，必须与 Stage 2 分开：

1. 每个反射/字段访问点返回明确错误码，不使用“打印日志后直接 return”的隐式失败；
2. `BUSINESS_CLASSLOADER_INJECT`、`REAL_APPLICATION_ATTACH` 分别上报稳定失败码；
3. 按 API 主版本和 ROM 维度汇总，而不是把它们记为 `CORE_LIBRARY_LOAD_FAILED`；
4. 在测试机矩阵发现字段变化时，优先增加受版本保护的实现分支或安全终止，禁止在 release 通过猜测字段名
   继续执行。

### 3.5 API 29–36 代码兼容性审计与改造设计

本节是对当前 Java/JNI 源码的静态审计结论。它将“明确不兼容风险”和“需要设备/服务环境验证的风险”分开。
除非条目明确写为已确认，不能仅凭静态代码断言某个 API 或厂商 ROM 必然失败。

#### API 版本概览

| API | 平台版本 | 与本 Runtime 的主要关注点 | 当前结论 |
| --- | --- | --- | --- |
| 29 | Android 10 | AndroidKeyStore 在 Application 早期初始化、non-SDK 限制、签名 API 迁移 | 高风险；已有临时 `ActivityThread` bridge，但 bridge 本身是 non-SDK 调用 |
| 30 | Android 11 | 同 API 29 的 early Keystore 和 hidden API 风险；包可见性规则 | 高风险；只读取自身包，不受 `<queries>` 影响 |
| 31 | Android 12 | non-SDK 限制随 target SDK 收紧、Play Integrity/Google Play Services 可用性 | 高风险；必须把 Integrity 的设备/Play 环境错误与代码错误分开 |
| 32 | Android 12L | 同 API 31；大屏/多窗口不改变本库装载语义 | 无新增确定性 API 断点，仍需覆盖 |
| 33 | Android 13 | 签名轮换语义、Keystore/Play Integrity 设备条件、目标 SDK 收紧 | 中高风险；Java 使用 `SigningInfo`，native 仍使用已弃用的签名字段，语义不一致 |
| 34 | Android 14 | hidden API 黑名单演进、严格的启动性能/ANR 暴露、动态代码加载安全要求 | 高风险；内存 DEX 本身不触发“磁盘动态代码文件只读”规则，但 hidden API 路径未消除 |
| 35 | Android 15 | 16 KB page-size 设备、native ELF 与 APK ZIP 对齐 | 高风险；必须执行第 3.2/3.3 节的全产物门禁及 16 KB 实机验证 |
| 36 | Android 16 | non-SDK 列表和 OEM 实现持续变化；本项目 targetSdk 36 | 无已知的本库公开 API 硬断点；不得将“未测到”视为兼容，必须作为发布矩阵必测项 |

#### A. 已确认的 high-risk non-SDK 调用

**涉及位置：** `jiagu-core.cpp` 的 `prepare_legacy_keystore_context()`、
`inject_dex_elements()`、`bind_real_application()`。

当前通过 JNI 访问下列非 SDK 类、字段或方法：

```text
android.app.ActivityThread.currentActivityThread()
ActivityThread.mInitialApplication / mBoundApplication / mAllApplications
ActivityThread$AppBindData.info
android.app.LoadedApk.mApplication
dalvik.system.BaseDexClassLoader.pathList
dalvik.system.DexPathList.dexElements / DexPathList$Element
android.app.Application.attach(Context)
```

Android 从 API 28 起对 non-SDK 接口施加限制，并且限制同时适用于反射和 JNI 的 `GetFieldID`/
`GetMethodID`；被拦截时 JNI 会返回 `NULL` 并产生 `NoSuchFieldError` 或 `NoSuchMethodError`。
OEM 还可以在相同平台版本追加 blocklist。因此这不是“API 29–36 均可工作”的可承诺实现。
官方参考：[non-SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)。

设计要求：

1. 建立 `HiddenApiAccess` 清单，逐项记录声明类、成员签名、业务目的、最小/最大已验证 API、ROM
   和替代方案；禁止新增未登记项。
2. 所有 JNI member lookup 在失败后立即捕获/清除 pending exception，并返回具体结果码；不得让
   `JNI_CHECK_NULL` 仅打印日志并从较大流程静默返回。
3. 将失败归入后续阶段而不是 Stage 2：`KEYSTORE_CONTEXT_BRIDGE_FAILED`、
   `DEX_PATHLIST_ACCESS_DENIED`、`APPLICATION_BIND_ACCESS_DENIED` 等。
4. 新增 debug-only `StrictMode.detectNonSdkApiUsage()` / logcat 收集测试；release 不得尝试通过
   adb hidden-API policy 绕过限制。
5. 长期目标是减少对 `ActivityThread`/`LoadedApk`/`DexPathList` 写入的依赖。若某能力没有公开 SDK
   等价物，应将该能力视为特定 API/ROM 的受控支持范围，而非静默兼容。

#### B. 签名校验存在双实现和签名轮换不一致

**涉及位置：** native `verify_signature()` 使用 `PackageManager.GET_SIGNATURES`（值 `64`）和
`PackageInfo.signatures[0]`；Java `NetworkHelper.AppIdentity.read()` 使用
`GET_SIGNING_CERTIFICATES` 与 `SigningInfo.getApkContentsSigners()`。

`GET_SIGNATURES` 和 `PackageInfo.signatures` 已废弃，且只取数组第一个证书。在 APK 签名轮换、多签名或
OEM 实现差异下，native 与 Java 可能对同一个已安装 APK 得到不同的证书集合：native 在 Stage 4 先阻断，
Java 的后续授权逻辑则永远没有机会执行。

设计要求：

1. 将证书读取收敛成壳 DEX 中唯一、可测试的 Java helper；native 只调用该 helper 并接收排序、去重后的
   SHA-256 digest 集合或受限状态码。
2. 校验“允许集合与当前 signer 集合是否相交/匹配”的规则由服务端配置明确定义；不得依赖 `[0]` 的顺序。
3. 兼容旧协议时，保留旧字段，但为签名轮换增加独立诊断：`SIGNING_INFO_UNAVAILABLE`、
   `SIGNER_SET_MISMATCH`、`SIGNER_API_INCONSISTENT`。
4. 加入 debug/release、v1/v2/v3/v4 签名、多 signer 及 signing lineage 的 APK 夹具。此项属于 Stage 4
   兼容性修复，不是 core `.so` 加载修复。

#### C. AndroidKeyStore 与密码套件

**涉及位置：** `NetworkHelper.getOrCreateSigningKey()`、`getOrCreateWrappingKey()`、
`decryptWrappedKey()`。

使用的 `secp256r1 + SHA-256` 签名密钥、RSA-2048 OAEP 解包、AES-GCM 均在 API 29 的公开
AndroidKeyStore API 范围内；静态审计没有发现 API 29–36 的确定性缺失 API。然而仍有两个兼容性风险：

1. API 29/30 的 early-startup bridge 依赖前述 hidden API，先于任何 KeyStore 操作失败；
2. 代码将 OAEP 主摘要和 MGF1 摘要固定为 SHA-1，并仅注明“API 29–34”。这必须在 API 35/36 的真机上
   验证；不得假设 provider、硬件模块或企业策略永远允许同一组合。

设计要求：记录 KeyStore 异常的受限类别（如 `KEYSTORE_UNAVAILABLE`、`KEY_GENERATION_FAILED`、
`KEY_INVALIDATED`、`OAEP_DECRYPT_FAILED`），附带 API/ABI/硬件回退与否，但不得上传 alias、密钥或
异常原文。若 API 35/36 验证发现 OAEP-SHA1 不可用，必须由服务端协议支持版本化算法迁移和旧设备兼容期，
不能在客户端无版本地降级密码校验。

#### D. Play Integrity、网络与启动线程

**涉及位置：** `NetworkHelper.getAuthorizedPayload()`、`integrityToken()`、`request()`。

Play Integrity Standard API 对 Android 版本本身覆盖 API 29–36；但可用性仍取决于 Google Play services、
Play 分发/识别状态、账户和网络。官方建议先在真正需要 token 前准备 provider；当前代码在
`attachBaseContext()` 的启动关键路径中执行 `prepareIntegrityToken`、`Tasks.await` 和 HTTP 调用，且临时
设置 `StrictMode.permitAll()`。这不是接口不存在问题，却会在 API 29–36 造成冷启动卡顿、ANR、弱网超时或
设备服务不可用时的启动失败放大。

官方参考：[Play Integrity Standard API](https://developer.android.com/google/play/integrity/standard)。

设计要求：

1. 将 `PLAY_SERVICES_UNAVAILABLE`、`PLAY_STORE_UNRECOGNIZED`、`INTEGRITY_PROVIDER_PREPARE_FAILED`、
   `INTEGRITY_TOKEN_REQUEST_FAILED`、`NETWORK_TIMEOUT` 与 `KEYSTORE_*` 分开记录；不能全部映射为
   `DEVICE_AUTHORIZATION` 的泛化失败。
2. 以真实业务安全边界决定是否允许缓存授权/有限离线启动；不得因提高启动成功率而跳过服务端 grant、签名、
   payload hash 或 AES-GCM 验证。
3. 设计异步预热和有界等待方案时，不能在 main thread 直接等待网络；必须量化 P50/P95、ANR 和失败率，
   并保留 API 29–36 的一致安全语义。
4. `ConnectionSpec.CLEARTEXT` 仅在配置为明文 HTTP 时生效，而 target SDK 较高的应用是否允许明文还会受到
   `networkSecurityConfig` 和平台策略影响。生产 URL 必须是 HTTPS；若允许测试 HTTP，应以独立 debug 配置
   和 `CLEARTEXT_NOT_PERMITTED` 诊断隔离，禁止发布到 release。

#### E. In-memory DEX、ABI 与 API 35+ 页大小

**涉及位置：** native `InMemoryDexClassLoader` 创建、`ByteBuffer.allocateDirect`、zlib 解压，以及
`dlopen("liblog_ext.so")`。

`InMemoryDexClassLoader(ByteBuffer[], ClassLoader)` 为 API 29–36 可用的公开 API，且当前实现不把动态
DEX 写回可执行磁盘文件；静态审计未发现 Android 14 的动态代码文件只读规则直接阻断该路径。真正的风险是：

- 业务 DEX 全量解压为 DirectBuffer，低内存设备可能分配失败或被系统杀进程；
- 代码对 `dex_plain_size` 的单条上限为 256 MiB，但未在 Java/native 边界提供总 DirectBuffer 预算；
- API 35+ 的 16 KB 页面设备要求每个 native 库（含第三方和 payload）的 ELF 及 APK/AAB 对齐均合格。

设计要求：增加 `DIRECT_BUFFER_ALLOCATION_FAILED`、`DEX_TOTAL_MEMORY_BUDGET_EXCEEDED`、
`RUNTIME_BUNDLE_DLOPEN_FAILED` 等稳定失败码；按 ABI/API/可用内存桶统计。16 KB 门禁维持第 3.2/3.3 节的
要求；官方参考：[支持 16 KB 页面大小](https://developer.android.com/guide/practices/page-sizes)。

#### F. API 36 的处理原则

当前代码未直接调用仅在 API 36 新增的公开 framework API，因此没有“编译通过、运行 API 29 缺方法”的直接
风险。真正风险来自 targetSdk 36 下 non-SDK blocklist、OEM 对启动及 KeyStore 的实现差异、第三方
Conscrypt/Play Integrity 的发布版本，以及 API 35/36 设备的 16 KB 页配置。

因此 API 36 的验收基线是：不以 Android 15 的测试结果替代 Android 16；每次 targetSdk、AGP、NDK、
Play Integrity 或 Conscrypt 升级均重跑 API 36 设备矩阵和 hidden-API 扫描。

## 4. 发布顺序与回滚

1. **服务端先行：** 接受新增可选诊断字段及新 `resultCode`，保持对旧 `CORE_LIBRARY_LOAD_FAILED` 的兼容。
2. **客户端观测版：** 只加入 Java 分类、native `JNI_OnLoad` 诊断和构建门禁；不改变加载策略。
3. **灰度：** 1% → 10% → 50% → 100%，每档至少观察 24 小时，并按 `runtimeVersion + versionCode + ABI + SDK`
   聚合。分母为收到 `STARTUP_ATTEMPT` 的主进程 session；分子为对应 session 的 Stage 2 终态。
4. **根据事实修复：** 只有某一原因码及 ABI/API 集中时，才提交针对性修复。例如对齐错误修构建链，ABI
   缺失修变体和 split 打包，JNI 注册失败修 keep/类加载链。
5. **回滚：** 保持事件 schema 向后兼容；仅停止新客户端版本或新的原因码生产，不删除本地 outbox 和已存在
   的事件字段。

下列情况必须停止扩量：核心加载明确失败率相对同版本基线显著上升、出现新的 `JNI_ONLOAD_*` 原因、16 KB
设备失败率上升，或诊断导致新增启动崩溃/ANR。

## 5. 验收标准

### 5.1 单元与集成测试

| 编号 | 场景 | 必须断言 |
| --- | --- | --- |
| L01 | 正常加载 | Stage 2 为唯一 `SUCCEEDED / CORE_LIBRARY_LOADED`；Reporter 故障不影响应用继续进入 nativeAttach |
| L02 | 缺少 `libjiagu-core.so` | 原始 `UnsatisfiedLinkError` 被重新抛出；Stage 2 为 `FAILED / CORE_LIBRARY_ABI_NOT_PACKAGED` 或明确的未知 loader 类别 |
| L03 | 人为令 `JNI_OnLoad` 返回 `JNI_ERR` | Java 侧为 `CORE_LIBRARY_JNI_ONLOAD_FAILED`；native 日志有具体子原因；无 pending exception 泄漏 |
| L04 | Reporter/SQLite/uploader 抛异常 | 不能覆盖原始 loader error，也不能把成功加载变为失败 |
| L05 | ELF 对齐错误夹具 | `verifyJiaguNativeCompatibility` 失败，并指出 ABI、库名和 `PT_LOAD` 证据 |
| L06 | 核心或 payload 库在任一 ABI split 缺失 | 构建失败；错误包含 variant、ABI、缺失条目 |
| L07 | APK ZIP 对齐错误夹具 | `zipalign -c -P 16 -v 4` 失败并阻断发布任务 |
| L08 | API/ROM 下 ClassLoader 或 Application 内部字段不可用 | Stage 2 保持成功；后续对应阶段唯一失败，错误码可区分 |
| L09 | `useLegacyPackaging` 例外构建（仅测试夹具） | ELF 对齐仍通过；APK 的 `.so` 为压缩条目；安装占用、低存储安装、冷启动均满足发布阈值 |
| L10 | 签名轮换、多 signer 和 native/Java 双路径 | 两路径得到相同排序 digest 集合；禁止按数组第一个 signer 判断；不一致时 Stage 4 明确失败 |
| L11 | API 29/30 KeyStore early-startup | 有、无旧 bridge 的路径均有可诊断终态；密钥可创建/读取或以 `KEYSTORE_*` 稳定失败，不得静默退出 |
| L12 | API 29–36 non-SDK 扫描 | debug 构建启用 `detectNonSdkApiUsage` 并保留 logcat 证据；每个访问点关联清单和测试结果 |
| L13 | Play services 缺失、Integrity provider 无效、弱网、明文 HTTP | 不阻塞无界时间；Stage 6 原因可区分；release 无 HTTP 明文成功路径 |

故障注入只能存在于测试构建或测试夹具；生产 APK 不得包含可以使 `JNI_OnLoad`、ELF 加载或 ABI 选择失败的
开关。

### 5.2 产物门禁

每次 release 构建必须满足：

- 发布 ABI 的每个最终 split 都包含 `libjiagu-core.so` 和 `liblog_ext.so`；
- 两个库所有 `PT_LOAD` 段均以 16 KB 对齐；
- 每个 APK 均通过 `zipalign -c -P 16 -v 4`；
- core 的 `DT_NEEDED` 未新增未经审批的非系统 `.so`；
- 默认 release 产物不得由 Manifest Transformer 写入 `android:extractNativeLibs="true"`，也不得启用
  `useLegacyPackaging`；若走经批准的临时例外，CI 必须验证该开关、压缩条目和 L09 的全部证据；
- 门禁的 JSON 摘要作为 CI artifact 保留，与 APK/AAB、AGP、NDK、bundletool 版本关联；
- 任一检查失败时 `assemble` / `bundle` / 发布任务退出非零，不能仅打印 warning。

### 5.3 设备与线上验收

发布候选版本至少覆盖：

- 每个发布 ABI；
- API 29、一个中间 API、当前最高已支持 API；
- 至少一台 16 KB 页 arm64 实机或官方等效验证环境；
- 有代表性的 OEM ROM 和低内存设备；
- APK 安装和 AAB split 安装两种路径。

对 API 29–36 的每一个主版本至少有一台真机或 Google API 系统镜像，并在每个发布 ABI 上完成冷启动、
缓存授权、首次授权、KeyStore key 创建、签名校验、DEX 解压/注入和真实 Application 启动。API 29、30、35、36
是不可降级为抽样的必测档；API 31–34 可在同一 release train 中按设备矩阵分批覆盖，但任何 targetSdk、
NDK、AGP、Conscrypt 或 Play Integrity 版本变化都会使 API 29–36 全部回归失效。

线上验收以 session 去重后统计：

- 新版本 Stage 2 失败事件中，`resultCode`、`failureClass`、ABI、SDK 的覆盖率不低于 99%；其余最多归为
  `UNKNOWN`，不允许静默丢失；
- 与灰度前同渠道、同 ABI/API 的基线相比，`CORE_LIBRARY_*` 明确失败率不升高；
- 若目标是修复既有 3%，在根因确认后，目标 ABI/API 分层的 Stage 2 明确失败率应下降，且独立 crash/ANR
  指标不得恶化；
- 不上传原始异常 message、stacktrace、文件路径、密钥、payload 或签名内容；通过抓包和本地 outbox 检查验证。

## 6. 交付清单

| 交付项 | 责任模块 | 完成证据 |
| --- | --- | --- |
| Java 安全上报与分类 | `jiagu-runtime` | L01–L04 通过；原始错误保留 |
| 16 KB 链接参数 | `jiagu-runtime/CMakeLists.txt` | readelf 结果覆盖全部 ABI |
| JNI_OnLoad 诊断 | `jiagu-core.cpp` | L03 通过；固定 native 原因码清单 |
| ABI/ELF/ZIP 验证任务 | `dex-report-plugin` | L05–L07 通过；CI JSON artifact |
| hidden-API 阶段错误闭环 | `jiagu-core.cpp` | L08 与 API/ROM 矩阵结果 |
| 服务端 schema 与看板 | `server` | 新旧客户端事件均可解析、按 ABI/API 聚合 |
| 灰度报告和回滚演练 | 发布流程 | 每档数据、停止条件和可执行回滚记录 |

## 7. 本次实施记录

本次实施已完成并在本仓库构建中验证的范围：

- `ProxyApplication.loadCore()` 的 reporter 隔离、`UnsatisfiedLinkError`/`LinkageError` 稳定分类、原始
  throwable 重新抛出；
- 启动事件 schema v2 增加受限 `failureClass`，codec 保持对 v1 本地记录的读取兼容；示例 uploader 将其
  作为可选字段发送并升级 `schemaVersion`；
- `jiagu-core` 显式加入 16 KB `max-page-size` 和 `common-page-size` 链接参数；
- `JNI_OnLoad` 对 `ProxyApplication` 查找和 `RegisterNatives` 失败清除 pending exception 并记录固定 native
  诊断点；
- 已执行 JVM 单测、app Java 编译、插件单测，构建 release APK，并对四个 ABI 的 core ELF 及最终 APK
  执行 readelf/zipalign 检查。

下列项需要服务端协议、真实 API 29–36 设备/ROM、Play Integrity 或发布环境共同验证，未以静态代码“完成”
代替验收：签名校验 Java/native 收敛、hidden-API 去除、启动链网络异步化、AAB device split 门禁任务、
API 29–36 真机矩阵、低存储/16 KB 页实机和线上灰度。它们仍按第 3.3、3.4、3.5 和第 5 节的验收标准执行。
