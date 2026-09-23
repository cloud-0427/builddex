# 本地加固模式设计

## 1. 目标与边界

新增 `local`（本地加固）模式，用于不依赖加固服务端、可离线构建且首次启动即完全离线解密的 APK。该模式的默认取向是**最小运行时行为**：所有可选的鉴权、环境检测、遥测和网络能力均默认关闭。业务 DEX 仍以密文存放在 APK 内，运行时在内存中解密并加载。

本设计只要求兼容 Android `minSdk 23`，不为 API 23 以下提供回退。加密采用平台从 API 23 起可用的 JCA `AES/GCM/NoPadding`，不引入 Play services、Android Keystore、RSA/ECDSA、Ed25519 或第三方密码库。

本模式不是抗静态逆向、反篡改或授权控制方案：解密所需的全部材料随 APK 分发，具有 APK 与运行时调试能力的攻击者最终可以恢复明文。它提供的是“避免 DEX 明文直接落在 APK 中”的基础混淆层，不能与在线授权模式的安全等级混淆。

## 2. 非目标

- 不访问加固服务端，不创建 Release，不封存密文摘要，不发布 Release。
- 不进行公司鉴权、设备注册、设备授权、用户授权、许可证校验、撤销、配额或有效期控制。
- 不请求 Play Integrity，也不依赖 Google Play services。
- 不创建、读取或依赖 Android Keystore 密钥。
- 不自动上传启动事件、崩溃、设备信息或任何遥测数据。
- 不承诺阻止重打包、Hook、调试、Root、模拟器、VPN/代理或离线复制。

## 3. 模式与默认值

插件 DSL 增加 `protectionMode`，枚举值为 `online` 和 `local`。保留当前线上协议作为 `online`，以避免改变已有构建的行为；用户显式选择 `local` 后，以下所有功能默认 `false`。构建类型块可以覆盖默认值，但不得通过覆盖隐式打开网络：`networkEnabled=false` 时，所有网络相关开关必须仍为 `false`。

```groovy
dexReport {
    protectionMode = "local"

    // 本地模式的下列值即使省略也全部为 false。
    networkEnabled = false
    companyAuthorizationEnabled = false
    deviceAuthorizationEnabled = false
    userAuthorizationEnabled = false
    licenseCheckEnabled = false
    playIntegrityEnabled = false
    signatureCheckEnabled = false
    antiDebugEnabled = false
    hookDetectionEnabled = false
    rootDetectionEnabled = false
    emulatorDetectionEnabled = false
    vpnDetectionEnabled = false
    proxyDetectionEnabled = false
    tamperCheckEnabled = false
    startupTelemetryEnabled = false
    crashTelemetryEnabled = false

    // 构建优化与载荷处理也全部默认关闭。
    codeMinificationEnabled = false // 对应 AGP minifyEnabled / R8
    shellMinificationEnabled = true // local 默认仅混淆、裁剪壳代码
    resourceShrinkEnabled = false   // 对应 AGP shrinkResources
    resObfuscationEnabled = false
    payloadCompressionEnabled = false
    nativeLibRepackagingEnabled = false

    // 仅保留基础本地 AES-GCM 加解密；不填写服务端参数。
    localCrypto {
        algorithm = "AES-256-GCM"
        keySource = "embedded-split"
    }
}
```

`serverUrl`、`companyId`、`companyApiKey`、`publish` 与 `startupLogUploaderClass` 在 `local` 模式无效且不应配置。实现须在配置期报出明确错误，而不是静默忽略敏感的线上参数。`local` 模式也不得注册 `JiaguReleaseTask`、`JiaguPublishFlowAction` 或任何会实例化 `JiaguServerClient` 的任务。

## 4. 开关矩阵

所有开关写入只读的本地 RuntimeConfig；默认值由插件在构建期固化，运行时不读取远端配置或 SharedPreferences 覆盖值。

