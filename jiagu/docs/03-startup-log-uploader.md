# 启动阶段事件上报设计

> 后续可靠性改造见 [05-startup-telemetry-reliability.md](05-startup-telemetry-reliability.md)。
> 本文保留阶段协议和历史非持久化方案；持久化、投递时机、补传、首帧超时及统计口径的目标设计以 05 为准。

本文定义加固后 APK 的启动阶段事件模型。目标是以一次进程启动为一个
`sessionId`，从壳加载、授权解密、业务 DEX 装载，一直观测到首个 Activity 的
首帧绘制。

> 本文中的 12 个阶段由 Runtime、Native 和插件共同实现。升级期间，服务端如需兼容
> 旧版本，可继续识别旧的 `DECRYPT_STARTED` 与 `STARTUP_COMPLETED` 事件。

## 目标与边界

- 每个关键阶段都能得到开始、成功或失败的可关联记录。
- 事件必须覆盖壳早期路径；此时业务 DEX 尚未加载，不能依赖业务 SDK。
- 首屏完成以 **首个 Activity 的第一帧提交绘制** 为准；`onActivityResumed` 只表示
  Activity 进入前台，不表示用户已经看到内容。
- 上报是 best-effort，不得阻塞、改变或中断加固启动链路。
- 事件不得包含 token、credential、payload key、服务端响应正文、完整签名内容或
  完整异常堆栈。

## 首期 12 个阶段

`STARTUP_ATTEMPT` 使用 `STARTED` 记录一次整体启动尝试；其余阶段只上报终态：
`SUCCEEDED`、`FAILED`、`SKIPPED` 或安全策略终止时的 `BLOCKED`。Reporter 会在本地记录
每个阶段的起点以计算 `stageDurationMs`，但不会将这些内部开始点分发给 uploader。

| stageId | stage | 成功边界 | 终态 `resultCode` 示例 | 预期插点 |
| --- | --- | --- | --- | --- |
| 1 | `STARTUP_ATTEMPT` | 整体启动尝试已记录；仅发送 `STARTED` | — | `System.loadLibrary("jiagu-core")` 前 |
| 2 | `SHELL_CORE_LOAD` | `jiagu-core` 已成功加载并注册 JNI | `CORE_LIBRARY_LOAD_FAILED` | `ProxyApplication` 静态初始化块 |
| 3 | `SHELL_ATTACH` | `ProxyApplication.attachBaseContext()` 完成 | `SHELL_ATTACH_FAILED` | `ProxyApplication.attachBaseContext()` |
| 4 | `RUNTIME_PROTECTION_CHECK` | 反调试、反 Hook、签名校验均通过，或相应开关关闭 | `DEBUGGER_DETECTED`、`HOOK_FRAMEWORK_DETECTED`、`SIGNATURE_MISMATCH` | `native_attach()` |
| 5 | `RUNTIME_BUNDLE_LOAD` | `liblog_ext.so`、导出符号和 JGRC 头校验通过 | `RUNTIME_BUNDLE_LOAD_FAILED`、`RUNTIME_BUNDLE_INVALID` | `native_attach()` |
| 6 | `DEVICE_AUTHORIZATION` | 获得可用 payload key / 授权材料 | `KEYSTORE_UNAVAILABLE`、`NETWORK_TIMEOUT`、`NETWORK_REJECTED` | `NetworkHelper.getAuthorizedPayload()` |
| 7 | `PAYLOAD_DECRYPT` | 本地 payload 完成 AES-GCM 解密与认证校验 | `PAYLOAD_AUTHENTICATION_FAILED`、`PAYLOAD_INVALID` | `NetworkHelper.decryptLocalPayload()` |
| 8 | `BUSINESS_DEX_DECOMPRESS` | 所有加密业务 DEX 解压到 DirectBuffer | `DEX_DECOMPRESS_FAILED`、`DEX_ENTRY_INVALID` | Native DEX 解压循环 |
| 9 | `BUSINESS_CLASSLOADER_INJECT` | `InMemoryDexClassLoader` 创建并注入 `dexElements` | `DEX_CLASSLOADER_CREATE_FAILED`、`DEX_INJECTION_FAILED` | Native ClassLoader 注入逻辑 |
| 10 | `REAL_APPLICATION_ATTACH` | 真实 Application 已创建、绑定并执行 `attach(Context)` | `REAL_APPLICATION_CREATE_FAILED`、`REAL_APPLICATION_BIND_FAILED`、`REAL_APPLICATION_ATTACH_FAILED` | Native 真实 Application 替换逻辑 |
| 11 | `REAL_APPLICATION_ON_CREATE` | 真实 `Application.onCreate()` 返回 | `REAL_APPLICATION_ON_CREATE_FAILED` | `native_on_create()` |
| 12 | `FIRST_ACTIVITY_FIRST_FRAME` | 本 session 首个 Activity 首次 `OnPreDraw` / Choreographer 帧回调到达 | `FIRST_FRAME_TIMEOUT` | 真实 Application 的 `ActivityLifecycleCallbacks` 与首个 Activity 的 DecorView |

