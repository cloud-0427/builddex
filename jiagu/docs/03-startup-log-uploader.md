# 启动阶段事件上报设计

本文定义加固后 APK 的启动阶段事件模型。目标是以一次进程启动为一个
`sessionId`，从壳加载、授权解密、业务 DEX 装载，一直观测到首个 Activity 的
首帧绘制。

> 当前 Runtime 只实现了 `DECRYPT_STARTED` 与 `STARTUP_COMPLETED` 两个兼容事件。
> 本文中的 11 个阶段是后续 Runtime、Native 和插件应共同实现的目标协议；在全部
> 阶段落地前，uploader 必须能同时处理旧事件和新事件。

## 目标与边界

- 每个关键阶段都能得到开始、成功或失败的可关联记录。
- 事件必须覆盖壳早期路径；此时业务 DEX 尚未加载，不能依赖业务 SDK。
- 首屏完成以 **首个 Activity 的第一帧提交绘制** 为准；`onActivityResumed` 只表示
  Activity 进入前台，不表示用户已经看到内容。
- 上报是 best-effort，不得阻塞、改变或中断加固启动链路。
- 事件不得包含 token、credential、payload key、服务端响应正文、完整签名内容或
  完整异常堆栈。

## 首期 11 个阶段

`SHELL_CORE_LOAD` 使用 `STARTED` 记录一次启动尝试；其余阶段只上报终态：
`SUCCEEDED`、`FAILED`、`SKIPPED` 或安全策略终止时的 `BLOCKED`。Reporter 会在本地记录
每个阶段的起点以计算 `stageDurationMs`，但不会将这些内部开始点分发给 uploader。

| stageId | stage | 成功边界 | 终态 `resultCode` 示例 | 预期插点 |
| --- | --- | --- | --- | --- |
| 1 | `SHELL_CORE_LOAD` | `jiagu-core` 已成功加载并注册 JNI | `CORE_LIBRARY_LOAD_FAILED` | `ProxyApplication` 静态初始化块 |
| 2 | `SHELL_ATTACH` | `ProxyApplication.attachBaseContext()` 完成 | `SHELL_ATTACH_FAILED` | `ProxyApplication.attachBaseContext()` |
| 3 | `RUNTIME_PROTECTION_CHECK` | 反调试、反 Hook、签名校验均通过，或相应开关关闭 | `DEBUGGER_DETECTED`、`HOOK_FRAMEWORK_DETECTED`、`SIGNATURE_MISMATCH` | `native_attach()` |
| 4 | `RUNTIME_BUNDLE_LOAD` | `liblog_ext.so`、导出符号和 JGRC 头校验通过 | `RUNTIME_BUNDLE_LOAD_FAILED`、`RUNTIME_BUNDLE_INVALID` | `native_attach()` |
| 5 | `DEVICE_AUTHORIZATION` | 获得可用 payload key / 授权材料 | `KEYSTORE_UNAVAILABLE`、`NETWORK_TIMEOUT`、`NETWORK_REJECTED` | `NetworkHelper.getAuthorizedPayload()` |
| 6 | `PAYLOAD_DECRYPT` | 本地 payload 完成 AES-GCM 解密与认证校验 | `PAYLOAD_AUTHENTICATION_FAILED`、`PAYLOAD_INVALID` | `NetworkHelper.decryptLocalPayload()` |
| 7 | `BUSINESS_DEX_DECOMPRESS` | 所有加密业务 DEX 解压到 DirectBuffer | `DEX_DECOMPRESS_FAILED`、`DEX_ENTRY_INVALID` | Native DEX 解压循环 |
| 8 | `BUSINESS_CLASSLOADER_INJECT` | `InMemoryDexClassLoader` 创建并注入 `dexElements` | `DEX_CLASSLOADER_CREATE_FAILED`、`DEX_INJECTION_FAILED` | Native ClassLoader 注入逻辑 |
| 9 | `REAL_APPLICATION_ATTACH` | 真实 Application 已创建、绑定并执行 `attach(Context)` | `REAL_APPLICATION_CREATE_FAILED`、`REAL_APPLICATION_BIND_FAILED`、`REAL_APPLICATION_ATTACH_FAILED` | Native 真实 Application 替换逻辑 |
| 10 | `REAL_APPLICATION_ON_CREATE` | 真实 `Application.onCreate()` 返回 | `REAL_APPLICATION_ON_CREATE_FAILED` | `native_on_create()` |
| 11 | `FIRST_ACTIVITY_FIRST_FRAME` | 本 session 首个 Activity 首次 `OnPreDraw` / Choreographer 帧回调到达 | `FIRST_FRAME_TIMEOUT` | 真实 Application 的 `ActivityLifecycleCallbacks` 与首个 Activity 的 DecorView |