| 分类 | DSL 开关 | 本地模式默认 | 生效位置 | 说明 |
| --- | --- | --- | --- | --- |
| 网络总闸 | `networkEnabled` | `false` | 插件、Runtime | `false` 时不得打包网络端点、HTTP 客户端调用路径或上传器；其余网络开关必须为 `false`。 |
| 公司鉴权 | `companyAuthorizationEnabled` | `false` | 构建期 | 禁用公司 API Key 校验、Release 创建/seal/publish。 |
| 设备鉴权 | `deviceAuthorizationEnabled` | `false` | Runtime | 禁用 BOOTSTRAP、ENROLL、AUTHORIZE、设备凭证、Grant 与设备密钥。 |
| 用户鉴权 | `userAuthorizationEnabled` | `false` | Runtime | 预留业务接入点；本地模式不收集用户标识、不跳转登录。 |
| 许可证 | `licenseCheckEnabled` | `false` | Runtime | 禁用有效期、次数、撤销和许可证文件校验。 |
| Play 完整性 | `playIntegrityEnabled` | `false` | Runtime | 禁用 token 预取、请求和校验。 |
| 签名校验 | `signatureCheckEnabled` | `false` | Native | 不读取 `EXPECTED_SIGNATURE`，不阻断重签名 APK。 |
| 篡改校验 | `tamperCheckEnabled` | `false` | Native/Runtime | 禁用 APK、资源、native 库、密文摘要的附加校验。AES-GCM tag 认证不属于可关闭的篡改校验。 |
| 反调试 | `antiDebugEnabled` | `false` | Native | 禁用 debugger 检测与阻断。 |
| Hook 检测 | `hookDetectionEnabled` | `false` | Native | 从现有 anti-debug 中拆出，禁用框架/注入痕迹检测。 |
| Root 检测 | `rootDetectionEnabled` | `false` | Runtime/Native | 不扫描 su、系统属性或挂载状态。 |
| 模拟器检测 | `emulatorDetectionEnabled` | `false` | Runtime | 不读取 Build 指纹、硬件特征或模拟器文件。 |
| VPN 检测 | `vpnDetectionEnabled` | `false` | Runtime | 不枚举网络接口、VPN transport 或 VpnService 状态。 |
| 代理检测 | `proxyDetectionEnabled` | `false` | Runtime | 不读取系统代理、PAC 或代理环境。 |
| 启动遥测 | `startupTelemetryEnabled` | `false` | Runtime | 不入队、不持久化、不上报启动事件；本地 Logcat 可保留受控 debug 日志。 |
| 崩溃遥测 | `crashTelemetryEnabled` | `false` | Runtime | 不安装网络上报处理器。 |
| Payload 业务 R8（设计项，未实现） | 当前无对应 DSL；实际为 Payload 固定 D8 | 不进入 Jiagu R8 | 构建期 | Routing 命中的业务代码转 DEX 时不经 Jiagu R8。`android minifyEnabled` 不会变成 Payload R8；业务需混淆时应在上游完成。 |
| Runtime 混淆/裁剪 | `shellMinificationEnabled` | `true`（仅 local） | 构建期 | 仅 Jiagu Runtime namespace 作为独立 R8 program input；R8 输出 classfile，与未改动的第三方/Shell classfile 合并后由 AGP D8 编译。产出独立 Runtime mapping。 |
| AGP 未使用资源裁剪 | `android.buildTypes.*.shrinkResources`（不是 Jiagu DSL） | 当前无 local 自动关闭 | 构建期 | 业务方 Android DSL 控制；通常依赖该 Variant 的 minify/R8。local 基线须显式 false。 |
| Jiagu 资源路径/资源表混淆 | `resObfuscationEnabled` | `false`（全局 convention） | 构建期 | 默认不修改资源名称、路径或引用；显式设为 `true` 才运行资源变换，并可能使用 Jiagu `resConfigs` 做语言资源裁剪。 |
| Payload 压缩 | `payloadCompressionEnabled` | `false` | 构建期/Runtime | JG3 保持未压缩存放；避免构建压缩和启动解压的 CPU 开销，代价是 APK 可能更大。 |
| Native 库重打包 | 当前无对应 Jiagu DSL | 当前不进入 DEX Payload | 构建期/Runtime | `.so` 使用 AGP 标准 Native 构建与 ABI 打包；`nativeLibRepackagingEnabled` 是设计项，当前未实现。 |

`shellMinificationEnabled` 是 `dexReport` 全局属性，不是逐变体属性。local 下，对实际启用 Jiagu 流水线的 Variant，插件关闭 AGP whole-program R8，再由该属性单独控制 Runtime-only R8；第三方仍作为原始 classfile 交给 AGP D8。`autoRunBuildTypes` 选择 Jiagu 流水线生效的 build type；未选中的 Variant 保留 Android DSL 自己的 `minifyEnabled`。若需 Debug 与 Release 使用不同 Runtime R8 值，再增加逐变体 DSL。

`payloadDecryptionEnabled` 不作为开关暴露：本地模式必须始终解密 payload。若 AES-GCM 认证失败、容器格式错误或 JG3 校验失败，启动失败；不能为了“关闭检测”而忽略认证 tag 或继续加载不可信明文。

