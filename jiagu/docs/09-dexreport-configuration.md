# `dexReport` 配置说明

本文按插件当前实现梳理 `dexReport {}` 配置块，包括在线加固和本地加固的示例、默认值、配置覆盖关系及容易混淆的项目。默认值以 `DexReportPlugin` 的 convention 和任务配置逻辑为准；Android Gradle Plugin（AGP）的 `android {}` 配置不属于 `dexReport`。

## 模式概览

通过 `protectionMode` 选择加固方式：

| 模式 | 默认值 | 行为 |
| --- | --- | --- |
| `online` | 是 | Jiagu 任务使用服务端 URL、企业 ID 和 API Key；服务端参与加固/发布流程。Payload 压缩默认开启。 |
| `local` | 否 | 在本地生成 Payload，不走服务端授权/网络流程；Payload 压缩被插件强制关闭；签名检查和启动日志上传被禁用；默认仅对 Jiagu Runtime 启用 R8。 |

两种模式都使用相同的 Payload 选择配置、构建类型发布开关、资源混淆开关等。某些配置虽然 DSL 中仍可设置，但在某模式下会被忽略或覆盖，详见下表。

## 在线加固示例

```groovy
dexReport {
    protectionMode = "online"
    serverUrl = "https://jg.nebulapro.net/" // 可省略，使用默认 URL
    companyId = providers.gradleProperty("jiaguCompanyId").get()
    companyApiKey = providers.environmentVariable("JIAGU_COMPANY_KEY").get()

    // 推荐显式采用 allowlist，只将自己的业务包路由进加密 Payload
    payloadSelectionMode = "allowlist"
    payloadIncludePackages = ["com.example.app.business.**"]
    shellKeepPackages = ["com.example.app.integration.**"]
    shellKeepClasses = ["com.example.app.Bootstrap"]
    startupComponentPolicy = "validate"
    payloadR8Policy = "fail"

    payloadCompressionEnabled = true
    signatureCheckEnabled = true
    expectedSignature = "<运行时签名校验期望值>"
    certificateSha256Digests = ["<允许的签名证书 SHA-256 Base64URL 摘要>"]
    startupLogUploaderClass = "com.example.app.StartupEventUploader"

    publish = false
    antiDebugEnabled = true
    autoRunBuildTypes = ["release"]
    attachToTask = "assemble"

    resObfuscationEnabled = false
    resConfigs = ["zh", "en"] // 仅资源混淆开启时由 Jiagu 消费

    buildTypes {
        debug {
            publish = false
            antiDebugEnabled = false
        }
        release {
            publish = true
        }
    }
}
```

企业凭据不要提交到版本库；示例通过 Gradle 属性和环境变量读取。使用 `publish = true` 会触发在线发布流程，通常只应在确认发布策略后用于目标构建类型。

## 本地加固示例

```groovy
dexReport {
    protectionMode = "local"
    shellMinificationEnabled = true

    payloadSelectionMode = "allowlist"
    payloadIncludePackages = ["com.example.app.business.**"]
    shellKeepPackages = ["com.example.app.integration.**"]
    shellKeepClasses = ["com.example.app.Bootstrap"]
    startupComponentPolicy = "validate"
    payloadR8Policy = "fail"

    // local 模式不使用在线凭据、服务端发布和线上运行时能力。
    publish = false
    resObfuscationEnabled = false
    autoRunBuildTypes = ["debug", "release"]
}
```

本地模式下，`shellMinificationEnabled` 仅控制 Jiagu Runtime namespace 的独立 R8。R8 输出 classfile 后与未改动的其它 Shell classfile 合并，再由 AGP D8 统一编译；第三方不进入 R8 program。对启用 Jiagu 流水线的 local Variant，插件会关闭 AGP whole-program R8，避免第三方被整体送入 R8；未被 `autoRunBuildTypes` 选中的 Variant 保留 Android DSL 自身配置。Payload 压缩强制关闭；反调试强制关闭；Manifest 签名检查和启动日志上传也强制关闭。`publish` 仍决定本地 release 元数据是否触发发布动作；本地模式没有服务端发布可完成，因此通常设为 `false`。