### 第 6 阶段的授权子结果

`DEVICE_AUTHORIZATION` 必须携带 `authorizationSource`，用于区分缓存命中和网络
依赖；它不是新的顶层 stage。该阶段成功时，`resultCode` 与
`authorizationSource` 使用同一个值；两者同时保留，使服务端无需依赖其他字段即可
按通用 `resultCode` 聚合，也能通过强类型字段分析授权路径。

| 值 | 是否联网 | 含义 |
| --- | --- | --- |
| `LOCAL_AUTHORIZATION_CACHE` | 否 | 本地 grant 与 wrapped payload key 校验通过，并可由 Keystore 解封。 |
| `NETWORK_BOOTSTRAP` | 是 | 无可用 device credential，使用新版 bootstrap。 |
| `NETWORK_AUTHORIZE` | 是 | credential 可用但授权缓存失效，重新授权。 |
| `NETWORK_LEGACY_ENROLL_AUTHORIZE` | 是 | bootstrap 不可用，回退到 enroll + authorize。 |
| `UNKNOWN` | 不确定 | 授权尚未完成，或阶段在授权前已失败。 |

可选的细粒度诊断事件（challenge、Integrity token、credential cache hit/miss、
authorization cache hit/miss）应作为第 6 阶段的属性或采样诊断事件，而不是首期
必发事件，以控制早期启动的事件量。

## 统一事件结构

新协议应以通用 `stageId + stage + status` 替代持续扩张的事件枚举。`stageId` 固定为
上表的 1–12，协议演进时不得改变既有编号；`stage` 是同一编号的可读枚举名。建议
`JiaguStartupEvent` 至少提供以下字段：

```text
sessionId                 // 每次进程启动生成；所有阶段共享
startupInstanceId         // App 数据生命周期内稳定的随机安装实例标识；清数据/重装后变化
stageId                   // 固定 1..12；用于排序、漏斗及服务端兼容
stage                     // 上表中的阶段名
status                    // STARTUP_ATTEMPT 使用 STARTED；其余阶段仅使用终态状态
resultCode                // 终态必填；STARTUP_ATTEMPT 为 null
occurredAtMillis          // 墙上时间，用于服务端排序
elapsedSinceStartMs       // 相对 SHELL_CORE_LOAD 开始的单调时钟耗时
stageDurationMs           // 本阶段耗时；仅结束状态填写
packageName
versionName / versionCode
processName
isMainProcess
isFirstLaunch
authorizationSource       // 第 6 阶段成功后填写；未决议/失败时为 UNKNOWN
networkAuthorizationRequired // 第 6 阶段成功后为 true/false；未决议时为 null
activityName              // 仅首 Activity；建议上传 hash 或经白名单映射的名称
activityResumedElapsedMs  // 首个 Activity onResume 时的耗时；首帧前的内部检查点
failureClass              // 可选的脱敏异常类别，不上传 message/stacktrace
```

`resultCode` 是所有终态事件的统一结果字段：例如检查通过为 `CHECK_PASSED`，签名
拦截为 `SIGNATURE_MISMATCH`，DEX 解压失败为 `DEX_DECOMPRESS_FAILED`。第 6 阶段的
成功结果直接使用 `LOCAL_AUTHORIZATION_CACHE`、`NETWORK_BOOTSTRAP`、
`NETWORK_AUTHORIZE` 或 `NETWORK_LEGACY_ENROLL_AUTHORIZE`；失败时使用
`NETWORK_TIMEOUT`、`NETWORK_REJECTED`、`KEYSTORE_UNAVAILABLE` 等稳定错误码。

服务端幂等键应使用：`packageName + versionCode + sessionId + stageId + status`。
除 `STARTUP_ATTEMPT` 外，同一阶段只产生一个终态事件；重试上传不得产生新的业务事件。