## 4.1 当前实现的配置所有权与生效边界

本节描述插件当前真实执行路径，避免把“业务方的 `minifyEnabled`”误认为它会处理已移入 Payload 的业务代码。加固任务先接收 AGP `ScopedArtifact.CLASSES`，按 payload routing 拆成 shell 和 business 两路；business 路只经 D8 转成 Payload DEX，shell 路再交给 AGP 后续 D8/R8。资源处理走独立的 AGP 资源任务链，不跟随这两路字节码分流。

当前实现已按 [08 文档](08-payload-allowlist-and-provider-startup-design.md) 第 3.4 节将 App 业务、Jiagu Runtime、其它 Shell class 分别路由到 Payload D8、Runtime-only R8 classfile、pass-through/AGP D8。第三方留在 Shell 但选择性进入 R8 的扩展仍明确不支持，后续再评估。

| 项目 | 当前配置入口 | 生效对象与执行者 | 可配置性 / 限制 |
| --- | --- | --- | --- |
| Payload 业务 Java/Kotlin 字节码 | Jiagu routing：`payloadSelectionMode`、`payloadIncludePackages`、`shellKeepPackages`、`shellKeepClasses`、启动组件策略 | 选入 Payload 的 class/JAR 由 Jiagu D8 转为 DEX，再封装/加密；当前不由 Jiagu R8 混淆、裁剪或优化 | 业务方可以通过 routing 决定哪些类进入 Payload；不能靠应用 Variant 的 ProGuard/R8 规则处理 Payload。上游已混淆的输入保留其现有描述符，业务方需保存上游 mapping。 |
| Payload 的预混淆/自定义规则 | 业务模块自己的上游构建或独立 SDK 发布流水线 | 由业务方上游 R8 完成；Jiagu 后续仍只做 D8 | 支持，但规则、mapping 和运行时反射/JNI 兼容性由上游负责。应用最终 Variant 的 `proguardFiles` 不是 Payload R8 配置。 |
| Jiagu Runtime class | local：`dexReport.shellMinificationEnabled`、Runtime 内置 consumer rules、Payload→Runtime ABI 扫描 | 开关为 true 时仅 Jiagu Runtime classes 进入独立 R8；R8 以第三方/Shell pass-through classfile 作为只读 library inputs | 输出 classfile 和 Runtime 专属 mapping；业务 App `proguardFiles` 不传入该 R8。Manifest/JNI/反射/SPI/共享 ABI 仍须有保留规则。 |
| 第三方与其它 Shell class | local Runtime R8 分流 | 不进入 R8 program；保留输入 class/member 描述符，最后由 AGP D8 转成 DEX | R8 开关为 true/false 均不改变第三方处理；D8 仅执行 Android 所需转换/desugaring。 |
| `android.buildTypes.*.minifyEnabled` | Android Gradle Plugin DSL | online：插件不覆盖；local 且 Jiagu 生效的 Variant：插件将 AGP R8 关闭，避免全 Shell R8；未被 `autoRunBuildTypes` 选中的 Variant 保留原值 | Jiagu 生效的 local Variant 中，该值不再控制 R8；Runtime R8 由 `shellMinificationEnabled` 控制。Payload 仍不进入 R8。 |
| Android 资源未使用项裁剪 | `android.buildTypes.*.shrinkResources` | AGP 资源 shrink 流程 | 插件当前不提供/不映射 `resourceShrinkEnabled`，也不在 local 自动强制关闭。由业务方 Android DSL 决定；通常需与 AGP R8/minify 一同启用。local 最小基线应显式 `false`。 |
| Jiagu 资源路径/资源表混淆 | `dexReport.resObfuscationEnabled` | Jiagu `ResObfuscatorTask` 修改链接后的 `.ap_`：资源文件路径及 `resources.arsc` 字符串池/键 | 这是独立于 R8 的资源变换，不是 AGP `shrinkResources`。插件全局 convention 为 `false`；有需要时业务方显式设为 `true`，并验证资源 ID、反射式资源名访问、第三方 SDK 和多语言包兼容性。 |
| `dexReport.resConfigs` | Jiagu 插件 DSL（不是 `android.defaultConfig.resConfigs`） | 仅当 Jiagu `resObfuscationEnabled=true` 时，传给 `ResObfuscatorTask` 做语言资源裁剪 | 关闭资源混淆任务时，这个插件属性不生效。不要把它和 AGP 的 `android.defaultConfig.resConfigs` / `androidResources.localeFilters` 混为一谈；后者属于 Android DSL，可以独立影响资源打包，local 基线需留空/不配置。 |
| Payload 压缩 | `dexReport.payloadCompressionEnabled` | Jiagu 业务 DEX 容器格式/运行时解压路径 | online 可由插件属性控制；local 当前由插件强制 false，生成未压缩 Payload。它不决定 Shell R8。 |
| `.so` / Native 库处理 | `android` 的 `jniLibs`、AGP native build 配置 | 当前由 AGP 走标准 Native 构建、ABI 打包和 APK `lib/<abi>/` 布局 | `nativeLibRepackagingEnabled` 目前不是已接入的插件开关；不能依赖它的 DSL 声明改变产物。当前加固任务没有把业务 `.so` 放入 DEX Payload。 |

