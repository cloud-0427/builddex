# Jiagu 业务 R8 与重打包架构改造设计

## 1. 文档状态

- 状态：设计阶段，作为后续实施的统一依据。
- 适用范围：`dex-report-plugin`、`jiagu-runtime`、示例 App 以及接入 Jiagu 的业务工程。
- 核心目标：业务方继续通过标准 Android Variant 配置决定是否混淆；Jiagu 不新增 `optimization`、`mappingFile` 或 `entrypointClass` 等必填配置，同时从架构上消除 Shell/Payload 同名类和二次 R8 风险。

本文档中的“业务方控制 R8”含义是：

1. 是否启用 R8，以业务工程现有的 `variant.isMinifyEnabled()` 为唯一事实来源；
2. R8 使用业务工程自己的 ProGuard 文件和依赖提供的 consumer rules；
3. Jiagu 只根据 Variant 的最终配置选择 D8 或 R8 执行路径，不擅自把未启用混淆的业务代码强制送入 R8；
4. Jiagu 可以追加维持加固边界所必需的最小规则，但不得追加改变业务命名策略的规则；
5. mapping 是构建产物，应自动生成、发现和归档，不由使用方填写路径。

## 2. 问题背景与已确认根因

当前构建链路会先把输入 class/JAR 划分为 Shell 与 Business，再对 Business 单独执行 D8 或 R8，随后由 Android 构建链继续处理 Shell。

已确认的线上 `VerifyError` 并不是 Yandex SDK 自身故障，而是 Shell 与 Payload 生成了相同的最终类描述符：

```text
Payload:
  k.a = com.akm.sdk.ad.platform.xiaomi.view.MutiImageView
  superclass = android.widget.LinearLayout

Shell APK:
  k.a = kotlin.collections.builders.ListBuilder$BuilderSubList$Itr
  superclass = java.lang.Object
  interfaces = java.util.ListIterator
```

Payload 中 `WW.c` 的 `0x3FE` 指令把 `k.a` 当作 `ViewGroup` 调用 `removeAllViews()`。运行时解析到了 Shell 中的 `k.a`，ART 因而在类加载阶段拒绝验证。

对当前 release 的 APK 外层 DEX 与 JG3 Payload 全量类名求交集，唯一交集就是：

```text
k/a
```

这说明问题不是普通 Java 强转异常，而是两个独立 R8 命名域在同一个运行时类空间中发生碰撞。

当前实现中的直接风险点：

- `JiaguTask` 根据 `minifyEnabled` 对 Business 再执行一次 R8；
- Business R8 被追加了 `-repackageclasses 'io.github.xjc.jiagu.payload.r8'`；
- `-keep class * extends android.view.View` 等规则会阻止部分已经混淆过的类再次改名，因此不能保证所有 Payload 类都进入指定包；
- Shell 后续 R8 看不到加密 Payload 的最终类名，可以独立生成相同的短名称；
- `VerifyServicesTask` 当前把 Shell/Payload 类集合合并，只验证 ServiceLoader，不检查两边交集；
- `processEntry()` 对重复输入条目静默跳过，输入顺序不同可能选择不同实现。

## 3. 已确定的设计决策

### 3.1 不增加业务 R8 模式配置

不引入以下 DSL：

```groovy
payload {
    optimization = "none"
    mappingFile = file("...")
}
```

原因：这些信息已经存在于 Android Variant 和 Gradle Artifact 中，重复配置会产生两个事实来源，最终必然出现配置不一致。

唯一判断逻辑：

```text
variant.isMinifyEnabled() == false
    -> Business 使用 D8，仅完成 class/JAR 到 DEX 的转换

variant.isMinifyEnabled() == true
    -> Business 使用 R8
       规则来自业务 Variant 的 proguardFiles
       规则来自依赖的 consumerProguardFiles
       Jiagu 只追加最小边界兼容规则
```

不得通过 `mapping.txt` 是否存在来反推是否启用 R8，因为文件可能是上一次构建残留。`variant.isMinifyEnabled()` 是唯一可靠来源。

### 3.2 不增加强制 entrypointClass

当前 Runtime 已能通过现有 Application/ClassLoader 安装流程加载业务 DEX，没有独立 `PayloadEntry` 也能运行。

本轮改造不引入：

```groovy
entrypointClass = "com.example.PayloadEntry"
```

也不要求业务实现新的统一接口。这样可以保证：