## 配置逐项说明

“未设”表示插件没有为该属性声明 convention；实际任务若需要该值可能会报 Gradle 缺少属性的错误，或由任务按 optional 逻辑跳过。请按模式要求显式配置在线必需凭据。

| 配置 | 默认值 | 含义与生效范围 |
| --- | --- | --- |
| `protectionMode` | `"online"` | 选择 `online` 或 `local`。大小写不敏感；其他值会在创建 Variant 时失败。 |
| `serverUrl` | `"https://jg.nebulapro.net/"` | 在线服务端地址。local 模式任务不使用服务端，设置此项不会令本地模式联网。 |
| `companyId` | 未设 | 在线企业/租户标识，在线加固需要有效值。local 不使用。 |
| `companyApiKey` | 未设 | 在线服务 API Key。应从环境变量或受保护的 Gradle 属性读取；local 不使用。 |
| `payloadSelectionMode` | `"legacy"` | Payload 类选择策略。`allowlist` 配合 `payloadIncludePackages` 指定业务包；`legacy` 保留兼容的旧选择行为。合法值和匹配语义由 `PayloadRouting` 校验。两种模式通用。 |
| `payloadIncludePackages` | 空集合 | Payload 包名 glob/模式集合，供选择策略使用。使用 `allowlist` 时应列出要加密承载的业务包。 |
| `shellKeepPackages` | 空集合 | 指定必须留在 APK Shell 的包，避免这些包被路由到 Payload。 |
| `shellKeepClasses` | 空集合 | 指定必须留在 APK Shell 的完整类名。 |
| `startupComponentPolicy` | `"validate"` | 启动组件与 Payload 路由冲突的处理策略；在任务校验时使用。 |
| `payloadR8Policy` | `"fail"` | Payload 对 R8 后果的兼容策略；默认遇到不兼容情况失败，避免静默生成不完整的 Payload。 |
| `payloadCompressionEnabled` | `true` | 在线模式控制业务 Payload 压缩。local 模式插件强制设为 `false`，用户设置不生效。 |
| `shellMinificationEnabled` | 未设；local 读取时默认为 `true` | 仅 local 模式读取，控制 Jiagu Runtime-only R8；第三方和其它 Shell class 不进入 R8。online 模式不由该项控制，使用 Android build type 的 AGP 配置。 |
| `signatureCheckEnabled` | `true` | 在线模式写入 Manifest 的运行时签名校验开关。local 强制关闭。 |
| `expectedSignature` | 未设 | 运行时签名校验期望值；与签名证书允许列表不是同一个配置。只有校验开启且提供该值时才写入对应元数据。 |
| `certificateSha256Digests` | 空集合 | 允许的签名证书 SHA-256 Base64URL 摘要列表，随 release 元数据传递，用于证书轮换/多签名证书场景。它与当前 Variant 实际 signingConfig 计算出的证书摘要并存，不能将两者理解为重复项。 |
| `startupLogUploaderClass` | `""` | 在线模式可填 Shell 中启动日志上传器的全限定类名；空值表示不添加该入口。local 强制清空。 |
| `resObfuscationEnabled` | `false` | 是否注册 Jiagu 资源混淆任务。独立于 R8 和 Android `shrinkResources`。两种模式都按该开关处理。 |
| `resConfigs` | 未设 | 传给 Jiagu 资源混淆任务的语言过滤列表；只有 `resObfuscationEnabled = true` 时消费。它不是 `android.defaultConfig.resConfigs` 或 Android locale filters。 |
| `autoRunBuildTypes` | 空集合 | 限制为哪些 Android build type 创建/挂接 Jiagu Variant 任务。空集合表示不限制，即所有 Variant。名称如 `debug`、`release`。两种模式通用。 |
| `attachToTask` | `"assemble"` | 扩展类声明的任务前缀配置，预期用于控制 Jiagu 任务挂接点；**当前实现未读取该属性**，修改它不会改变任务图，暂不应依赖。实际自动构建筛选由 `autoRunBuildTypes` 控制。 |
| `publish` | `false` | 全局默认发布开关，控制成功构建后是否执行 release 发布流程。线上通常用于在线模式；local 建议关闭。 |
| `antiDebugEnabled` | 未设 | 在线模式的全局反调试默认值。未设置时按 build type 名称推断：名称含 `debug` 则 `false`，否则 `true`。local 强制 `false`。 |
| `buildTypes.<name>.publish` | 未设 | 指定某个 build type 的发布开关；若设置，覆盖全局 `publish`。 |
| `buildTypes.<name>.antiDebugEnabled` | 未设 | 指定某个 build type 的反调试开关；在线时优先级高于全局值；local 忽略并强制关闭。 |