### local / online 场景矩阵

| 场景 | Payload 业务代码 | Shell / 依赖 | Android 资源 | Jiagu 资源混淆 |
| --- | --- | --- | --- | --- |
| local + `shellMinificationEnabled=true` | Routing 命中的类由 D8 → Payload；保持输入类/成员描述符，不吃 Runtime R8 规则 | 仅 Jiagu Runtime 进入独立 R8 并输出 Runtime mapping；第三方及其它 Shell class 原样通过 AGP D8，AGP whole-program R8 关闭 | `shrinkResources` 仍按 Android DSL；建议 false。Android `resConfigs`/locale filters 仍可独立过滤 | `resObfuscationEnabled` 默认 false；显式设 true 才会运行资源变换及插件 `resConfigs` 裁剪 |
| local + `shellMinificationEnabled=false` | 仍由 D8 → Payload，不经 R8 | Jiagu Runtime 与其它 Shell class 均不经 Jiagu R8；最终全部由 AGP D8 编译 | `shrinkResources` 仍按 Android DSL 配置；若配置与 AGP 约束冲突，构建可能失败 | 同上，跟 Runtime R8 开关无关 |
| online + Variant `minifyEnabled=false` | 当前选择的 Payload 仍由 Jiagu D8 处理 | 留在 Shell 的类走 AGP D8 | 由 Android DSL 控制 | 按 `dexReport.resObfuscationEnabled` 控制 |
| online + Variant `minifyEnabled=true` 且存在 Payload class | Jiagu 不会自动将 Variant R8 规则应用于 Payload；检测到 Payload 绕过 R8 时构建失败（当前 `payloadR8Policy=fail`） | 仅 Shell 会进入 AGP R8 | `shrinkResources` 由 Android DSL 控制，且须满足 AGP 与 minify 的组合约束 | 独立由 Jiagu 资源开关控制 |
| online + 无 Payload class（所有 class 留 Shell） | 不产出业务 DEX 的场景不受 Payload 绕过保护 | AGP Variant R8 按业务 DSL 处理完整 class program | 由 Android DSL 控制 | 独立由 Jiagu 资源开关控制 |

### 应用 DSL 与 Jiagu DSL 的配置示例

local 模式应明确表达“业务字节码进入 Payload、只有 Jiagu Runtime 进入独立 R8、第三方和其余 Shell class 经 AGP D8、资源裁剪和资源混淆均关闭”。Jiagu Runtime R8 使用 Runtime 内置 consumer rules 与生成的 Payload ABI keep rules；业务 App 的 `proguardFiles` 不会被用于 Runtime R8。如果规则是为了业务 Payload 的反射或 JNI，应在业务上游混淆流水线处理，或将相应类路由到 Shell 并由业务自行确保运行时兼容。

```groovy
dexReport {
    protectionMode = "local"
    payloadSelectionMode = "allowlist"
    payloadIncludePackages = ["com.example.business.**"]

    shellMinificationEnabled = true
    // 默认 false；确有需要且完成资源兼容性回归后才显式开启。
    // resObfuscationEnabled = true
    // dexReport.resConfigs 只给 Jiagu 资源混淆任务用；关闭该任务时不生效。
}

android {
    buildTypes {
        release {
            // local 时最终值由 shellMinificationEnabled 覆盖；建议显式写出预期值，
            // 避免未启用插件或切换 online 后产生意外差异。
            minifyEnabled false
            shrinkResources false
            proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
        }
    }
    defaultConfig {
        // local 最小基线不配置 resConfigs / localeFilters，避免 Android DSL 语言过滤。
    }
}
```

这里 `minifyEnabled false` 表示 local 且 Jiagu 生效时禁用 AGP whole-program R8；`shellMinificationEnabled` 独立控制 Jiagu Runtime-only R8。示例中业务 `release` 的 `minifyEnabled` 仍写 false，是为了避免在 online 或插件未生效时误认为业务 Payload 已完成 R8。

