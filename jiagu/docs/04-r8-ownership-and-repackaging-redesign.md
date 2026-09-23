# Jiagu 业务字节码保真与 Shell R8 所有权设计

## 1. 结论

Jiagu 不拥有业务代码的 R8、裁剪、优化、重命名或重打包权。无论业务输入是 RAW class/JAR，还是已经由上游 R8 处理过的 class/JAR，Jiagu 都只使用 D8 将其转换为 Payload DEX，并保持输入字节码的类和成员描述符不变。

只有 Jiagu 自己拥有的 Shell 和 Runtime 代码可以由 APK 外层的 AGP R8 处理。Shell R8 的职责是缩减和重命名壳代码，并通过专属命名空间避免与 Payload 的现有描述符冲突。

这条边界消除了“预混淆 SDK 引用 RAW 依赖、但 RAW 依赖又被 Jiagu 二次 R8 改名”的问题。Jackson 的 `SerializationFeature` 崩溃正是该问题的实例：`l.e` 保留了对 `com.fasterxml.jackson.databind.SerializationFeature` 的引用，而二次 R8 将目标类改为另一个描述符，导致运行时找不到原名。

## 2. 所有权模型

| 代码域 | 所有者 | Jiagu 动作 | 名称策略 |
| --- | --- | --- | --- |
| 业务 App、业务模块、第三方 SDK | 业务方/SDK 提供方 | D8 → Payload DEX → JG3 | 保持输入描述符 |
| 已 R8 的业务模块/SDK | 上游构建方 | D8 → Payload DEX → JG3 | 保持上游 R8 描述符 |
| Jiagu Runtime、授权/解密所需依赖 | Jiagu | 留在 Shell，交给 AGP Shell R8 | 原名或 `io.github.xjc.jiagu.shell.r8.**` |
| Shell 与 Payload 的共享 ABI | Jiagu | TraceReferences 生成 Shell keep rules | 必须保持 Payload 所引用的原描述符 |
| Android boot classpath | Android 平台 | 仅作为 D8/R8 library | 平台原名 |

Payload 的业务编译器始终为 D8；当前插件没有把应用 Variant 的 ProGuard/R8 规则交给 Payload R8。local 模式下 `shellMinificationEnabled` 会在 `beforeVariants` 设置 AGP Variant 的最终 `minifyEnabled`，因此应用的 `android.buildTypes.*.minifyEnabled` 会被覆盖；该次 R8 仅处理 Jiagu transform 输出的 Shell classes artifact。online 模式则不覆盖 Variant 值；若 `minifyEnabled=true` 而仍有业务 class 被路由至 Payload，当前策略会拒绝构建，而不是悄悄跳过 Payload 混淆。

## 3. 构建流程

```text
AGP Scoped CLASSES
        |
        |-- Shell/Runtime/启动前必需代码 --> shell.jar
        |                                      |
        |                                      +--> AGP Shell R8/D8
        |
        +-- 所有其余业务 class/JAR ----------> business.jar
                                               |
                                               +--> D8 only
                                               +--> Payload DEX
                                               +--> JG3 加密/封装
```

业务输入不再按 `RAW` 和 `R8_PROCESSED` 选择 Jiagu R8 策略：Jiagu 对 Payload 输入只使用 D8，不执行 tree shaking、优化或类/成员重命名，因此输入描述符保持一致。业务方的应用级 `proguardFiles` 和 SDK consumer rules 会被 AGP 消费，但作用域是最终 Shell R8，不是 Payload；规则中只针对已路由到 Payload 的类的 keep 条目不会替 Payload 执行混淆。若业务需 Payload 混淆，须在业务上游构建中处理并保存对应 mapping，或实现独立 Payload R8 流程。

## 4. Shell/Payload 边界

Shell 只应包含：

- Jiagu Runtime 与 JNI 入口；
- Proxy Application、授权、解密和 Payload 注入前必须运行的代码；
- Shell 启动期间必需的 HTTP、加密和平台依赖；
- Manifest 组件、资源 R 类及明确配置的启动上传器；
- 被 Payload 静态引用的 Shell ABI。

Payload 的 DEX 在 `attachBaseContext` 中解密后注入应用 ClassLoader。Runtime 会把 Payload `dexElements` 插到 Shell 前面；因此最终 APK Shell 与 Payload 不能包含相同的类描述符。构建完成后必须检查 Payload 内部重复定义及 Shell/Payload 交集。

Shell R8 规则由 `ShellKeepRules` 根据最终 Payload DEX 生成：对 Payload 静态引用的 Shell 类、成员和继承关系保持描述符；其余可改名 Shell 类使用：

```proguard
-repackageclasses 'io.github.xjc.jiagu.shell.r8'
```

Payload 禁止包含 `io.github.xjc.jiagu.shell.**`。若业务代码占用该前缀，构建必须失败。

## 5. 输入审计

Jiagu 仍检测 `R8_PROCESSED` 元数据，但仅用于审计、上游 mapping 归档与诊断，不参与业务编译策略。`input-index.json` 的每个业务 Artifact 都必须显示：

```json
{
  "classification": "RAW | R8_PROCESSED | CONFLICTING_EVIDENCE",
  "action": "D8_PRESERVE_BUSINESS_BYTECODE",
  "r8ProgramInput": false
}
```

`CONFLICTING_EVIDENCE` 不会触发业务二次 R8；它应作为供应链诊断信息报告。相同 class descriptor 且字节码不同仍是确定性错误，必须在切分阶段失败。

业务 mapping 不再由 Jiagu 生成。崩溃还原需要：

- 业务方常规构建已经产生的 mapping；
- 上游预混淆 AAR/JAR 的 mapping（若提供）；
- AGP Shell R8 mapping。

## 6. 验收标准

1. 任意业务 RAW class/JAR 只经过 D8，不进入 Jiagu R8；
2. 任意 `R8_PROCESSED` 业务 class/JAR 只经过 D8，不发生二次 R8；
3. Jiagu 不将业务 ProGuard/consumer rules 用作 Payload R8 输入；这些规则可能由 AGP Shell R8 消费，因此必须按 Shell 归属审计；
4. 业务 DEX 中对外部类的描述符必须与上游输入一致；
5. Shell R8 只能处理 shell.jar，且 Shared ABI keep rules 生效；
6. Payload 内无跨 DEX 重复类，Shell 与 Payload 的类描述符交集为空；
7. `input-index.json` 中所有业务 Artifact 的 `r8ProgramInput=false`；
8. 使用“预 R8 SDK + 未 R8 Jackson”构建时，`l.e` 可解析原始 `com.fasterxml.jackson.databind.SerializationFeature`。

## 7. 迁移

删除旧设计中的业务 R8 分支、RAW 专属 `payload.raw.r8` 命名域、RAW 依赖闭包重写逻辑以及 `business-mapping.txt` 输出。业务方不需要增加全局 `-keep` 来保护 Payload 的符号；原有业务 ProGuard 文件不作为 Jiagu Payload R8 输入，但仍会由 AGP 消费并影响 Shell R8，因此须检查规则是否仍适用于 Shell。