### 第 5 阶段的授权子结果

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
authorization cache hit/miss）应作为第 5 阶段的属性或采样诊断事件，而不是首期
必发事件，以控制早期启动的事件量。

## 统一事件结构

新协议应以通用 `stageId + stage + status` 替代持续扩张的事件枚举。`stageId` 固定为
上表的 1–11，协议演进时不得改变既有编号；`stage` 是同一编号的可读枚举名。建议
`JiaguStartupEvent` 至少提供以下字段：

```text
sessionId                 // 每次进程启动生成；所有阶段共享
stageId                   // 固定 1..11；用于排序、漏斗及服务端兼容
stage                     // 上表中的阶段名
status                    // SHELL_CORE_LOAD 使用 STARTED；其余阶段仅使用终态状态
resultCode                // 终态必填：成功结果或稳定失败/拦截码
occurredAtMillis          // 墙上时间，用于服务端排序
elapsedSinceStartMs       // 相对 SHELL_CORE_LOAD 开始的单调时钟耗时
stageDurationMs           // 本阶段耗时；仅结束状态填写
packageName
versionName / versionCode
processName
isMainProcess
isFirstLaunch
authorizationSource       // 第 5 阶段成功后填写；未决议/失败时为 UNKNOWN
networkAuthorizationRequired // 第 5 阶段成功后为 true/false；未决议时为 null
activityName              // 仅首 Activity；建议上传 hash 或经白名单映射的名称
activityResumedElapsedMs  // 首个 Activity onResume 时的耗时；首帧前的内部检查点
failureClass              // 可选的脱敏异常类别，不上传 message/stacktrace
```

`resultCode` 是所有终态事件的统一结果字段：例如检查通过为 `CHECK_PASSED`，签名
拦截为 `SIGNATURE_MISMATCH`，DEX 解压失败为 `DEX_DECOMPRESS_FAILED`。第 5 阶段的
成功结果直接使用 `LOCAL_AUTHORIZATION_CACHE`、`NETWORK_BOOTSTRAP`、
`NETWORK_AUTHORIZE` 或 `NETWORK_LEGACY_ENROLL_AUTHORIZE`；失败时使用
`NETWORK_TIMEOUT`、`NETWORK_REJECTED`、`KEYSTORE_UNAVAILABLE` 等稳定错误码。

服务端幂等键应使用：`packageName + versionCode + sessionId + stageId + status`。
除 `SHELL_CORE_LOAD` 外，同一阶段只产生一个终态事件；重试上传不得产生新的业务事件。

## 生命周期与时间线

```text
SHELL_CORE_LOAD
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

第 11 阶段只记录本 session 的第一个 Activity。它不必是 Launcher Activity：深链、
通知或恢复任务都可能使其他 Activity 成为第一个被创建的界面。应记录其受控标识，
并在 `FIRST_ACTIVITY_FIRST_FRAME` 上报时附带从启动开始至首帧的总耗时。

业务若还需要“数据已就绪”的定义，可另行主动发送 `FIRST_ACTIVITY_FULLY_DRAWN`；它不
属于加固 Runtime 的首期 11 阶段，因为框架无法可靠判断异步内容何时完成。

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

事件分发可在后台线程执行，但**不能只依赖即时 HTTP 请求**：在阶段开始和失败时，
Runtime/uploader 应先以轻量、限量的持久化队列落盘；后续启动或后台任务再批量上传。
这样进程被杀、崩溃或安全策略调用 `_exit` 时，已记录的启动轨迹仍可在下次上报。

网络上传需设置短超时、去重与退避重试；任何 uploader 异常必须被隔离，不能影响
解密、DEX 注入、Application 或 Activity 生命周期。

## 首次启动与成功率

`isFirstLaunch()` 表示当前 App 数据生命周期中主进程记录到的第一次壳启动尝试；首次
安装或清除数据后会重新为 `true`，普通应用升级不会重置。远程进程不会抢占该标记，
并且其 `isMainProcess()` 为 `false`。

建议用首期第 1 和第 11 阶段计算首次启动端到端成功率：

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