- 现有接入工程无需增加业务适配代码；
- Android 组件、SDK 初始化和现有生命周期行为保持兼容；
- 本轮只解决 R8 所有权、命名冲突和重打包可验证性，不同时扩大 Runtime 改造范围。

如果未来需要真正的独立 ClassLoader、插件化生命周期或多 Payload，再单独设计可选 Entrypoint SPI；它不应成为本轮修复的前置条件。

### 3.3 Jiagu 是执行协调者，不是业务混淆策略所有者

当业务 Variant 开启 `minifyEnabled` 时，由于 Jiagu 在 AGP 全局 R8 前已经把 Business 从 Shell 输入中分离，Business 分支仍需要单独调用 R8。这里“调用由 Jiagu 发起”不等于“策略由 Jiagu 决定”。

业务策略来源必须完整保留：

- `variant.isMinifyEnabled()`；
- Variant 的 `proguardFiles`；
- 依赖 AAR 的 consumer rules；
- `minSdk`；
- `debuggable`；
- boot classpath；
- 业务配置的属性保留、优化、反射、JNI 和序列化规则。

Jiagu 不得默认添加业务级重打包、全局 keep、任意优化开关或自定义混淆字典。唯一例外是 RAW 与 `R8_PROCESSED` 混合编译时，为防止两个独立 R8 命名域生成相同描述符，可对本次首次 R8 的 RAW 输出增加自动生成的冲突隔离命名空间；该规则不得作用于 `R8_PROCESSED` 输入。

## 4. 目标代码域与所有权

| 代码域 | 所有者 | 优化策略 | 命名策略 |
|---|---|---|---|
| Jiagu Runtime/Shell | Jiagu | Jiagu 可控制 | Jiagu 保留名或 Shell 专属命名空间 |
| Shell 依赖 | Jiagu | Shell R8 处理 | 原名保留或 Shell 专属命名空间 |
| Business Payload | 业务方 | 自动跟随 Variant | 业务规则决定；仅混合输入的 RAW 通道允许自动冲突隔离 |
| Shared ABI | Jiagu | 禁止删除，谨慎优化 | 稳定原名 |
| Android Boot Classpath | Android 平台 | 不处理 | 平台原名 |

必须满足：

```text
Shell 最终类描述符集合 ∩ Payload 最终类描述符集合 = 空集
```

在当前 Runtime 仍使用同一应用类空间的前提下，不允许通过“ClassLoader 恰好先找到正确类”来容忍重复类。重复类必须在构建期失败。

## 5. 目标构建流程

```text
Android Variant 配置完成
        │
        ├─ 自动读取 minifyEnabled、minSdk、debuggable
        ├─ 自动读取业务 ProGuard 文件
        └─ 自动读取依赖 consumer rules
                    │
            扫描并划分输入
             ┌──────┴──────┐
             │             │
          Shell JAR    Business Artifacts
             │             │
             │       逐 Artifact 检测
             │        ┌────┴────────────┐
             │       RAW          R8_PROCESSED
             │        │                 │
             │   minifyEnabled?     JAR/class -> D8
             │    ┌────┴────┐       DEX -> 透传
             │   false      true         │
             │    │          │           │
             │    D8         R8          │
             │          业务规则决定结果  │
             │    └─────────┬────────────┘
             │              │
             │        Business DEX
             │             │
             │       JG3 压缩/加密
             │
       AGP Shell R8/D8
             │
        最终 APK/AAB
             │
      Shell/Payload 最终验证
```

### 5.1 `minifyEnabled=false`

Business 编译器使用 D8：

- 不读取 ProGuard 规则；
- 不做 tree shaking；
- 不做 minification；
- 不做 R8 类合并和方法内联；
- 只进行 DEX 生成所需的转换与 desugaring；
- 不生成 Business mapping。

Jiagu 日志必须明确输出：

```text
[Jiagu] businessCompiler=D8, source=variant.isMinifyEnabled(false)
```

### 5.2 `minifyEnabled=true`

Business 编译器不能再把所有输入无条件交给同一次 R8。它应先按 Artifact 自动识别是否已经经过 R8，再分流：

```text
确认未经过 R8 的输入
    -> 使用业务 Variant 的规则执行 R8

确认已经经过 R8 的输入
    -> 禁止再次进入 R8 program inputs
    -> class/JAR 仅用 D8 转换，DEX 直接透传
    -> 同时作为 library inputs 提供给未混淆业务代码的 R8
```

对需要执行 R8 的输入：

