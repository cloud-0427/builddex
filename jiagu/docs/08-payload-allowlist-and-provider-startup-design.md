# Payload 白名单与第三方 Provider 稳定启动设计

## 1. 背景与目标

当前 Jiagu 在 `ScopedArtifact.CLASSES` 收到全部业务和运行时依赖，并以包名前缀把少量启动依赖留在 Shell，其余 class 都转换为加密 Payload。这个策略把大量第三方库送入业务 D8：在示例工程中，App 自研 class 只有 6 个，但业务 D8 输入约为 5,832 个 class。

这带来两个问题：

1. 每次业务输入变化都需要对大批第三方 class 执行一次额外的 D8；
2. Provider、AndroidX Startup、反射、JNI、SPI 等第三方启动链路被拆到 Shell/Payload 两侧时，边界很难审计。

本设计将边界改为**基于来源和用途的三路分流**：应用业务进入 Payload；第三方依赖留在 Shell 且不作为 R8 program input；Jiagu Runtime 留在 Shell 并按 Runtime 专属规则进入 R8。目标是保护应用核心逻辑，使第三方 Android 组件在 APK 常规类路径中稳定可用，同时避免对第三方类做二次裁剪、优化、改名或重打包。

本设计改变代码归属及字节码处理流水线，不改变 Payload 的 DEX 格式、加密协议或运行时解密注入机制。当前实现已在 Shell 内进一步拆分 Runtime-only R8 与第三方 pass-through D8；online 模式仍使用 AGP Variant 自身配置处理 Shell，Runtime-only 分流适用于 local 模式。

## 2. 结论

推荐的目标边界如下（第三方选择性进入 R8 不属于本阶段支持范围）：

```text
Payload（加密；只做 D8，不做 R8）
  - 应用业务模块中明确指定的业务 class
  - 明确指定的自研业务 library/module class

Shell R8 lane（APK 常规 classes*.dex）
  - Jiagu Runtime、JNI 入口、ProxyApplication
  - 插件内部 Runtime allowlist 命中的 Jiagu 自有 class
  - 对 Runtime/Payload/未选中依赖的共享 ABI 应用自动 keep/边界校验

Shell pass-through lane（APK 常规 classes*.dex）
  - 默认所有第三方 AAR/JAR 依赖
  - 其它明确要求保持原样的自研 SDK
  - class 只经过 D8 的 classfile→DEX 转换，不进入 R8 program set
  - Manifest Provider/Startup 等组件按启动边界留在此 lane；是否允许 Runtime lane 取决于明确的模块归属和组件时序

资源/Native lane（AGP 标准处理）
  - 第三方与自研 AAR 的 res/assets/manifest/native inputs 继续由 AGP 合并
  - 不因 class lane 的 R8 策略自动改写资源；资源裁剪、资源重命名和 Native 重打包分别由独立开关控制
```

`Payload` 注入发生在 `ProxyApplication.attachBaseContext()`。Android 的 Provider 在 `Application.attachBaseContext()` 之后、`Application.onCreate()` 之前初始化；因此 Payload 在正常路径上已经可见。但 Provider 属于系统启动边界，不能仅依赖这一时序作为唯一保障：其 class、Manifest 元数据、反射初始器和 R8 keep 规则均应留在 Shell 并被显式验证。

## 3. DSL

### 3.1 新 DSL

```groovy
dexReport {
    // "legacy"：保持现有“除 Shell 白名单外均进入 Payload”的行为。
    // "allowlist"：仅 include 的包进入 Payload；推荐新项目和完成迁移的项目使用。
    payloadSelectionMode = "allowlist"

    // Java 包名 glob；仅允许包边界，不接受单个 class 作为 Payload include。
    payloadIncludePackages = [
        "lows.dgeon.ightr.jiagu.**",
        "com.example.feature.**"
    ]

    // 即使命中 include 也必须留 Shell 的启动/兼容代码。可接受包或单一 class。
    shellKeepPackages = ["com.example.feature.bootstrap.**"]
    shellKeepClasses = ["com.example.feature.LegacyContentProvider"]

    // "validate" 为推荐默认值；"fail" 把所有边界警告升级为构建失败；
    // "off" 仅限排障，不能用于 Release。
    startupComponentPolicy = "validate"
}
```