## 生命周期与时间线

```text
STARTUP_ATTEMPT
  → SHELL_CORE_LOAD
  → SHELL_ATTACH
  → RUNTIME_PROTECTION_CHECK
  → RUNTIME_BUNDLE_LOAD
  → DEVICE_AUTHORIZATION
  → PAYLOAD_DECRYPT
  → BUSINESS_DEX_DECOMPRESS
  → BUSINESS_CLASSLOADER_INJECT
  → REAL_APPLICATION_ATTACH
  → REAL_APPLICATION_ON_CREATE
  → FIRST_ACTIVITY_FIRST_FRAME
```

第 12 阶段只记录本 session 的第一个 Activity。它不必是 Launcher Activity：深链、
通知或恢复任务都可能使其他 Activity 成为第一个被创建的界面。应记录其受控标识，
并在 `FIRST_ACTIVITY_FIRST_FRAME` 上报时附带从启动开始至首帧的总耗时。

业务若还需要“数据已就绪”的定义，可另行主动发送 `FIRST_ACTIVITY_FULLY_DRAWN`；它不
属于加固 Runtime 的首期 12 阶段，因为框架无法可靠判断异步内容何时完成。

## 上报、落盘与失败处理

`startupLogUploaderClass` 仍是可选配置：未配置时，事件只写入 Logcat
（`Jiagu_Startup`），不反射加载使用方代码。

```groovy
dexReport {
    startupLogUploaderClass = "com.example.StartupUploader"
}
```

uploader 必须有 public 无参构造器并实现 `JiaguStartupLogUploader`。插件应把 uploader
及其内部类保留在壳 DEX，并生成 R8 keep 规则。它只能使用 Android 平台 API 或明确
保留在壳内的依赖。

事件分发可在后台线程执行。当前实施阶段采用**不跨进程持久化**的有界内存队列：进程
结束时尚未发送的事件允许丢失，不能把 HTTP 送达作为启动成功或安全拦截的前提。持久化
队列是后续可选增强，而不是本阶段的依赖。

网络上传需设置短超时、去重与退避重试；任何 uploader 异常必须被隔离，不能影响
解密、DEX 注入、Application 或 Activity 生命周期。

### 当前阶段：非持久化可靠性优化

本阶段的目标是先保证单次进程内的事件语义准确、上报不与关键启动路径竞争，并提高进程
仍存活时的送达率。它不承诺 `_exit`、崩溃或系统杀进程后的远端送达。

```text
Native / Java 启动阶段
  → Reporter：记录事件、保证阶段终态唯一、写入有界内存队列
  → 启动关键路径不执行 HTTP
  → REAL_APPLICATION_ON_CREATE 成功后（或首帧/超时后）
  → 单一 UploadWorker 分类、重试并发送队列中的事件
```

#### Native 阶段闭环

`nativeAttach(Context)` 应由 `void` 改为返回显式结果（例如 `int`）：`0` 表示成功，非零
表示不可恢复的 Native 启动失败。`ProxyApplication.attachBaseContext()` 仅在 Native 返回成功
后发送 `SHELL_ATTACH / SUCCEEDED`；否则发送 `SHELL_ATTACH / FAILED /
NATIVE_ATTACH_FAILED`，并停止后续 ProxyApplication 启动。

Native 代码应按阶段拆分为返回 `StartupResult` 的小函数。每个已进入的阶段在所有退出路径
上都必须发送一个且仅一个终态：

```text
RUNTIME_PROTECTION_CHECK      → SUCCEEDED / BLOCKED
RUNTIME_BUNDLE_LOAD           → SUCCEEDED / FAILED
DEVICE_AUTHORIZATION          → SUCCEEDED / FAILED
PAYLOAD_DECRYPT               → SUCCEEDED / FAILED
BUSINESS_DEX_DECOMPRESS       → SUCCEEDED / FAILED
BUSINESS_CLASSLOADER_INJECT   → SUCCEEDED / FAILED
REAL_APPLICATION_ATTACH       → SUCCEEDED / FAILED
```

不得再以仅输出 Native Log 后直接 `return` 的方式结束已进入的阶段。Reporter 应维护每个
`stageId` 的终态标记，忽略重复终态并记录本地诊断，避免 JNI 与 Java 双方重复上报。

#### 安全拦截