- R8 开关来自 Variant；
- 使用业务 `proguardFiles`；
- 自动合并依赖 consumer rules；
- 输出 Business mapping；
- 不再对整个 Payload 或预混淆输入追加 `-repackageclasses`；
- 混合输入时，只允许对首次 R8 的 RAW program inputs 追加专属冲突域；
- 不使用 Jiagu 自定义的全局混淆字典；
- Jiagu 最小规则必须可审计并写入构建报告。

日志必须明确输出：

```text
[Jiagu] businessCompiler=R8, source=variant.isMinifyEnabled(true)
[Jiagu] businessRules=<自动发现数量及摘要>
[Jiagu] consumerRules=<自动发现数量及摘要>
[Jiagu] r8ProgramArtifacts=<确认未经过 R8 的输入>
[Jiagu] preMinifiedArtifacts=<确认已经经过 R8、不会再次进入 R8 的输入>
```

### 5.3 上游已经混淆的模块或 SDK

`variant.isMinifyEnabled()` 表示当前 App Variant 是否希望对 Business 进行 R8，不能证明每个上游 AAR/JAR 是否曾经被独立混淆。

因此自动决策必须分为两层：

1. Variant 层：`variant.isMinifyEnabled()` 决定当前业务是否要求混淆；
2. Artifact 层：判断每个输入是否已经经过 R8，决定它能否再次进入 R8 program inputs。

#### 5.3.1 Artifact 检测结果

每个 JAR、目录或 DEX 必须得到以下结果之一：

| 结果 | 含义 | `minifyEnabled=true` 时的动作 |
|---|---|---|
| `RAW` | 确认没有经过 R8 | 进入 Business R8 program inputs |
| `R8_PROCESSED` | 确认已经经过 R8 | 不再进入 R8；D8 转换或 DEX 透传 |
| `CONFLICTING_EVIDENCE` | 元数据互相矛盾或同一 Artifact 混有两类输出 | 构建失败 |
| `SUSPICIOUS_UNKNOWN` | 无可靠元数据，但存在明显预混淆特征 | 构建失败并报告证据 |

不得只按整个 Variant 做“一刀切”的第二次 R8。一个业务 Variant 可以同时包含：

- 当前 App 新编译、尚未混淆的 class；
- 未混淆的普通依赖；
- 已经由某个 Library release Variant 做过 R8 的项目模块；
- 厂商提供的预混淆 SDK。

这些输入必须按 Artifact 分流，最终再把各自的 DEX 合并进 JG3。

#### 5.3.2 自动检测证据与优先级

检测按以下优先级执行：

1. **Gradle/AGP 生产者元数据**：项目依赖的 Variant、生产 Task、Artifact 类型以及生产 Variant 的 `minifyEnabled`；
2. **自动关联的 mapping Artifact**：通过 AGP Variant Artifact API 或项目依赖关系获取的 mapping；
3. **R8 compiler marker**：使用 R8 提供的 marker 提取能力检查编译器、版本和模式；
4. **R8 mapping id / SourceFile 元数据**：例如 `r8-map-id-*`，作为补充证据；
5. **类名与结构启发式**：大量根包短类名、R8 synthetic、outline 和 residual signature 只能用于判定“可疑”，不能单独证明未混淆。

判定原则：

- 任一高可信证据确认经过 R8，即标记 `R8_PROCESSED`；
- 项目本地 javac/kotlinc 输出目录以及明确的未 minify Variant，可以标记为 `RAW`；
- 普通外部 JAR 没有 R8 marker 时通常按 `RAW` 处理，但如果同时出现明显 R8/短名特征，则标记 `SUSPICIOUS_UNKNOWN` 并失败；
- mapping 文件是否存在不能单独决定结果，必须同时校验生产者、Variant、mapping id 或 Artifact SHA-256，避免陈旧 mapping 误判；
- 同一个 Artifact 内如果部分 class 显示 R8 marker、部分又显示不兼容的生产信息，标记 `CONFLICTING_EVIDENCE`；
- 检测结果必须写入构建报告，不能只打印一句“已混淆”。

完全移除过 marker、mapping 和生产者元数据的任意第三方字节码，不可能仅靠类文件做到百分之百可靠识别。此时不得假装已经准确判断；具有明显预混淆特征的 release 输入应失败并要求依赖生产方保留可验证元数据。

#### 5.3.3 已 R8 输入的处理

`R8_PROCESSED` Artifact 必须满足：