对应扩展类型：

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `payloadSelectionMode` | `Property<String>` | `legacy` | `legacy`、`allowlist` 两种模式。保留 legacy 避免升级插件后意外降低加固范围。|
| `payloadIncludePackages` | `SetProperty<String>` | 空 | allowlist 模式必填；包名 glob，例如 `com.example.**`。|
| `shellKeepPackages` | `SetProperty<String>` | 空 | 强制进入 Shell 的包 glob，优先级高于 Payload include。|
| `shellKeepClasses` | `SetProperty<String>` | 空 | 强制进入 Shell 的完整类名。|
| `startupComponentPolicy` | `Property<String>` | `validate` | 启动组件冲突与无法解析项的处理方式。|

Payload 的业务范围继续由业务方 `payloadIncludePackages` 配置；Jiagu Runtime R8 范围不交给接入工程配置，而由插件源码中的内部 Runtime package allowlist 固定，例如 `io.github.xjc.jiagu.**`。该命名空间是 Jiagu 自有、保留的包空间。其余第三方依赖默认留在 Shell，保持 classfile 后只交给 AGP D8。本阶段不支持“第三方留在 Shell、但选择性进入 R8”，也不新增 `shellR8Dependencies` 或按包名选择第三方 R8 的 DSL。此能力作为后续需求保留；未来需先验证完整依赖关系、consumer rules、反射/JNI/SPI 元数据和处理边界，另行评审兼容性风险。

Runtime allowlist 是插件内部实现常量/策略，不新增工程侧 DSL，不随 `buildTypes` 覆盖。若将来确需把其他 Jiagu 自有 Shell 模块纳入 Runtime R8，应由插件维护方审查后扩展内部名单，不要求业务工程逐项维护。插件对 namespace 冲突做校验：非 Jiagu 来源的 class 若占用保留的 `io.github.xjc.jiagu.**` 前缀，构建应报错，不能误将其纳入 Jiagu R8。

本设计还要求新增下列 R8 保护属性，避免业务方打开混淆后被插件静默忽略：

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `payloadR8Policy` | `Property<String>` | `fail` | 当 Variant 启用 `minifyEnabled=true` 且 Payload 非空时的策略：`fail`、`preprocessedOnly`、`r8WithAppRules`。|

### 3.2 匹配及优先级

当前两路实现中，class entry 使用 slash 名称匹配，DSL 使用点分包名；构建时统一正规化。现有路由优先级从高到低：

1. Jiagu 固定 Shell class（`io.github.xjc.jiagu.**`、R 类、Shell 必需依赖）；
2. Manifest 启动期 class 与其显式元数据 class；
3. `shellKeepClasses`、`shellKeepPackages`；
4. `payloadIncludePackages`；
5. allowlist 模式的默认值：Shell；legacy 模式的默认值：Payload。

同一 class 命中 Shell 与 Payload 规则时，Shell 胜出。`startupComponentPolicy=validate` 记录原因；`fail` 在用户显式将启动 class 纳入 Payload 时失败，避免静默改变加固范围。

空白名单的 allowlist 模式必须失败，而不是产生“没有业务代码”的成功构建。包模式应只允许合法 Java 包路径和结尾 `.*` / `.**`，拒绝模糊的任意文件 glob。

目标把“放在哪里”与“交给哪个处理器”拆开。处理器归属优先级为：Jiagu 内部 Runtime namespace → Runtime R8；命中业务方 `payloadIncludePackages`（且不是 Jiagu Runtime namespace）→ Payload D8；其余 Shell class → 未修改 classfile，由 AGP D8 转换。所有第三方依赖均默认 pass-through，不因包名或依赖坐标而单独进入 R8。启动组件规则可把业务 class 的**位置**强制改为 Shell，但默认作为 pass-through classfile；Runtime 内部启动入口留在 Runtime R8 并由插件内置规则 keep。`shellKeepPackages/classes` 只影响 destination，不能单独决定 R8 processor，也不能作为免 R8 或免 D8 的开关。