### 已知实现差异与发布前门禁

设计目标是 local 模式所有可选资源优化默认关闭。Jiagu 资源混淆现在通过插件全局 convention `false` 实现“省略即关闭”；仍有以下未覆盖项：

1. `codeMinificationEnabled`、`resourceShrinkEnabled`、`nativeLibRepackagingEnabled` 当前不是插件实现的 DSL 属性。业务 R8 的实际分界由 payload routing 与 `shellMinificationEnabled` 决定；资源裁剪须看 AGP `shrinkResources`；Native 库使用 AGP 标准打包。
3. 插件当前不校验 local 的 `shrinkResources`、Android `resConfigs`/locale filters 最终值，也不禁止资源配置裁剪。要把“最小 local”做成强保证，后续应增加 beforeVariants/configuration 校验，或提供显式插件开关并检测最终 Variant 值。
4. `resObfuscationEnabled=true` 还会让 `ResObfuscatorTask` 使用 `dexReport.resConfigs` 做语言资源裁剪；它不仅是重命名开关。开启时必须把它视为“资源表/路径变换 + 可选语言过滤”的组合功能，并对资源 ID、反射式资源名访问、第三方 SDK 和多语言包做完整回归。

local 最小基线发布检查必须逐项读取**最终值/任务图/产物**，不能只看某个 DSL 文本：Runtime R8=true（如果采用默认配置）；第三方不进入 R8；Payload 不进入 R8；AGP `shrinkResources=false`；Android `resConfigs`/locale filters 不产生非预期过滤；`dexReport.resObfuscationEnabled=false`（默认值，除非显式 opt-in）；`dexReport.resConfigs` 不触发 Jiagu 裁剪；业务 `.so` 仍按标准 ABI 目录打包。

## 5. 本地密钥与容器

### 5.1 密钥来源

每次本地构建生成一个新的 32 字节随机 `payloadKey`（`SecureRandom`）。密钥只在本次 Gradle 任务内存、生成的 RuntimeConfig 和 APK 内的只读段中存在；构建结束后清空内存中的可变副本，不写入日志、构建缓存、`release.json` 或服务器。

用户所说的“密码结构存储在本地”落实为：解密所需的算法标识、Key ID、密钥分片位置、AAD 格式、nonce、密文与 tag 都位于 APK 的 `liblog_ext.so` 只读段。为避免单一明显常量，密钥使用 `embedded-split` 结构：

```text
payloadKey = keyPartA XOR keyPartB XOR keyMask

keyPartA  : RuntimeConfig 的 Base64URL 字段
keyPartB  : JGLP-L 头部中的独立 32 字节字段（同样位于 `liblog_ext.so` 只读段）
keyMask   : 由 packageName + versionCode + 固定域分隔符做 SHA-256 后取前 32 字节
```

这只是让直接字符串搜索稍困难，**不是密钥保护**。不使用硬编码口令、PBKDF、可预测随机数或设备派生密钥；这些做法要么更弱，要么会重新引入兼容性或可用性问题。

### 5.2 算法与参数

| 项目 | 取值 |
| --- | --- |
| 对称算法 | AES-256-GCM (`AES/GCM/NoPadding`) |
| Key | 每构建随机 32 字节 |
| Nonce | 每构建随机 12 字节，来自 `SecureRandom` |
| Tag | 128 位，由 GCM `doFinal` 产生并校验 |
| AAD | UTF-8 `LOCAL-PAYLOAD-V1\\0` + packageName + versionCode + payload 明文 SHA-256 |
| 摘要 | SHA-256，仅用于容器身份与 JG3 明文完整性标识；GCM tag 承担密文认证 |

Nonce 与密钥配对只能使用一次。因为每次构建都生成新 key，且使用随机 nonce，不依赖在线模式目前的“由 HMAC 推导 nonce”规则，也不需要公司、Release 或服务端字段。

### 5.3 容器格式

沿用 `JGRC` 外层以减少 native 装载改动，但使用独立版本，避免被线上 `RuntimeConfig v3` 误解析。

```text
JGRC (big-endian)
offset  size  值
0       4     ASCII "JGRC"
4       4     bundleVersion = 2
8       4     local RuntimeConfig JSON 长度 C
12      4     JGLP 长度 P
16      C     RuntimeConfig（configVersion = 4, mode = "local"）
16+C    P     JGLP-L

JGLP-L (big-endian)
0       4     ASCII "JGLP"
4       4     version = 2
8       4     encryptedLength（keyPartB + nonce + ciphertext + tag）
12      32    keyPartB
44      12    nonce
56      N     ciphertext + 16-byte GCM tag
```