反调试和 Hook 检测函数不应自行调用 `_exit(0)`；它们应返回
`DEBUGGER_DETECTED` 或 `HOOK_FRAMEWORK_DETECTED`，由第 4 阶段统一执行：

```text
RUNTIME_PROTECTION_CHECK / BLOCKED / <稳定原因码>
  → 最小化 Logcat 诊断
  → 尝试交给内存 uploader（不等待、不延迟）
  → _exit(0)
```

由于本阶段不持久化，该事件的远端送达是 best-effort；安全策略不得为等待 HTTP、TLS 或
后台线程而放宽或延迟退出。若将来需要保证此类事件跨进程送达，必须另行引入持久化或进程
外采集能力。

#### JNI 异常边界

真实 `Application` 的构造、绑定和 `attach(Context)` 调用后必须检查
`ExceptionCheck()`。发现异常时先保存必要的异常类别、清除 pending exception，再发送
`REAL_APPLICATION_ATTACH / FAILED / REAL_APPLICATION_ATTACH_FAILED`，最后以 Native
失败结果返回或重新抛出原异常。不得在 pending exception 状态下直接报告成功。

上报的可选 `failureClass` 仅允许异常类名等受限信息；不得包含 message、stacktrace 或业务
对象内容。

#### 内存队列与 HTTP 策略

Reporter 使用单消费者、有界内存队列（建议 32--64 条）。队列满时优先淘汰成功事件，保留
`FAILED` 与 `BLOCKED` 事件；该策略只影响本进程内行为，不改变阶段状态机。

阶段 1--10 仅收集事件，不立即发 HTTP。第 11 阶段成功后可以启动 UploadWorker；第 12 阶段
成功或超时后优先 drain。为覆盖首帧长期不达的正常存活进程，可在第 11 阶段成功后设置一个
2--3 秒的后台兜底调度。Uploader 不得运行在主线程。

每个事件使用稳定幂等键：

```text
packageName + versionCode + sessionId + stageId + status
```

该值应作为请求字段或 `Idempotency-Key` 请求头提交，服务端以它去重。若服务端支持批量接口，
优先按 session 批量发送；否则保持连接复用的受控串行发送。

| 响应或错误 | 本进程内行为 |
| --- | --- |
| 2xx | 确认成功并移出队列 |
| 409 且服务端确认是幂等重复 | 视为成功并移出队列 |
| 408、425、429、5xx、连接错误、超时 | 指数退避后重试 |
| 400、401、403、404、422、序列化/配置错误 | 不重试，记录本地诊断后丢弃 |

重试限制为当前进程内最多 2--3 次，建议使用带抖动的 `1s → 3s → 8s` 退避并遵守
`Retry-After`。进程退出后内存队列丢失是已知且可接受的边界。

#### 首帧与采样

第 12 阶段必须同时具备成功和超时终态。安装首帧 observer 后设置一次性超时；首帧先到则
发送 `SUCCEEDED / FIRST_FRAME_DRAWN`，超时先到则发送 `FAILED / FIRST_FRAME_TIMEOUT`。
两者通过同一原子终态标记竞争，确保只上报一次。

仅 `isFirstLaunch=true && isMainProcess=true` 的 session 进入上传队列；非首次或远程进程
仍可保留本地最小诊断，但不发送启动事件。上传失败仅在当前进程内额外重试一次。

### 验收与测试条件

本节中的测试应同时覆盖 Java 单元测试、Android instrumentation 测试和可注入故障的 Native
测试入口。测试不能依赖真实公网 endpoint，应使用可控 MockWebServer 或等价本地测试服务。