## 3.3 与业务方 R8 的兼容策略

这里必须区分两种常被统称为“指定 R8”的情形：

1. **业务 App Variant 设置 `minifyEnabled=true`。** Jiagu 在 `ScopedArtifact.CLASSES` 对 class 做切分；Payload class 被取走后，AGP 后续 R8 只能处理 Shell JAR。因此，不能假设业务方的 ProGuard 文件、consumer rules、mapping、裁剪和重命名会自动作用到 Payload。
2. **某个第三方 AAR/JAR 已由其提供方预混淆。** 该 artifact 已经带着上游 R8 的 class 名和字节码进入 Jiagu。它默认留在 Shell，或在明确允许的预处理模式下仅做 D8；不得由 Jiagu 再根据猜测的规则重命名。

`payloadR8Policy` 的语义如下：

| 策略 | `minifyEnabled=true` 且 Payload 非空时 | 适用场景 |
| --- | --- | --- |
| `fail`（默认） | 构建失败并提示 Payload 包与 Variant 名称 | 当前安全基线；避免“业务以为已混淆，Payload 实际未混淆”。|
| `preprocessedOnly` | 只接受带可验证上游 R8 产物与 mapping 的 Payload 输入；否则失败 | 自研模块已在独立上游流水线完成 R8 的组织。|
| `r8WithAppRules` | Jiagu 为 Payload 显式运行 R8，并使用完整的 Variant 规则、consumer rules、desugaring 配置、classpath 和 mapping | 后续独立实现的高级模式，不能仅复用当前 D8 任务。|

`r8WithAppRules` 必须将 Shell/Payload 看作两个独立的 R8 程序域：生成 Payload mapping，并对 Payload→Shell 静态 ABI 使用 keep rules；反射/JNI/SPI 仍由显式规则处理。它不应与 Shell R8 共用或覆盖 mapping 文件。该模式完成前，`fail` 是唯一不会误导业务方的默认行为。

### 3.4 第三方 pass-through 与 Runtime-only R8（本次新增目标）

#### 所有权与处理约束

| 来源组 | 最终位置 | 字节码处理 | R8 身份 | 目标效果 |
| --- | --- | --- | --- | --- |
| App 业务 class | 加密 Payload | Jiagu D8 → Payload DEX | 不得作为 Shell R8 program input | 保持选定的业务符号描述符；Payload 加解密路径不变 |
| Jiagu Runtime class（内部保留 package allowlist 命中） | Shell | 专属 R8 program input → optimized classfile；与 pass-through classfile 合并后由 AGP D8 → DEX | 插件代码写死的唯一默认 Shell R8 program group | 只依据 Jiagu Runtime 规则、JNI/Manifest/共享 ABI 自动规则裁剪及混淆；无需接入工程配置 Runtime 名单 |
| 外部第三方 AAR/JAR（全部） | Shell | 原始 classfile 与 Runtime R8 classfile 合流后由 AGP D8 → DEX；允许必需的 Android desugaring | R8 仅作为 `library`/解析 classpath（只读），不得作为 R8 program input | 不做 Jiagu R8 tree shaking、优化、重命名、repackage；本阶段不支持第三方 R8 opt-in |
| Android 平台/boot classpath | 不打入 app DEX | R8/D8 library input | library | 按 Android 平台提供 |

这里“第三方不走 R8”定义为：第三方 class 不得成为 Jiagu Runtime R8 的 program input，也不得出现在 Runtime R8 output 来源集合中。不能用“为每个 SDK 自动生成 `-keep`”冒充完全隔离；自动 keep 仍让第三方进入 R8 program pipeline，不能满足严格的来源隔离目标。

