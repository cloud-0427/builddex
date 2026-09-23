# Payload 白名单与第三方 Provider 稳定启动设计

## 1. 背景与目标

当前 Jiagu 在 `ScopedArtifact.CLASSES` 收到全部业务和运行时依赖，并以包名前缀把少量启动依赖留在 Shell，其余 class 都转换为加密 Payload。这个策略把大量第三方库送入业务 D8：在示例工程中，App 自研 class 只有 6 个，但业务 D8 输入约为 5,832 个 class。

这带来两个问题：

1. 每次业务输入变化都需要对大批第三方 class 执行一次额外的 D8；
2. Provider、AndroidX Startup、反射、JNI、SPI 等第三方启动链路被拆到 Shell/Payload 两侧时，边界很难审计。

本设计将边界改为**正向白名单**：只把明确归属应用/自研模块的包放入 Payload，第三方依赖默认保留在 Shell。目标是保护应用核心逻辑，同时使第三方 Android 组件在 APK 的常规类路径中稳定可用。

本设计只改变代码归属与构建校验，不改变 Payload 的 DEX 格式、加密协议或运行时的解密注入机制。

## 2. 结论

推荐的默认边界如下：

```text
Payload（加密）
  - 明确列入 payloadIncludePackages 的应用业务包
  - 明确列入 payloadIncludePackages 的自研 library/module 包

Shell（APK 常规 classes*.dex）
  - Jiagu Runtime、JNI 入口和 ProxyApplication
  - 所有未命中 Payload 白名单的第三方依赖
  - R 类、资源相关 class、Manifest 声明的启动期 class
  - 配置的启动上传器、其启动依赖，以及用户显式指定的 shellKeepPackages/classes
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

本设计还要求新增下列 R8 保护属性，避免业务方打开混淆后被插件静默忽略：

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `payloadR8Policy` | `Property<String>` | `fail` | 当 Variant 启用 `minifyEnabled=true` 且 Payload 非空时的策略：`fail`、`preprocessedOnly`、`r8WithAppRules`。|

### 3.2 匹配及优先级

class entry 使用 slash 名称匹配，DSL 使用点分包名；构建时统一正规化。匹配优先级从高到低：

1. Jiagu 固定 Shell class（`io.github.xjc.jiagu.**`、R 类、Shell 必需依赖）；
2. Manifest 启动期 class 与其显式元数据 class；
3. `shellKeepClasses`、`shellKeepPackages`；
4. `payloadIncludePackages`；
5. allowlist 模式的默认值：Shell；legacy 模式的默认值：Payload。

同一 class 命中 Shell 与 Payload 规则时，Shell 胜出。`startupComponentPolicy=validate` 记录原因；`fail` 在用户显式将启动 class 纳入 Payload 时失败，避免静默改变加固范围。

空白名单的 allowlist 模式必须失败，而不是产生“没有业务代码”的成功构建。包模式应只允许合法 Java 包路径和结尾 `.*` / `.**`，拒绝模糊的任意文件 glob。

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

Payload 生成后，继续以最终业务 DEX 为 source、Shell JAR 为 target 运行 `TraceReferences`，生成 Shell keep rules。这样 Payload 对第三方 Shell class 的直接类型、方法、字段及继承关系不会被 Shell R8 重命名或裁剪。

### 5.2 动态引用

`TraceReferences` 无法发现下列边：

- `Class.forName`、反射字段/方法名；
- JNI 的 `FindClass`、注册方法和 class-name 字符串；
- `ServiceLoader`、`META-INF/services`；
- AndroidX Startup 的 metadata Initializer；
- XML layout、Navigation、DataBinding、序列化框架、WebView/插件化配置中的类名。

设计要求：

1. 将服务描述文件合并，并为接口、Provider 实现生成 keep rules；
2. 为 Manifest Provider / Factory / Initializer 及其构造器生成 `-keep,allowaccessmodification`；
3. 用户仍须保留 SDK 官方 consumer rules 和 App 自身 R8 rules；
4. 对 `shellKeepClasses` 生成不改名、不裁剪规则；对 `shellKeepPackages` 生成最小可行 keep 规则，避免只移动 class 却被 Shell R8 删除；
5. 在 Release 验证中扫描常见 class-name 字符串，作为诊断，不把启发式结果自动移动到 Shell。

### 5.3 已混淆或预编译 SDK

第三方默认在 Shell 后，AGP Shell R8 可以处理它们。对于预混淆 SDK，必须保留 SDK 提供的 consumer rules；对于 Payload 引用的 ABI，自动生成的 TraceReferences keep rules 优先保证其描述符稳定。若 SDK 使用反射而未提供 keep rules，构建应输出明确的归属、规则来源和缺失诊断。

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
4. Payload 对 Shell 的静态引用必须有对应 keep rule，且 Shell R8 输出中仍存在目标定义；
5. `META-INF/services` 合并结果与 Provider 实现的 keep rule 必须一致；
6. 解包最终 APK，验证 Shell 含必要 class；解析 Payload，验证仅含命中白名单的业务 class；
7. 运行 instrumentation 启动测试：至少覆盖冷启动、Provider 查询、AndroidX Startup、深链 Activity、WorkManager（若接入）和反射/JNI smoke test。

`startupComponentPolicy=fail` 时，以上 2、3、4、5 任一失败均阻断 Release。Debug 的 `validate` 可提供完整报告，但不得把无法加载的 Provider 降级为警告。

## 7. 性能与安全边界

白名单模式会使 Jiagu 的业务 D8 输入从“自研 + 大量第三方”下降到“自研业务包”。但这不会让第三方 class 的 APK DEX 编译凭空消失：它们会在 Shell 的 AGP D8/R8 阶段处理。

因此预期收益是：

- Payload 构建、加密、摘要和运行时解密体积下降；
- 业务 D8 的额外全量成本显著下降；
- 第三方 Provider 与启动 SDK 的类路径更接近未加固 APK；
- 第三方代码不再被 Payload 加密保护，且 Shell DEX 可能变大；
- 若仍把整个 Shell 产为单一 JAR，修改自研 class 仍可能使下游 Shell dex 失去细粒度增量。后续应独立设计 dex archive 复用，不能把“移到 Shell”误认为完整的构建性能方案。

加固边界应由资产价值决定：认证、算法、核心业务规则和自研协议实现应在 Payload；公开 SDK、UI 支撑库和平台集成库可在 Shell。不要仅为追求最小 Payload，把业务启动所必需的自研 bootstrap 强行加密。

## 8. 迁移步骤

1. 保持 `payloadSelectionMode = "legacy"`，生成一次 class-routing 审计报告；
2. 统计自研 applicationId/package 与自研 module 包，建立 `payloadIncludePackages` 初稿；
3. 先在 Debug 开启 `allowlist + startupComponentPolicy=validate`，对比加壳前后的合并 Manifest、Provider 和启动日志；
4. 若 Release 使用 `minifyEnabled=true`，先选择并验证 `preprocessedOnly` 或实现 `r8WithAppRules`；在此之前保持 `payloadR8Policy=fail`；
5. 显式补齐 SDK 的反射/JNI/Initializer 白名单与 keep rules；
6. 运行冷启动和组件测试矩阵，再在 Release 使用 `startupComponentPolicy=fail`；
7. 通过后再将项目默认模式切换为 allowlist。插件不得在升级时自动改变既有项目的选择模式。

## 9. 验收标准

1. 未命中 `payloadIncludePackages` 的第三方 class 全部进入 Shell；
2. 自研白名单 class 全部进入 Payload，除被启动规则强制留 Shell 的明确例外；
3. 最终 Manifest 的 Provider 不被通用清理逻辑删除；
4. 所有 Provider / Factory / Startup Initializer 在最终 APK Shell 中可解析；
5. Shell 与 Payload 不存在重复 class；
6. Payload 可以解析并调用 Shell 的第三方库，Shell 启动组件可以在 Payload 注入后访问业务 class；
7. Release 在 API 29、目标设备 API、冷启动和首次安装场景通过组件测试；
8. `minifyEnabled=true` 且 Payload 非空时，构建报告必须明确列出 payloadR8Policy、规则来源、mapping 路径和 R8 版本；
9. 构建报告显示 Payload class 数、Shell class 数、路由原因、Payload 大小和 D8 阶段耗时，便于持续观测。
