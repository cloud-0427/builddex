# Jiagu 业务字节码保真与 Shell R8 所有权设计

## 1. 结论

Jiagu 不拥有业务代码的 R8、裁剪、优化、重命名或重打包权。无论业务输入是 RAW class/JAR，还是已经由上游 R8 处理过的 class/JAR，Jiagu 都只使用 D8 将其转换为 Payload DEX，并保持输入字节码的类和成员描述符不变。

只有显式归属 Jiagu 的 Runtime classes 可以由 R8 处理。第三方 SDK 即使留在 Shell，也不得作为 R8 program input；Runtime R8 仅将第三方作为只读 library classpath。R8 输出 Runtime classfile，与未改动的 Shell pass-through classfile 合并后由 AGP D8 转换为 DEX。第三方留在 Shell 同时又选择性进入 R8 的需求已识别，但本阶段明确不支持，未来另行评估。

这条边界消除了“预混淆 SDK 引用 RAW 依赖、但 RAW 依赖又被 Jiagu 二次 R8 改名”的问题。Jackson 的 `SerializationFeature` 崩溃正是该问题的实例：`l.e` 保留了对 `com.fasterxml.jackson.databind.SerializationFeature` 的引用，而二次 R8 将目标类改为另一个描述符，导致运行时找不到原名。

## 2. 所有权模型

| 代码域 | 所有者 | Jiagu 动作 | 名称策略 |
| --- | --- | --- | --- |
| App 业务代码 | 业务方 | Jiagu D8 → Payload DEX → JG4/JGLP | 保持输入描述符；需混淆时由上游完成 |
| 已预混淆的业务模块/SDK | 上游构建方 | Jiagu D8 → Payload 或 pass-through DEX | 保持上游 R8 描述符与 mapping 关系 |
| 第三方 AAR/JAR | SDK 提供方 | Shell pass-through D8；不作为 R8 program input | 保留上游类名、成员名及可观察语义 |
| Jiagu Runtime | Jiagu | 独立 Runtime R8 program → optimized classfile → AGP D8 → Shell DEX | 仅 Jiagu Runtime mapping；受 Runtime 专属规则控制 |
| Runtime → 第三方 library ABI | 第三方提供方 | 第三方作为 Runtime R8 library input | library descriptor 不变；不输出/改写第三方 class |
| Payload → Runtime 共享 ABI | Jiagu | TraceReferences/边界审计，生成 Runtime R8 keep rules | 保持 Payload 引用的原描述符 |
| Payload/Runtime → 第三方 ABI | 第三方提供方 | DEX 静态链接检查；pass-through 目标不需要 R8 keep | 必须存在且保持原描述符 |
| Android boot classpath | Android 平台 | 仅作为 D8/R8 library | 平台原名 |

Payload 的业务编译器始终为 D8；当前插件没有把应用 Variant 的 ProGuard/R8 规则交给 Payload R8。local 且 Jiagu 流水线生效时，插件关闭 AGP whole-program R8，再由 `shellMinificationEnabled` 控制 Jiagu Runtime-only R8；该 R8 输出 classfile，后续由 AGP D8 转换。应用 Variant 的 `minifyEnabled` 不再控制该 Runtime R8；online 模式则不覆盖 Variant 值，若 `minifyEnabled=true` 而仍有业务 class 被路由至 Payload，当前策略会拒绝构建。

## 3. 构建流程

```text
AGP class inputs + internal Runtime package allowlist
        |
        |-- App/business classes --------------------------> D8 --> Payload DEX --> encrypt
        |
        |-- :jiagu-runtime classes ------------------------> Runtime-only R8 --> Runtime classfile --+
        |                                                     third-party libraries as classpath only |
        |
        |-- external third-party AAR/JAR classes ----------> unchanged classfiles -----------------+
        |
        +-- third-party resources/manifest/assets/native --> normal AGP merge/package
                                                                  |
                                           AGP D8 -----------------+--> final APK DEX
```

代码的外部 artifact 仍是 Shell/Payload 两路，但 Shell 内部已分成 Runtime-only R8 classfile 与 pass-through classfile 两个处理域；二者统一由 AGP D8。Runtime R8 allowlist 固定在 Jiagu 插件内部 namespace，不要求业务工程逐个列出第三方或 Runtime 模块；详见 [Payload 白名单与第三方 Provider 稳定启动设计](08-payload-allowlist-and-provider-startup-design.md) 的 3.4 节。

业务输入不再按 `RAW` 和 `R8_PROCESSED` 选择 Jiagu Payload R8 策略：Payload 固定只使用 D8，不执行 tree shaking、优化或类/成员重命名。local 模式已将 Runtime R8 rules 与业务 App `proguardFiles` 隔离，第三方 consumer rules 不用于改变第三方 class；第三方仅作为 Runtime R8 library input。online 模式仍按 AGP Variant 规则处理 Shell。业务方需混淆 Payload 时，应在业务上游构建中处理并保存对应 mapping，或实现独立 Payload R8 流程。