第三方 pass-through 最终仍需经过 D8，因为 Android APK 执行的是 DEX，不是 JVM classfile。当前实现不是为第三方单独运行 D8：Runtime R8 classfile 与原样 pass-through classfile 合并回 AGP `ScopedArtifact.CLASSES`，由 AGP 对整个 Shell classes 统一执行 D8/desugaring 并生成 DEX。D8 转换可能进行 Android 必需的 desugaring，并生成 synthetic/companion class；目标是保持外部可观察语义、类名、成员名、注解/泛型/反射元数据、服务声明和资源关联，而不是保证输出字节与输入 class/JAR 二进制一致。已经被 SDK 上游混淆的名称当然仍保持其上游映射结果。

AGP D8 使用同一 Variant 的 `minSdk`、Java/Kotlin bytecode level、desugaring 和 core-library-desugaring 配置、boot classpath 及相关 toolchain 版本。Runtime R8 仅产出 classfile，不承担独立 DEX/desugaring；构建审计应验证回接后的完整 Shell classfile 集合由该 Variant 的 AGP D8 正常编译。

#### 内部 allowlist 与处理拓扑

```text
AGP class inputs
        |
        +-- payloadIncludePackages matches --------------> Jiagu D8 --> encrypted Payload DEX
        |
        +-- io.github.xjc.jiagu.** (internal allowlist) --> isolated R8 --> Runtime classfile --+
        |                                                                                       +--> AGP D8 --> Shell DEX
        +-- every remaining Shell class -----------------> unchanged classfile ----------------+
        |
        +-- AAR resources/assets/manifest/native --------> normal AGP merge/package
                                                            (no Jiagu rewrite by default)
                                                                 |
                           Runtime classfile + pass-through --+--> AGP D8 --> Shell DEX/package
```

默认 R8 选择不需要为每个工程解析和维护第三方坐标名单：插件源码中的 Runtime package allowlist 自动选中 Jiagu Runtime，其余组件默认 pass-through。当前不通过 Gradle component provenance 识别第三方 R8 opt-in。`ScopedArtifact.CLASSES` 聚合输入逐 class 扫描，用于拆分 Runtime、Payload 和 pass-through 的归属。设计要求如下：

1. 从聚合 classes 输入中拆分 Payload、Runtime R8、pass-through 三个处理组；Runtime 名单只存在于插件源码；
2. 第三方来源信息可用于审计和诊断，但不用于将第三方选入 Jiagu R8；
3. 检测非 Jiagu class 对保留 namespace `io.github.xjc.jiagu.**` 的占用；报告 component ID/artifact path 与 class，不能误将第三方纳入 Jiagu Runtime R8；
4. 所有非 Runtime Shell classes（第三方、AndroidX、其它 SDK 或未进入 Payload 的类）保持 classfile 不变，最终随 AGP D8 编译；无需知道或枚举依赖名；
5. App 业务代码由现有 `payloadIncludePackages` 决定 Payload；Runtime namespace 优先保持在 Shell，不能被业务 Payload allowlist 抢走；冲突配置直接报错；
6. R8 lane 的 program inputs 仅包括 Jiagu Runtime internal allowlist；任何第三方绝不能成为 Jiagu R8 program input；R8 输出 classfile 与 pass-through classfile 一并交给 AGP D8；
7. AGP 公开 `ScopedArtifact.CLASSES` API 没有提供独立 DEX append/merge artifact。因此使用受支持的 classfile 接入点：在 Jiagu transform 内对 Runtime 独立运行 R8 classfile backend，将输出和未改动的 Shell pass-through classfile 合并回 classes artifact，再由 AGP 正常 D8 与打包。不得使用私有 task 或中间目录注入 DEX；
8. Runtime mapping 单独输出/归档；不得覆盖业务上游 mapping 或假称 pass-through 第三方有 Jiagu mapping。

Runtime R8 输出 classfile 与 pass-through classfile 合流前必须扫描 class descriptor 冲突；任何冲突不得通过随意重命名第三方 class 化解。AGP D8 统一处理合并后的 Shell classfile，并负责需要的 desugaring 与 dex 输出。