- 不加入 `R8Command.addProgramFiles(...)`；
- 若输入为 class/JAR，只通过 D8 转为 DEX，保留现有类和成员名称；
- 若输入已经是兼容的 DEX，校验 minSdk、DEX 版本和摘要后直接进入 Payload；
- 作为 library input 提供给其余 RAW 业务代码的 R8，用于类型和成员解析；
- 不向它追加 `-repackageclasses`、`-keep`、优化或裁剪规则；
- 自动关联并归档其已有 mapping；没有 mapping 时记录不可反混淆状态，但不能因此再次 R8；
- 生成前后类描述符集合必须一致，D8 不得造成重命名。

#### 5.3.4 混合输入编译

当 Variant 同时含有 RAW 与 `R8_PROCESSED` 输入时：

```text
RAW artifacts
    -> R8(program；混合模式下输出到 RAW 专属冲突域)
       libraries = boot classpath + Shell ABI + R8_PROCESSED artifacts
    -> raw-business*.dex

R8_PROCESSED class/JAR artifacts
    -> D8 only
    -> preminified*.dex

R8_PROCESSED dex artifacts
    -> validate + pass through

所有 DEX
    -> 类重复检查
    -> 稳定排序和重新编号
    -> JG3
```

如果 RAW 代码与预混淆 Artifact 之间存在无法解析的反向依赖、重复类或不兼容 desugaring，构建必须失败并报告 Artifact 来源，不允许退回到“全部再跑一次 R8”。

#### 5.3.5 长期要求

- 项目内 Android Library 优先发布未混淆 class，并通过 consumer rules 把规则交给最终业务 R8；
- 对无法控制的预混淆第三方 SDK，按黑盒输入处理；
- 自动识别到 `R8_PROCESSED` 后，必须从第二次 R8 的 program inputs 中剔除；
- 检测到“上游预混淆 + 当前 Variant 开启 R8”时输出明确的分流报告；
- 无法获得上游 mapping 时不能伪造完整反混淆链，但仍必须通过最终类冲突门禁。

## 6. Shell 命名隔离

业务类名完全由业务规则决定后，冲突规避责任必须转移到 Jiagu 可控制的 Shell 一侧。

### 6.1 Shell 私有命名空间

为 Shell R8 增加专属命名空间：

```text
io.github.xjc.jiagu.shell.r8.**
```

Shell 类分为两类：

1. 必须保持原名的类：Manifest 组件、JNI 入口、反射入口、Shared ABI、Jiagu 固定 Runtime 类；
2. 可以改名的类：统一重打包到 Shell 专属命名空间，不允许产生根包短名，如 `a.a`、`k.a`。

Payload 禁止包含 `io.github.xjc.jiagu.shell.**`。如果业务源码或预混淆依赖占用该前缀，构建直接失败。

Shell 命名规则应通过现有 `shell-keep-rules.pro` 自动注入 AGP 的 Shell R8，不暴露新的业务 DSL。若业务规则声明了冲突的 `-repackageclasses` 或 `-flattenpackagehierarchy`，插件应失败并指出规则来源，而不是依赖规则顺序决定结果。

### 6.2 为什么不再全局重打包 Payload

Payload 可能包含：

- 业务反射；
- JNI 类名注册；
- WebView JavaScript bridge；
- JSON/ORM/序列化字段；
- 第三方 SDK 内部字符串类名；
- 已经执行过 R8 的 AAR/JAR。

Jiagu 对完整 Payload 再追加 `-repackageclasses` 会改变业务方已经验证过的规则边界，并且 `-keep` 类仍可能保留旧短名，不能真正保证命名隔离。因此删除原来的全局规则：

```proguard
-repackageclasses 'io.github.xjc.jiagu.payload.r8'
```

但混合输入存在两个独立命名器：上游 R8 已经生成过 `a.a` 等名称，本次 RAW R8 即使把预混淆 JAR 作为 library，也仍可能再次生成同名描述符。因而混合模式必须为“本次首次 R8 的 RAW 输出”建立独立冲突域，例如：

```proguard
-repackageclasses 'io.github.xjc.jiagu.payload.raw.r8'
```

该规则只注入 RAW R8 命令，`R8_PROCESSED` Artifact 仍只走 D8，类描述符保持不变。合并 DEX 后必须再次执行 Payload 内部类交集检查；若 keep 规则保留的 RAW 原名仍与预混淆类冲突，则构建失败并报告来源。

## 7. 自动 Mapping 管理

### 7.1 不提供手工 mappingFile 配置