| 编号 | 场景与故障注入 | 必须验证的结果 |
| --- | --- | --- |
| T1 | 完整成功启动 | Stage 1 为 `STARTED`；Stage 2--12 各有一个终态；每个 stageId 无重复终态；事件按 `elapsedSinceStartMs` 非递减。 |
| T2 | `nativeAttach` 返回失败 | Stage 3 为 `FAILED / NATIVE_ATTACH_FAILED`；之后不进入 Proxy `onCreate`；Native 已进入的具体阶段均有终态。 |
| T3 | Runtime bundle 缺失、导出符号缺失、Header 非法 | Stage 5 分别为对应稳定 `FAILED` 码；不得出现 Stage 5 成功或 Stage 3 成功。 |
| T4 | 授权超时、服务端拒绝、Keystore 不可用、AES-GCM 校验失败 | Stage 6 或 7 恰有一个 `FAILED` 终态，错误码稳定；不得继续解压或注入 DEX。 |
| T5 | DEX 元数据非法、解压失败、DirectBuffer 分配失败 | Stage 8 恰有一个 `FAILED`；不得发送 Stage 8 成功或 Stage 9 成功。 |
| T6 | ClassLoader 创建/注入失败 | Stage 9 恰有一个 `FAILED`；不得发送 Stage 9 成功。 |
| T7 | 真实 Application 构造、bind、`attach` 分别抛异常 | Stage 10 为相应 `FAILED`；不会误报成功；上传内容不含 message 或 stacktrace。 |
| T8 | 调试器与 Hook 检测命中 | `_exit` 前可观察到 Stage 4 `BLOCKED` 和稳定原因码的本地记录；测试不得等待或要求 HTTP 已送达。 |
| T9 | 首 Activity 正常首帧、observer 安装失败、永不 PreDraw | 分别得到 `FIRST_FRAME_DRAWN`、`FIRST_FRAME_OBSERVER_FAILED`、`FIRST_FRAME_TIMEOUT`；三种情况均只产生一个 Stage 12 终态。 |
| T10 | 2xx、409 幂等重复、429（含 Retry-After）、5xx、400、超时/断连 | 验证队列确认、退避次数、Retry-After、生效的丢弃规则和幂等键；HTTP 不在主线程或 Stage 1--10 同步执行。 |
| T11 | 队列达到容量、成功与失败事件混合 | 队列长度不超过上限；成功事件优先被淘汰；`FAILED` / `BLOCKED` 事件优先保留；启动线程不阻塞。 |
| T12 | 首次、非首次、主进程、远程进程及多次失败重启 | 首帧成功前的每次主进程启动均为 `isFirstLaunch=true` 并使用相同 `startupInstanceId`、不同 `sessionId`；首帧成功后的下一次启动为非首次且不上传。 |

以下条件是发布前的最低门槛：

- 所有 T1--T12 自动化通过，且 Native 故障注入覆盖每个返回路径。
- 压力测试下主线程没有网络 I/O；Uploader 慢、抛异常或队列满均不改变业务启动结果。
- 同一 session 的服务端接收结果可用幂等键去重，重复投递不增加漏斗计数。
- Logcat、测试请求体和服务端 mock 断言均确认不包含 token、credential、payload key、完整异常栈或原始 Activity 类名。

## 首次启动与成功率

`isFirstLaunch()` 表示当前 App 数据生命周期内**尚未成功到达首帧**的主进程启动尝试，
而不是物理意义上的第一个进程。首次安装或清除数据后该状态为 `true`；仅当
`FIRST_ACTIVITY_FIRST_FRAME / SUCCEEDED` 发生时才同步写入首次成功标记。该成功 session
本身仍为 `isFirstLaunch=true`，下一次主进程启动才变为 `false`。因此，壳加载、授权、解密、
DEX、真实 Application 或首帧失败后重启，都会继续视为首次启动。普通应用升级不重置该
状态；远程进程不参与判定，且其 `isMainProcess()` 为 `false`。

`startupInstanceId` 是首次壳初始化时生成并保存在 App 私有状态中的随机 UUID，用于关联
同一安装实例的多次失败重启；它不使用 OAID、Android ID 或其他系统个人标识。它与每次
启动重新生成的 `sessionId` 共同构成分析关系：同一 `startupInstanceId` 下，每次尝试有不同
`sessionId`；清数据或卸载重装后 `startupInstanceId` 改变。

建议用首期第 1 和第 12 阶段计算首次启动端到端成功率：

```text
分母：SHELL_CORE_LOAD / SUCCEEDED，isMainProcess=true，isFirstLaunch=true
分子：FIRST_ACTIVITY_FIRST_FRAME / SUCCEEDED，isMainProcess=true，isFirstLaunch=true
```

同时保留各阶段的成功率和 P50/P95 耗时，可直接定位失败或耗时集中在哪一个边界。

## 与当前实现的兼容关系

当前 `DECRYPT_STARTED` 对应第 2 阶段开始进入 Native 启动链路附近；
`STARTUP_COMPLETED` 对应第 10 阶段成功结束。迁移期可同时发送旧事件和新阶段事件，
待服务端及 uploader 完成切换后再评估旧事件下线。

当前已存在的 Native/Java 计时点可直接作为首批实现基础：授权与 payload 解密、业务
DEX 解压、ClassLoader 注入、真实 Application 创建/绑定/attach。