#### Runtime R8 规则隔离

- Jiagu Runtime 的 `consumer-rules.pro`、JNI/Manifest 保留规则与 Payload→Runtime 的静态 ABI keep rules必须应用于 Runtime R8。
- 第三方 AAR 的 consumer rules 面向消费方应用的正常 whole-program R8；当 SDK 已被标记为 pass-through 时，不应将它们作为改变第三方 DEX 的依据。若其中规则是为 Runtime 反射调用第三方 API 所必需，应转化为明确的 Runtime→third-party ABI 验证/保留需求，不得因此把第三方改为 R8 program input。
- App `proguardFiles` 需要明确作用域：默认的业务 App 规则不应意外扩大到 `:jiagu-runtime` R8；新增 `runtimeR8Files`（或等效配置）作为 Runtime lane 的显式用户扩展规则。Jiagu 内部安全规则不可被业务规则覆盖。
- R8 对 Runtime 做重命名时，只允许 Jiagu 控制的命名策略。由于 Runtime 与第三方最终位于同一 APK classloader 中，必须对 descriptor collision、反射字符串、JNI 注册名和公开 API ABI 进行验证。

#### 第三方资源及 metadata 保持原样

字节码隔离本身不能阻止资源层面的变更。第三方 AAR 的资源、assets、Manifest、`META-INF/services`/consumer metadata 和 `.so` 仍由 AGP 常规合并与打包；为了尽量保持第三方行为不变：

1. local baseline 默认不运行 Jiagu `ResObfuscatorTask`（`resObfuscationEnabled=false`）；
2. `shrinkResources`、`android.defaultConfig.resConfigs`、locale filters 是独立的 Android DSL，分别保持关闭/未过滤；如用户主动开启，必须明确它会影响第三方资源；
3. 不删除第三方 Provider/Startup metadata，不重写其 Manifest 节点；冲突按 AGP/manifest merger 明确报错或由用户提供合并规则；
4. `META-INF/services` 必须合并并在 pass-through D8/打包后仍能被 `ServiceLoader` 找到；其资源路径和内容作为验证对象；
5. Native `.so` 继续标准 `lib/<abi>/` 打包，不放入 Payload、不释放重定向；ABI splits/过滤仍由 Android DSL 显式控制。

#### 必须保留的第三方兼容信息

pass-through 不等于跳过 Android packaging。拆分/重组需要保留并校验：Runtime-visible annotations、Signature/generic metadata、InnerClasses/EnclosingMethod、nestmate 信息（如适用）、Kotlin metadata、服务文件、多发行版 AAR 的 `classes.jar`/额外 JAR、JNI/native 配套库、Manifest Provider/Initializer 元数据、资源引用及 consumer artifact 资源。签名文件（`META-INF/*.SF`/`.RSA`）不能照搬到聚合结果造成伪签名；这属于标准 DEX/package 转换的清理，不代表改写 SDK API。

#### 第三方 R8 选择性处理：暂不支持

当前设计不支持通过 `group:name`、包名前缀或其它配置让第三方留在 Shell 并进入 Jiagu R8。所有第三方均按 pass-through D8 处理。该限制是有意的兼容性边界，不代表第三方 R8 选择性处理不可能；它作为后续需求保留，未来必须基于完整依赖关系、consumer rules、反射/JNI/SPI 元数据和 DEX 合流能力另行评审，不纳入本阶段 DSL、实现及验收范围。

## 4. Provider 与启动期组件

### 4.1 必须保留的 Manifest 信息

不能再以“加壳后可能出问题”为理由，无条件删除所有第三方 Provider。`ManifestTransformerTask` 应先读取合并 Manifest，建立启动组件清单，再替换 `Application`：