mapping 路径由任务输出和 AGP Artifact 自动确定，不允许业务填写绝对路径或 Variant 相关字符串。

建议的统一输出：

```text
build/outputs/jiagu/<variant>/mapping/
  mapping-index.json
  payload-mapping.txt       # 仅 Business R8 时存在
  shell-mapping.txt         # 仅 Shell R8 时存在
  upstream-mappings/        # 能自动发现时归档
```

为兼容现有工具，可继续生成：

```text
build/intermediates/jiagu/<variant>/business-mapping.txt
```

但它应作为内部任务输出，不需要业务配置。

### 7.2 Mapping 来源

- Payload mapping：由 `JiaguTask` 的 Business R8 直接设置输出路径，因此天然可知；
- Shell mapping：通过 AGP Variant Artifact API 获取最终 mapping，而不是扫描目录猜路径；
- 项目依赖 mapping：对 Gradle 能提供 Variant Artifact 的项目模块自动关联；
- 外部预混淆 AAR/JAR：若发布物没有 mapping，只记录“upstream mapping unavailable”。

### 7.3 Mapping 索引

`mapping-index.json` 至少记录：

```json
{
  "variant": "release",
  "businessMinified": true,
  "businessCompiler": "R8",
  "businessCompilerVersion": "8.12.14",
  "payloadMapping": "payload-mapping.txt",
  "shellMapping": "shell-mapping.txt",
  "payloadMappingSha256": "...",
  "shellMappingSha256": "...",
  "upstreamMappings": [
    {
      "component": ":akmsdk_mi",
      "artifactSha256": "...",
      "r8DetectedBy": ["AGP_VARIANT", "R8_MAPPING_ID"],
      "mapping": "upstream-mappings/akmsdk_mi.txt",
      "mappingSha256": "..."
    }
  ],
  "payloadDexSha256": "..."
}
```

`minifyEnabled=false` 时：

- `businessMinified=false`；
- `payloadMapping=null`；
- 必须忽略或清理同目录中的陈旧 mapping，不能让旧文件影响判断。

`payload-mapping.txt` 只描述本次由 Jiagu 协调执行的 Business R8，也就是 RAW 输入。已经是 `R8_PROCESSED` 的 Artifact 不得再次出现在该 mapping 的输入类集合中，它们应分别记录到 `upstreamMappings`。

自动检测报告建议输出：

```text
build/intermediates/jiagu/<variant>/input-index.json
```

每个 Artifact 至少记录：

```json
{
  "component": ":akmsdk_mi",
  "path": "...",
  "sha256": "...",
  "classification": "R8_PROCESSED",
  "evidence": ["AGP_VARIANT", "R8_MAPPING_ID"],
  "compiler": "R8",
  "compilerVersion": "...",
  "action": "D8_ONLY",
  "includedInR8ProgramInputs": false
}
```

## 8. Shell/Payload 类冲突门禁

扩展现有 `VerifyServicesTask`，分别维护以下集合：

```text
payloadDefinitions: Map<ClassName, PayloadDexEntry>
shellDefinitions:   Map<ClassName, ApkDexEntry>
```

禁止先合并集合再检查。校验逻辑：

```java
Set<String> duplicates = new TreeSet<>(payloadDefinitions.keySet());
duplicates.retainAll(shellDefinitions.keySet());
if (!duplicates.isEmpty()) {
    throw new IOException(formatDuplicateReport(duplicates));
}
```

错误报告必须包含：

- 类描述符；
- Shell 所在 `classes*.dex`；
- Payload 所在 `classes*.dex`；
- 两边 superclass/interfaces；
- 如果可用，分别通过 Shell/Payload mapping 还原出的原始类名；
- 修复建议指向 Shell 命名隔离或错误分区，不建议业务盲目增加 keep。

该任务应从 `verifyJiaguServices<Variant>` 扩展/重命名为：

```text
verifyJiaguPackage<Variant>
```

它至少包含：

1. Shell/Payload 类名交集检查；
2. Payload 内跨 DEX 重复定义检查；
3. APK 内跨 DEX 重复定义检查；
4. ServiceLoader 描述符检查；
5. Shell 专属包名前缀检查；
6. Payload 禁止包名前缀检查；
7. Manifest 组件存在性检查；
8. Payload DEX/JG3 长度和摘要检查。

任何一项失败都必须阻止 `assemble`、`bundle` 和发布任务。

## 9. 输入重复类门禁与确定性

将 `processedNames: Set<String>` 改为包含来源信息的索引：