## 覆盖优先级与重复项

### 有意重复：全局值与 build type 覆盖

`publish` 和 `antiDebugEnabled` 各有全局配置和 `buildTypes {}` 配置。这是有意的两层设置：全局值提供缺省行为，build type 值适合对 `debug`/`release` 单独覆盖。

- `publish`：`buildTypes.<name>.publish` > 全局 `publish`（默认 `false`）。
- 在线 `antiDebugEnabled`：build type 值 > 全局值 > 自动推断（非 debug 默认为 `true`，debug 默认为 `false`）。
- 本地 `antiDebugEnabled`：始终 `false`，全局和 build type 设置均不生效。

建议全局只设统一默认值，确有差异时才在 build type 再写一遍，避免看起来重复但最终值难以判断。

### 看起来相似但用途不同

- `expectedSignature` 是运行时检查所期待的签名值；`certificateSha256Digests` 是服务端/发布元数据中的允许证书摘要列表，适用于多证书或证书轮换。不要把两者合并。
- `payloadCompressionEnabled` 是 Payload 容器压缩；`shellMinificationEnabled` 是 local 模式下 Jiagu Runtime 的 R8。它们作用对象不同。
- `resConfigs` 是 Jiagu 资源混淆任务的语言过滤；Android DSL 的 `resConfigs` / `localeFilters` 是 AGP 资源打包配置。两者不是重复配置，但同时开启时可能叠加过滤。
- `resObfuscationEnabled` 是 Jiagu 资源变换；`android.buildTypes.*.shrinkResources` 是 AGP 资源裁剪。不是同一开关。

### 当前未生效或被模式覆盖的配置

- `attachToTask` 当前只有声明和默认值，没有消费者，是可清理/待实现的配置项。
- local 中服务端凭据、`serverUrl`、在线签名校验、上传器类名不参与任务执行；Payload 压缩、反调试和签名检查分别被插件强制覆盖为关闭。
- local 且 Jiagu 流水线启用的 Variant 会关闭 AGP whole-program R8，由 `shellMinificationEnabled` 单独控制 Runtime R8；未被 `autoRunBuildTypes` 选中的 Variant 保留 Android DSL 的 `minifyEnabled`。该 DSL 仍是全局值，不能按 build type 单独开关 Runtime R8。

## 配置归属提醒

以下不是 `dexReport` 配置，而是 Android/Gradle 自身配置：签名配置、`minifyEnabled`（online 模式及未启用 Jiagu pipeline 的 Variant）、`shrinkResources`、Android `resConfigs`/locale filters、ProGuard 文件、`applicationId`、版本号和 NDK 配置。local 且 Jiagu pipeline 生效时，插件关闭 AGP whole-program R8 并单独按 `shellMinificationEnabled` 处理 Runtime；其他 Android DSL 配置仍由 AGP 负责。