## 4. Shell/Payload 边界

Shell 只应包含：

- Jiagu Runtime 与 JNI 入口；
- Proxy Application、授权、解密和 Payload 注入前必须运行的代码；
- Shell 启动期间必需的 HTTP、加密和平台依赖；
- Manifest 组件、资源 R 类及明确配置的启动上传器；
- 被 Payload 静态引用的 Shell ABI。

Payload 的 DEX 在 `attachBaseContext` 中解密后注入应用 ClassLoader。Runtime 会把 Payload `dexElements` 插到 Shell 前面；因此最终 APK Shell 与 Payload 不能包含相同的类描述符。构建完成后必须检查 Payload 内部重复定义及 Shell/Payload 交集。

Runtime R8 规则由 Runtime 专属规则与 Payload→Runtime ABI 扫描生成：只 keep Payload 静态引用的 Runtime 类、成员和继承关系；第三方 pass-through DEX 不受这些 keep/obfuscation 规则控制。Runtime 其余可改名 class 使用：

```proguard
-repackageclasses 'io.github.xjc.jiagu.shell.r8'
```

Payload 禁止包含 `io.github.xjc.jiagu.shell.**`。若业务代码占用该前缀，构建必须失败。

## 5. 输入审计

Jiagu 仍检测 `R8_PROCESSED` 元数据，但仅用于审计、上游 mapping 归档与诊断，不参与 Payload 编译策略。目标架构中 `input-index.json` 的每个 Artifact 都必须包含来源、lane 和 R8 身份：

```json
{
  "classification": "RAW | R8_PROCESSED | CONFLICTING_EVIDENCE",
  "action": "D8_PRESERVE_DESCRIPTORS",
  "lane": "PAYLOAD | RUNTIME_R8 | SHELL_PASSTHROUGH",
  "r8ProgramInput": false,
  "componentId": "com.vendor:sdk:1.2.3"
}
```

对 plain file dependency 等无法关联 Gradle component 的输入，`componentId` 可为空；lane 必须仍能由 Runtime package allowlist 与 Payload routing 确定。

`CONFLICTING_EVIDENCE` 不会触发业务二次 R8；它应作为供应链诊断信息报告。相同 class descriptor 且字节码不同仍是确定性错误，必须在切分阶段失败。

业务 mapping 不再由 Jiagu 生成。目标三路架构中第三方 pass-through lane 无 R8 mapping；崩溃还原需要：

- 业务方常规构建已经产生的 mapping；
- 上游预混淆 AAR/JAR 的 mapping（若提供）；
- Jiagu Runtime-only R8 mapping（不得与业务上游 mapping 混用）。

## 6. 验收标准

1. 任意业务 RAW class/JAR 只经过 D8，不进入 Jiagu R8；
2. 任意 `R8_PROCESSED` 业务 class/JAR 只经过 D8，不发生二次 R8；
3. Jiagu 不将业务 ProGuard/consumer rules 用作 Payload R8 输入；local Runtime-only R8 已与业务 `proguardFiles` 隔离，online 模式仍按 AGP Variant 自身配置处理；
4. 业务 DEX 中对外部类的描述符必须与上游输入一致；
5. 目标 Runtime R8 只能处理 Jiagu Runtime program classes；third-party artifacts 只能作为 library input 或单独 D8 pass-through，绝不成为 R8 program input；
6. Payload 内无跨 DEX 重复类，Shell 与 Payload 的类描述符交集为空；
7. `input-index.json` 中所有业务 Artifact 的 `r8ProgramInput=false`；
8. 使用“预 R8 SDK + 未 R8 Jackson”构建时，`l.e` 可解析原始 `com.fasterxml.jackson.databind.SerializationFeature`。

## 7. 迁移

删除旧设计中的业务 R8 分支、RAW 专属 `payload.raw.r8` 命名域、RAW 依赖闭包重写逻辑以及 `business-mapping.txt` 输出。业务方不需要增加全局 `-keep` 来保护 Payload 的符号；local 模式由插件内部 Runtime namespace allowlist 选择 Runtime R8 program，Shell 其余 classes 保持 classfile 并与 Runtime R8 classfile 回接 AGP `ScopedArtifact.CLASSES`，再由 AGP D8 统一编译。本阶段不支持第三方选择性进入 R8，也不要求维护第三方名单。Runtime 规则隔离与 classfile 回接细节见 [Payload 白名单与第三方 Provider 稳定启动设计](08-payload-allowlist-and-provider-startup-design.md)。