```text
entryName -> {
  originArtifact,
  sha256,
  targetDomain
}
```

处理规则：

- `META-INF/services/*`：继续合并 provider；
- 非 class 资源：使用明确的合并策略；
- 相同类名、相同 SHA-256：允许确定性去重，并记录日志；
- 相同类名、不同 SHA-256：立即失败；
- 不允许“第一个输入获胜”。

所有 JAR、目录、ZIP entry 必须排序后处理。错误报告必须列出两个来源 Artifact，不允许只输出重复类名。

## 10. Shell/Business 分区改造

当前分区主要依赖 `shouldKeepInShell()` 的硬编码包名前缀。长期应升级为“声明 + 自动发现 + 精确闭包”：

### 10.1 强制 Shell 根节点

- Jiagu Runtime 类；
- Shell Application；
- merged manifest 中的 Application、Provider、Receiver、Service、Activity、AppComponentFactory；
- AndroidX Startup Provider 与 initializer；
- 启动日志 uploader；
- JNI `FindClass`/`RegisterNatives` 必需类；
- Payload 加载前必须执行的网络、认证和加密代码。

### 10.2 Shared ABI

Payload 实际引用到的 Shell 类、方法和字段继续通过 TraceReferences 生成精确保留规则。

Shared ABI 的原则：

- 只在 Shell 定义一次；
- Payload 把它作为 library class 使用；
- Shell R8 不得删除或改变 Payload 已编译引用的描述符；
- 动态反射/JNI 入口仍由原项目或 SDK consumer rules 提供，TraceReferences 不能替代动态规则。

### 10.3 依赖归属

同一个第三方库只能归属一个域：

- 仅启动期使用：Shell；
- 仅业务运行期使用：Payload；
- 两边都使用：放入 Shell 作为 Shared ABI，Payload 不再携带副本；
- 不允许 Shell/Payload 各保留不同版本。

## 11. Gradle 任务拆分

将当前职责较多的 `JiaguTask` 逐步拆分为可缓存、可验证的任务：

| Task | 作用 | 主要输出 |
|---|---|---|
| `analyzeJiaguInputs<Variant>` | 排序输入、重复类检查、逐 Artifact R8 自动识别 | `input-index.json` |
| `splitJiaguClasses<Variant>` | Shell/Business 分区 | `shell.jar`、`business.jar`、`partition-index.json` |
| `compileJiaguBusinessDex<Variant>` | 按 Variant 和 Artifact 分类选择 R8、D8 或透传 | Business DEX、Payload mapping |
| `generateJiaguShellRules<Variant>` | TraceReferences 与 Shell 命名策略 | `shell-keep-rules.pro` |
| `packageJiaguPayload<Variant>` | DEX 排序、压缩、JG3、摘要 | `payload.jg3`、摘要 |
| `collectJiaguMappings<Variant>` | 自动收集 Payload/Shell mapping | mapping bundle |
| `verifyJiaguPackage<Variant>` | 最终 APK/AAB 与 Payload 全面验证 | 校验报告 |
| `createJiaguRelease<Variant>` | Release 协议、加密、Native 封装 | release metadata、JNI 输出 |

任务之间只通过声明式 Artifact 传递文件，不通过固定 `build/` 路径互相猜测。

## 12. 兼容性与迁移策略

### 阶段 0：先增加只读诊断

- 输出 Variant R8 自动判断结果；
- 输出所有业务/consumer 规则来源；
- 按 Artifact 输出 `RAW`、`R8_PROCESSED`、`CONFLICTING_EVIDENCE`、`SUSPICIOUS_UNKNOWN` 分类及证据；
- 输出实际进入 `R8Command.addProgramFiles(...)` 的 Artifact 清单；
- 生成 Shell/Payload 类交集报告，但暂时只警告；
- 用当前故障样本验证能稳定报告 `k.a`。

### 阶段 1：启用强制门禁

- 类交集从 warning 改为 build failure；
- 输入不同字节重复类改为 build failure；
- `R8_PROCESSED` Artifact 再次进入 R8 program inputs 时直接失败；
- `CONFLICTING_EVIDENCE` 和 `SUSPICIOUS_UNKNOWN` 的 release 构建直接失败；
- Service、Manifest、JG3 校验统一纳入 `verifyJiaguPackage`；
- `assemble`、`bundle`、publish 都依赖该校验。

### 阶段 2：调整命名所有权