| 来源 | Shell 处理 | 备注 |
| --- | --- | --- |
| `<application android:name>` | Manifest 改为 `ProxyApplication`；原 Application 写入 `REAL_APPLICATION` | 原 Application 可留在 Payload；由 Runtime 在注入完成后绑定。|
| `<provider android:name>` | Provider class 强制 Shell，并保留节点 | 防止系统在 Provider 实例化时找不到 class。|
| Provider 的 `<meta-data android:value/resource>` | 保留；`android:name` 若为 class 名则作为启动元数据 class 审计 | AndroidX Startup initializer 属于此类。|
| `android:appComponentFactory` | 默认不删除，解析其 class 并强制 Shell；不支持时应失败并给出迁移说明 | 删除会改变 AppCompat/AndroidX 的组件构造语义。|
| `<instrumentation>`、自定义 `BackupAgent`、`Application` 直接引用的 loader | 强制 Shell 并审计 | 它们可能早于或紧邻 Payload 注入运行。|
| `<receiver>`、`<service>`、`<activity>` | 默认按 DSL 归属；若声明为 direct-boot-aware、导出启动接收器，或被显式标为 bootstrap，则强制 Shell | 它们通常在 attach 后加载，不应无谓扩大 Shell。|

相对类名（`.Foo`）、无前缀类名和完整类名必须按 manifest package 正确展开。`android:name` 以 `${applicationId}` 等 placeholder 表达时，应使用 AGP 合并后的最终 Manifest；无法解析时在 `validate` 记录、在 `fail` 失败。

### 4.2 AndroidX Startup 与 Profile Installer

现有逻辑会删除 `androidx.startup.InitializationProvider` 和 `ProfileInstallReceiver`。新设计必须替换为策略化处理：

- `InitializationProvider` 默认保留在 Shell；其 `<meta-data android:value>` 指向的 Initializer class 应强制 Shell，或在 `startupComponentPolicy=fail` 下要求用户显式确认其归属；
- 不能仅因其存在就删除 Provider。删除会使 WorkManager、EmojiCompat、第三方 SDK 等依赖 Startup 的功能失效；
- Profile Installer 的移除只能由单独的、默认关闭的兼容开关控制，并应记录移除原因。它不得与通用 Provider 策略绑定；
- 若某 SDK 的 Initializer 通过字符串、资产或 JNI 间接加载其它 class，用户必须通过 `shellKeepPackages/classes` 明确覆盖，并由验证任务报告未覆盖的字符串类名候选。

### 4.3 运行时保证

`ProxyApplication.attachBaseContext()` 的顺序必须保持：`super.attachBaseContext` → 加载 Native Core → 解密/校验 Payload → 注入 class loader。任何 Provider 初始化之前，`nativeAttach()` 必须已经成功；失败必须中止进程，禁止半初始化后继续执行 Provider。

Provider 在 Shell 后仍可调用 Payload 业务逻辑，因为注入后的应用 class loader 同时包含 Payload 与 Shell。反向依赖也成立：Payload 业务代码可静态引用 Shell 中的第三方 class。唯一禁止的是同一 class descriptor 在两侧重复定义。

## 5. R8、反射、JNI 与 SPI

### 5.1 静态引用

Payload 生成后，以最终业务 DEX 为 source 检查其对 Runtime 和第三方的静态引用。Payload→Runtime 的 ABI 需要为 Runtime R8 生成精确 keep rules；Payload→第三方 pass-through 的 ABI 不需要 keep 才能阻止改名（它不进入 R8 program set），但必须在 DEX merge 前验证目标 class/member 存在且 descriptor 一致。Runtime→第三方的 classpath 引用在 Runtime R8 中作为 library reference 解析，同样不得把第三方升级成 program input。

### 5.2 动态引用

`TraceReferences` 无法发现下列边：

- `Class.forName`、反射字段/方法名；
- JNI 的 `FindClass`、注册方法和 class-name 字符串；
- `ServiceLoader`、`META-INF/services`；
- AndroidX Startup 的 metadata Initializer；
- XML layout、Navigation、DataBinding、序列化框架、WebView/插件化配置中的类名。