本地 RuntimeConfig 的最小字段为：`configVersion`、`mode`、`packageName`、`versionCode`、`payloadPlaintextSha256`、`keyPartA`、`aadVersion`、`cipherAlgorithm`。不得含 `serverUrl`、公司 API Key、公司 ID、Release ID、服务端公钥、Integrity 配置、Credential 或 Grant。

## 6. 运行时流程

```text
ProxyApplication.attachBaseContext
  -> Native 读取 JGRC v2 与 local RuntimeConfig
  -> （仅当对应开关为 true）执行本地环境/签名检查
  -> 合成 embedded-split payloadKey
  -> 校验 JGLP-L 头部、长度和版本
  -> AES-GCM + AAD 解密并验证 tag
  -> 校验 JG3 明文结构和 payloadPlaintextSha256
  -> 注入 DEX，绑定真实 Application
```

运行时必须增加显式分支，例如 `getLocalPayload(Context, String, ByteBuffer)`，而不是让 `getAuthorizedPayload` 在“拿不到服务器配置”时降级。这样可保证 local 路径不加载 `NetworkHelper` 的 HTTP、OkHttp、Keystore、Play Integrity、授权缓存和授权状态机。

本地路径不得申请 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` 等仅服务于加固网络检测/上报的权限。若业务 App 自身需要这些权限，插件不得删除它们，但加固 Runtime 仍不得使用它们。

### 6.1 全部可选开关关闭时的最小流程

以下是 `protectionMode=local` 且本文所有可选开关均为 `false` 的基线。它是 local 模式的主要回归目标，而不是“功能缺失时的降级路径”。

```text
构建期
业务 DEX
  -> 生成未压缩 JG4（JG3 的本地未压缩变体）
  -> 生成本次构建专用的随机 AES-256 key 与 nonce
  -> AES-GCM 加密为 JGLP-L
  -> 将 JGLP-L、local RuntimeConfig、key 分片写入 liblog_ext.so
  -> 注入 ProxyApplication、最小 Native Runtime 和真实 Application 元数据

启动期
ProxyApplication.attachBaseContext
  -> Native 读取 APK 内 JGRC v2
  -> 读取 local RuntimeConfig 并合成 embedded-split key
  -> 解析 JGLP-L 头部、长度、nonce 和 tag
  -> 以固定 AAD 执行 AES-GCM 解密并验证 tag
  -> 校验 JG4 结构及 payloadPlaintextSha256
  -> 在内存中注入业务 DEX，绑定真实 Application