- 删除作用于完整 Business Payload 的全局 `-repackageclasses`；
- 混合模式只隔离 RAW R8 的新命名域；
- 实现 RAW、`R8_PROCESSED` 混合输入的 R8/D8/DEX 透传管线；
- 给 Shell 引入专属命名空间；
- 保留 Shell Manifest/JNI/反射/Shared ABI 原名；
- 对业务工程不增加任何新 DSL。

### 阶段 3：自动 Mapping 与崩溃链路

- 通过 Artifact API 收集 Shell mapping；
- 自动归档 Payload mapping；
- 自动关联已 R8 项目依赖的 upstream mapping；
- 输出 mapping index 与 SHA-256；
- 提供 Shell/Payload 两阶段 retrace 工具或任务。

### 阶段 4：清理当前单体任务

- 拆分任务；
- 增加 Gradle TestKit 测试；
- 建立 AGP/R8 支持矩阵；
- 对不支持的 AGP/R8 组合明确失败，不静默降级。

## 13. 回归测试矩阵

至少覆盖：

| 场景 | 预期 |
|---|---|
| Debug，`minifyEnabled=false` | Business 走 D8，无 mapping |
| Release，`minifyEnabled=true`，全部 RAW | Business 走一次 R8，自动生成 mapping |
| Release，`minifyEnabled=true`，某项目依赖已 R8 | 该依赖被识别为 `R8_PROCESSED`，不进入第二次 R8 |
| RAW 与 `R8_PROCESSED` 混合输入 | RAW 走 R8，预混淆 class/JAR 走 D8，预混淆 DEX 透传 |
| `R8_PROCESSED` 项目依赖存在 mapping | 自动关联并归档，不要求手工路径 |
| `R8_PROCESSED` 外部 SDK 没有 mapping | 不再次 R8，报告无法反混淆及 Artifact 摘要 |
| 陈旧 mapping 残留但 Artifact/Variant 不匹配 | 不得据此判定已经 R8 |
| Artifact 元数据互相矛盾 | 标记 `CONFLICTING_EVIDENCE` 并失败 |
| 无可靠元数据且出现明显预混淆特征 | 标记 `SUSPICIOUS_UNKNOWN` 并失败 |
| 已 R8 的 `MutiImageView -> k.a` 输入 | `k.a` 名称不被第二次 R8 改写，最终无 Shell 冲突 |
| 业务缺少反射 keep | 行为由业务规则负责，Jiagu 不擅自补全 |
| Shell 与 Payload 都生成 `k.a` | 构建期明确失败 |
| 上游同名类字节一致 | 确定性去重并记录 |
| 上游同名类字节不同 | 构建期失败并报告来源 |
| 多 DEX Payload | 每个 DEX 都参与冲突和摘要检查 |
| ServiceLoader | 描述符和实现类均可加载 |
| Manifest Provider 冷启动 | Payload 安装前后顺序正确 |
| APK 与 AAB | 两种产物都执行最终验证 |
| 重复构建 | 输入相同时 Payload 与索引摘要一致 |

设备验证至少覆盖最低支持 API、主力 API 和最新 API。启动日志中不得出现：

```text
VerifyError
ClassNotFoundException
NoClassDefFoundError
NoSuchMethodError
NoSuchFieldError
IllegalAccessError
ClassCastException
```

## 14. 验收标准

完成本次架构改造后必须满足：

1. 业务工程不配置 Jiagu 专属 optimization 开关；
2. 业务是否 R8 完全跟随 `variant.isMinifyEnabled()`；
3. `minifyEnabled=false` 时 Jiagu 不调用 R8；
4. `minifyEnabled=true` 时先逐 Artifact 自动识别，再只对 RAW 输入执行 R8；
5. 已确认 `R8_PROCESSED` 的 Artifact 不得出现在任何第二次 R8 的 program inputs 中；
6. 已 R8 class/JAR 仅允许 D8 转换，已 R8 DEX 仅允许校验后透传；
7. D8/透传前后的预混淆类描述符集合必须保持一致；
8. RAW 输入的 R8 使用业务规则和 consumer rules；
9. 自动检测必须至少综合 Gradle/AGP 生产者、mapping Artifact、R8 marker 和 mapping id；
10. 不能可靠判断且具有预混淆特征的 release Artifact 必须失败，不能猜测后再次 R8；
11. `input-index.json` 必须记录每个 Artifact 的分类、证据、动作及是否进入 R8 program inputs；
12. Business 不再被 Jiagu 全局强制 `repackageclasses`；混合模式仅允许隔离 RAW R8，且不得改写 `R8_PROCESSED` 类名；
13. 当前 R8 自动生成的 mapping、Shell mapping 和可发现的 upstream mapping 全部自动收集和索引；
14. mapping 缺失不能成为再次 R8 已混淆 Artifact 的理由；
15. 不要求新增 `entrypointClass`；
16. Shell/Payload 最终类描述符交集必须为零；
17. 重复输入类不再静默覆盖；
18. 当前 `k.a` 故障样本必须在构建阶段被拦截，并验证预混淆 `k.a` 没有进入第二次 R8；
19. Debug、Release、APK、AAB 都有一致的验证路径；
20. 旧业务工程只需升级插件，不需要新增 DSL 即可获得默认行为。