设计要求：

1. 将服务描述文件合并，并检查接口、Provider 实现均来自 pass-through DEX 或 Runtime R8 输出；Runtime 若被反射加载才生成 Runtime keep rules；
2. 为 Manifest Provider / Factory / Initializer 校验最终所属 lane 和构造器可见性，不允许被错误路由到加密 Payload 后在 Provider 初始化时缺失；
3. 第三方 consumer rules 保留在 provenance/report 中，但默认不交给第三方 D8，也不作为第三方 R8 keep（第三方并非 R8 program）；Runtime R8 只使用 Jiagu Runtime rules 和显式 `runtimeR8Files`；
4. `shellKeepClasses` / `shellKeepPackages` 只影响 destination。Runtime R8 lane 内的类是否 keep 由 R8 rules 决定；pass-through lane 内的类天然不经 R8，不能再用 shellKeep 名称暗示 R8 keep；
5. 在 Release 验证中扫描常见 class-name 字符串，作为诊断，不把启发式结果自动移动到 Shell R8。

### 5.3 已混淆或预编译 SDK

第三方默认进入 Shell pass-through D8，不由 AGP Shell R8 改写。预混淆 SDK 保留上游名称；consumer rules 随 artifact provenance 存档并用于诊断，不应因默认规则而改变第三方类。若 Jiagu Runtime 依赖某 SDK 的反射/JNI API，应检查 Runtime 自身 keep 与 SDK API descriptor；缺少 ABI 时构建给出组件和 class 级诊断，不通过重混淆该 SDK 来“修复”。

业务方 `minifyEnabled=true` 不能被当作“Payload 已经混淆”的证据。只有 `preprocessedOnly` 的可验证上游输入，或真正实现 `r8WithAppRules` 后，才允许在该 Variant 中产出非空 Payload。

## 6. 构建与校验

### 6.1 产物和审计

`input-index.json` 升级为每个 class / artifact 可追踪的归属审计，而不仅是 R8 证据：

```json
{
  "class": "com.example.feature.LoginManager",
  "origin": "project :feature-login",
  "destination": "PAYLOAD",
  "reason": "payloadIncludePackages: com.example.feature.**"
}
```

对于体积较大时，完整 class 级清单可输出为独立 `class-routing.jsonl`；`input-index.json` 保留 artifact 汇总、class 数、Payload/Shell 数和规则命中统计。

### 6.2 必须执行的验证

新增 `verifyJiaguStartupBoundary<Variant>`，在 `assemble<Variant>` 前执行：

1. Shell/Payload class descriptor 交集必须为空；
2. 每个最终 Manifest Provider、Factory、Startup Initializer 和显式启动 class 必须存在于 Shell；
3. 保留的 Provider 节点、authority 和 metadata 必须与加壳前合并 Manifest 一致，允许的 `Application` 与 Jiagu metadata 变更除外；
4. Payload 对 Runtime 的静态引用必须有对应 keep rule，且 Runtime R8 输出中仍存在目标定义；Payload 对 pass-through Shell 的引用通过 descriptor/成员链接校验；
5. `META-INF/services` 合并结果与 Provider 实现的 keep rule 必须一致；
6. 解包最终 APK，验证 Shell 含必要 class；解析 Payload，验证仅含命中白名单的业务 class；
7. 运行 instrumentation 启动测试：至少覆盖冷启动、Provider 查询、AndroidX Startup、深链 Activity、WorkManager（若接入）和反射/JNI smoke test。

`startupComponentPolicy=fail` 时，以上 2、3、4、5 任一失败均阻断 Release。Debug 的 `validate` 可提供完整报告，但不得把无法加载的 Provider 降级为警告。

## 7. 性能与安全边界

三路分流会使 Jiagu Payload D8 只处理业务包，Jiagu Runtime 进入小范围专属 R8，第三方 SDK 作为 pass-through classfile 与 Runtime R8 输出一起由 AGP D8 转换。第三方 class 的 DEX 转换成本不会消失，但避免它们进入 R8 tree shaking/optimization/obfuscation program pipeline，并避免 Runtime R8 规则无意改变外部依赖。