```

此基线保留的加固效果仅为：业务 DEX 不以普通明文 `classes*.dex` 形式直接出现在 APK 中；密文位于 `liblog_ext.so` 的只读段；明文 DEX 仅在启动时的内存中用于加载。`ProxyApplication`、Native Runtime、本地容器解析、key 分片合成、AES-GCM 认证解密、JG4 格式校验和 DEX 注入是不可移除的核心链路。

即使全部可选开关关闭，下列正确性校验也必须保留，且不计入签名/篡改/环境检测：JGRC/JGLP-L magic、版本、长度边界、nonce/tag 长度、AES-GCM tag、JG3 格式和 payload 明文 SHA-256。不允许因为关闭检测而忽略认证失败后继续加载。

在该基线中明确不存在：业务 payload R8、资源裁剪、资源混淆、`resConfigs` 语言裁剪、payload 压缩、业务 native 库重打包、网络请求、公司/设备/用户/许可证鉴权、签名/调试/Hook/Root/模拟器/VPN/代理/额外篡改检测、Keystore、Play Integrity、遥测和上传器。唯一默认开启的 R8 是 shell 专用混淆/裁剪，作用域严格限定在分流后的壳 classes JAR。业务 App 自己声明或调用的同类能力不属于 Jiagu Runtime；测试必须将二者区分。

## 7. 插件与代码改造

本节保留原始实现要求；目前已完成的 Runtime-only R8 分流与其余差异，以 **4.1 当前实现的配置所有权与生效边界** 为准。尤其资源开关的 local convention 与资源裁剪最终值尚未完全实现。

1. 在 `DexReportExtension`、`DexReportBuildType`、`JiaguTask` 与 `ManifestTransformerTask` 增加模式和对应布尔开关；每个属性必须有明确的模式默认值和最终 Variant 校验。
2. 区分 Payload D8、Runtime-only R8 classfile pass、Shell AGP D8、AGP resource shrink、Jiagu resource obfuscation、payload compression 与 Native `.so` 打包，不能用一个 `codeMinificationEnabled`/`resourceShrinkEnabled` 概念代替不同执行器。local 的 Payload R8 固定不执行；`shellMinificationEnabled` 控制 Runtime-only R8，local 默认 true；resource shrink、resource obfuscation、payload compression、native 重打包必须分别有可验证的最终值。
3. local 且 Jiagu 生效的 Variant 将 AGP whole-program R8 关闭；`shellMinificationEnabled` 仅控制 Runtime-only R8。Runtime R8 输出 classfile 与 pass-through Shell classfile 回接 `ScopedArtifact.CLASSES`，再统一由 AGP D8 处理；第三方不得成为 R8 program input。AGP `shrinkResources` 和 Android locale filters 属于不同 Android DSL，需分别显式关闭或校验。
4. 在 `DexReportPlugin` 根据模式选择任务图：`online` 保持现有 `JiaguReleaseTask`；`local` 注册 `JiaguLocalPayloadTask`，直接生成 JGRC v2/JGLP-L 和 ELF，不创建任何 server client。
5. `JiaguLocalPayloadTask` 校验 `minSdk >= 23`；低于 23 时构建失败并说明 `AES/GCM/NoPadding` 的最低 API 要求。`payloadCompressionEnabled=false` 时生成未压缩 JG4；`nativeLibRepackagingEnabled=false` 时不得收集、复制或改写业务 native 库。
6. Native 根据 bundle/config 版本选择线上或本地入口。local 入口不可查找或调用 `NetworkHelper.getAuthorizedPayload`；关闭 payload 压缩和 native 重打包时也不加载对应的解压、文件释放或 native 重定向代码路径。
7. 新建轻量 `LocalPayloadCrypto`（JCA + `ByteBuffer`），仅包含容器解析、key 合成、AES-GCM 与 JG3 校验。避免把完整 OkHttp/Keystore/Play Integrity 依赖保留在 local shell。
8. Manifest 只写入启用的检测 metadata；未设置即视为 `false`，禁止 native 侧以 `true` 作为 metadata 缺失时的默认值。
9. 构建结束清零 plaintext、payloadKey 及其临时分片数组；失败路径同样清零并删除临时 bundle 文件。

## 8. 约束与冲突规则

- `protectionMode=local` 与 `networkEnabled=true`、公司/设备/用户鉴权、许可证、Play Integrity、启动/崩溃上传任一项组合时，构建失败并列出冲突开关。
- `local` 可以显式打开签名、反调试、Hook、Root、模拟器、VPN、代理或篡改检查，但这是用户承担兼容性与可用性风险的 opt-in；每项均须单独启用，不能由一个“严格模式”隐式打开。
- local 的业务 Payload 固定不经 Jiagu R8；`shellMinificationEnabled` 默认 `true`，仅控制 Jiagu Runtime R8。R8 classfile 输出与第三方 pass-through classfile 统一回接 AGP D8；第三方绝不进入 R8 program。资源裁剪须通过 Android `shrinkResources=false` 和不设置过滤型 Android `resConfigs`/locale filters 实现；Jiagu 资源混淆 convention 已全局设为 `false`，仅在显式 `resObfuscationEnabled=true` 时开启。
- `dexReport.resConfigs` 仅由 Jiagu `ResObfuscatorTask` 消费，并且只有 `resObfuscationEnabled=true` 时才做语言过滤；当前 local 不会自动禁用该任务。Android DSL 的 `resConfigs`/locale filters 则独立生效。
- 不支持由服务端远程改变 local 配置。任何模式切换均需重新构建 APK。

## 9. 验收与测试

### 必测构建

- `minSdk 23`、24、29、35 的 local debug/release 构建均能完成；`minSdk 22` 明确失败。
- local 最小配置采用 `shellMinificationEnabled=true`、`resObfuscationEnabled=false`（全局默认已关闭）、Android `shrinkResources=false`，且不配置 Android 语言过滤；最终仅 Runtime classes 经 R8，第三方和其它 Shell classes 经 AGP D8，Payload classes 经 Jiagu D8，资源与语言未被 Jiagu/AGP 裁剪。当前实现尚无 resource shrink/locale 最终值校验，验证时需检查最终包内容。
- local 配置不填写 `serverUrl`、`companyId`、`companyApiKey` 仍能构建；填写线上凭证则明确失败。
- 产物中存在 JGRC v2、JGLP v2 与 `mode=local`，不存在 `serverUrl`、公司 API Key、`serverPublicKey`、`integrityMode`、`wrappedKey`、Credential、Grant 字符串。
- local 任务图不含 `JiaguReleaseTask`、publish 任务和 `JiaguServerClient` 的执行路径。

### 必测运行时

- 断网、飞行模式、无 SIM、VPN 开启、系统代理开启、无 Google Play services、Root/模拟器/调试器环境下，默认 local 配置均不因加固 Runtime 而阻断或发起请求。
- 使用网络拦截器/测试代理验证启动过程零加固网络请求；业务自身请求需在测试中单独标记，不能计入 Runtime。
- 正确 payload 可解密并完成真实 Application 绑定；篡改 nonce、密文、tag、AAD 字段或 JG3 摘要时必须失败，不加载 DEX。
- 打开任一 opt-in 检测时，分别验证其 metadata、运行时分支和关闭时的零调用。
- `adb shell dumpsys package` 与静态 APK 扫描确认 local shell 不因加固而新增网络权限或 Play/Keystore 依赖。

### 全关闭基线回归门禁

每次修改 plugin、Runtime、Native 代码、容器版本或依赖版本后，都必须构建一个所有可选开关均为 `false` 的 local fixture APK，并执行以下门禁；任一项失败均不得视为 local 模式可发布。

| 门禁 | 检测方法 | 通过条件 |
| --- | --- | --- |
| 配置最终值 | 读取 Gradle 最终 Variant、task graph 和 APK | `mode=local`；鉴权/检测/加固网络/遥测关闭；Payload 不经 R8、Runtime-only R8 按开关开启、第三方不经 R8；`shrinkResources=false`、无 Android locale 过滤、`resObfuscationEnabled=false`、Payload 未压缩；`.so` 使用标准 ABI 打包。 |
| 任务图零线上依赖 | Gradle `--dry-run`、任务输入审计和 class 调用扫描 | 不执行或实例化 `JiaguReleaseTask`、`JiaguPublishFlowAction`、`JiaguServerClient`；不要求 serverUrl/companyId/companyApiKey。 |
| 产物最小性 | 解压 APK/AAB 中的最终 split，扫描 manifest、DEX、字符串、依赖 | 存在 JGRC v2/JGLP-L 与最小壳；不存在加固写入的 URL、公司凭证、Release/Grant/Credential、Play Integrity、OkHttp/Keystore 授权路径；业务 native `.so` 仍在标准 `lib/<abi>/` 目录。 |
| 未压缩载荷 | 解析 JG3/JGLP-L 头与 RuntimeConfig | 标识为未压缩；没有解压入口或压缩算法字段；密文可被正确解密。 |
| 零加固网络 | 飞行模式启动；网络安全测试/MockWebServer/代理记录 | Runtime 启动至真实 Application 绑定期间无 DNS、socket、HTTP 或上传队列活动。业务网络必须用独立 fixture 排除。 |
| 零环境阻断 | 在调试器、Root、模拟器、VPN、代理和无 Play services 环境分别启动 | 不执行对应检测、不因这些环境返回阻断；真实 Application 均可启动。 |
| 加密正确性 | 正确样本及篡改 nonce、密文、tag、AAD、长度、JG3 摘要的样本 | 正确样本仅解密一次并启动；任何篡改均在 DEX 注入前稳定失败，且无网络回退。 |
| 内存与清理 | instrumentation 测试和受控 native/Java 测点 | 解密结果以 direct buffer 交给注入链；key、临时明文和可变分片在成功与异常路径均清零。 |
| API 兼容性 | API 23、24、29、35 真机或模拟器矩阵 | 均可离线启动；API 22 的 local 构建被明确拒绝。 |

为避免“测试只证明开关字段为 false”，测试桩应分别在网络、授权、Keystore、Integrity、反调试/Hook、环境检测、压缩解压和 native 重打包入口放置计数器；全关闭 fixture 要求这些计数全部为零。容器解析、AES-GCM、JG3 校验和 DEX 注入入口则必须至少被执行一次。

## 10. 迁移与发布

1. 先实现独立容器版本和 `JiaguLocalPayloadTask`，保持 online 的 v3 协议与既有产物完全不变。
2. 增加 local 单元测试、API 23 真机/模拟器测试和离线启动测试后，再公开 DSL。
3. README 与示例 App 新增 local 示例，移除其中线上 company API Key；当前示例中出现的凭证应立即废弃并从版本控制中删除。
4. 发布说明应明确：local 是离线基础加密模式，不具备在线授权、设备绑定、撤销或抗逆向保障；需要这些能力的应用继续选择 `online`。