## 15. 本轮不做的事项

- 不引入 Payload Entrypoint 协议；
- 不强制业务改成独立插件框架；
- 不切换到 child-first ClassLoader；
- 不替业务生成通用反射/JNI 白名单；
- 不通过扫描 mapping 文件是否存在决定是否启用 R8；
- 不允许用关闭 VerifyError 校验、延迟 Provider 初始化等方式掩盖类冲突。

## 16. 主要实施位置

- `dex-report-plugin/src/main/java/io/github/xjc/dexreport/JiaguTask.java`
  - D8/R8 策略拆分；
  - 删除全局 Payload `-repackageclasses`，仅在混合模式隔离 RAW R8 命名域；
  - 输入重复类来源追踪；
  - 业务编译报告。
- `dex-report-plugin/src/main/java/io/github/xjc/dexreport/DexReportPlugin.java`
  - Variant 自动配置；
  - AGP mapping Artifact 接线；
  - 新任务及依赖关系。
- `dex-report-plugin/src/main/java/io/github/xjc/dexreport/ShellKeepRules.java`
  - Shared ABI 精确保留；
  - Shell 专属命名策略。
- `dex-report-plugin/src/main/java/io/github/xjc/dexreport/VerifyServicesTask.java`
  - 扩展为完整包校验；
  - Shell/Payload 类交集门禁；
  - 最终类层级与来源报告。
- `dex-report-plugin/src/test/java/io/github/xjc/dexreport/`
  - 增加自动 R8 判断、重复类、命名冲突、mapping 自动收集测试。

## 17. 实施状态（2026-09-11）

第一阶段已实现：

- Variant 的 `isMinifyEnabled` 继续作为是否调用 Business R8 的唯一开关；
- class/JAR Artifact 基于 `~~R8{...}` 与 `r8-map-id-*` 双强证据自动分类；
- 同一 Artifact 只有全部可进入 DEX 的 class 都带强证据时才判为 `R8_PROCESSED`，混合证据直接失败；
- `R8_PROCESSED` class/JAR 从 R8 program inputs 剔除，仅走独立 D8；
- RAW 与预混淆 DEX 分通道生成、稳定重编号，并在进入 JG3 前检查重复类；
- 混合模式给 RAW R8 使用 `io.github.xjc.jiagu.payload.raw.r8` 冲突域；
- Shell 可改名类和 R8 synthetic 使用 `io.github.xjc.jiagu.shell.r8` 专属命名域；
- 输入不同字节码的重复业务类不再静默覆盖，并报告两个来源；
- `input-index.json` 已记录路径、摘要、分类、证据与实际动作；
- APK 校验已增加 Payload 内重复类、APK DEX 内重复类及 Shell/Payload 类交集门禁。

当前故障工程的静态验收结果：226 个 Artifact 中自动识别 1 个 `R8_PROCESSED`（`akmsdk_mi`，201/201 class 带强证据）；Payload 47,116 类、Shell 2,553 类、Payload 内重复为 0、Shell/Payload 交集为 0；`k.a` 只存在于 Payload。

后续阶段仍需完成：

- 从 AGP producer/Variant 元数据与 mapping Artifact 增加高优先级识别证据；
- 支持已是 DEX 的 `R8_PROCESSED` Artifact 校验后直接透传；
- 自动归档 upstream mapping、Business mapping、Shell mapping 并生成统一索引；
- 为 AAB 增加与 APK 等价的最终类空间校验；
- 将当前 `VerifyServicesTask` 命名和职责升级为统一的 `verifyJiaguPackage`。

后续实施应以本文档的设计决策和验收标准为准；如果调整 R8 所有权、ClassLoader 模型或新增 Entrypoint，需要先更新本文档，再修改实现。