因此预期收益是：

- Payload 构建、加密、摘要和运行时解密体积下降；
- 业务 D8 的额外全量成本显著下降；
- 第三方 Provider 与启动 SDK 的类路径更接近未加固 APK；
- 第三方代码不再被 Payload 加密保护，且 Shell DEX 可能变大；
- Runtime R8 必须仅以 Runtime classfile 为 program input；其输出 classfile 与原样 pass-through Shell classfile 经公开 `ScopedArtifact.CLASSES` 接回 AGP，随后统一由 AGP D8 生成 Shell DEX。不得把整个 Shell JAR 再交给 R8，也不得通过私有中间目录注入 DEX。

加固边界应由资产价值决定：认证、算法、核心业务规则和自研协议实现应在 Payload；公开 SDK、UI 支撑库和平台集成库可在 Shell。不要仅为追求最小 Payload，把业务启动所必需的自研 bootstrap 强行加密。

## 8. 迁移步骤

1. 保持现有 Payload routing 行为，先生成 class→artifact 来源审计；在 Jiagu 插件内部定义并测试 Runtime package allowlist（接入工程无需添加 R8 模块配置）；
2. 通过固定 Runtime allowlist 将 Jiagu 自有 class 选入 Runtime R8；Payload 继续由 `payloadIncludePackages` 选择；Shell 中其余 class 自动进入 pass-through；
3. 先验证第三方 artifacts 没有成为 R8 program input，且只经过 D8；证明 AAR transform、multi-JAR、desugaring 和 duplicate classes 不会破坏 class 分流；
4. 已完成 AGP Artifact API spike：公开 API 没有独立 DEX append artifact，因此采用 Runtime R8 classfile backend，通过公开 `ScopedArtifact.CLASSES` 回接，再由 AGP D8 统一产出 DEX；无私有 task/目录依赖；
5. 先在 Debug 对比最终 class descriptor、Manifest、Provider、`META-INF/services`、资源/native 内容及启动日志；
6. 验证 Jiagu Runtime R8 mapping 与 keep 规则；对 Runtime→第三方 library references 和 Payload→Shell ABI 执行静态链接检查；
7. 运行冷启动、Provider、反射/JNI/SPI、资源访问及组件测试矩阵，再在 Release 使用 `startupComponentPolicy=fail`；
8. 三路分流已接入 local pipeline；后续推广或调整旧项目 routing 时不得静默切换既有 Payload 范围。

## 9. 验收标准

1. 未命中 `payloadIncludePackages` 的第三方 class 全部进入 Shell；
2. 自研白名单 class 全部进入 Payload，除被启动规则强制留 Shell 的明确例外；
3. 最终 Manifest 的 Provider 不被通用清理逻辑删除；
4. 所有 Provider / Factory / Startup Initializer 在最终 APK Shell 中可解析；
5. Shell 与 Payload 不存在重复 class；
6. Payload 可以解析并调用 Shell 的第三方库，Shell 启动组件可以在 Payload 注入后访问业务 class；
7. Release 在 API 29、目标设备 API、冷启动和首次安装场景通过组件测试；
8. 第三方类不得出现在 Runtime R8 program input/output；构建报告列出 pass-through 来源和 class 数，并以输入/回接 classfile 对比证明其未被 Runtime R8 改写；最终仍由 AGP D8 编译；
9. Jiagu Runtime classes 才是默认 Shell R8 program input；Runtime mapping 单独输出，外部依赖只作为 library reference；
10. `minifyEnabled=true` 且 Payload 非空时，构建报告必须明确列出 payloadR8Policy、规则来源、mapping 路径和 R8 版本；
11. 构建报告显示每个 artifact（能解析时包含 Gradle component）的来源、lane、class 数、路由原因、Payload 大小和 D8/R8 阶段耗时，便于持续观测；第三方无需显式配置名单。
