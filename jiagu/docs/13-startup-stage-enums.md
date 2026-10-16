# 启动阶段与枚举

阶段 ID 是协议标识，不代表执行顺序。既有 ID 和结果码字符串保持不变。

2026-10-16 更新：主阶段声明已按启动流程排列，新增显示顺序、中文名称及异步标记；补齐 P0 检查点、诊断码、内部事件名和记录类型枚举。JSON 仍输出既有字符串，不使用 enum ordinal 作为编号。

## Local Runtime

主链：`STARTUP_ATTEMPT(1)` → `LOCAL_PAYLOAD_READ(1001)` → `PAYLOAD_DECRYPT(7)` →
`BUSINESS_DEX_PARSE(1002)` → `BUSINESS_CLASSLOADER_CREATE(1003)` →
`REAL_APPLICATION_CREATE(1004)` → `FIRST_ACTIVITY_RESUMED(1006)`。

`EVENT_REPORTER_READY(1005)` 由工作线程在上传器初始化成功后上报，属于异步旁路。
它和 `FIRST_ACTIVITY_RESUMED` 不保证上报顺序，也不是业务启动成功的前置条件。

`LocalStartupStage`、`LocalStartupStatus`、`LocalStartupResultCode` 分别表示阶段、状态和结果码。
加载器、解码器、ComponentFactory 和 Reporter 的内部上报使用枚举。
当前阶段去重使用稳定阶段名与开始/终态键，保留字符串入口以兼容既有宿主和自定义阶段/结果码。
事件公开字符串字段保持兼容；新增枚举 getter，自定义名称无法映射时返回 null。

JSON 字段规则：`event` 为 stage 名称，`eventId` 为 stageId 的十进制字符串；
`telemetryEventId` 为唯一事件 ID。自定义阶段沿用 stageId=0。

### 按流程查找主阶段

| 显示顺序 | 枚举 | 固定事件编号 | 含义 |
| --- | --- | --- | --- |
| 10 | STARTUP_ATTEMPT | 1 | 开始启动 |
| 20 | LOCAL_PAYLOAD_READ | 1001 | Payload 读取与外层配置检查 |
| 30 | PAYLOAD_DECRYPT | 7 | Payload 解密、认证与摘要检查 |
| 40 | BUSINESS_DEX_PARSE | 1002 | DEX 表、header 和边界检查 |
| 50 | BUSINESS_CLASSLOADER_CREATE | 1003 | native 路径准备与 ClassLoader 创建 |
| 60 | REAL_APPLICATION_CREATE | 1004 | Application 实例构造，不代表 attach/onCreate 完成 |
| 70 | EVENT_REPORTER_READY | 1005 | 上传器就绪，异步旁路 |
| 80 | FIRST_ACTIVITY_RESUMED | 1006 | 第一次 Activity resumed，不代表首帧 |

`values()` 已按上述阅读顺序声明；`getDisplayOrder()` 是展示排序，`getDisplayName()` 是中文说明，`isAsynchronous()` 标出旁路；`fromId()` / `fromWireName()` 查询已有定义。禁止按 ordinal 持久化、上报或判断阶段依赖。1005 放在此处方便阅读，仍可能晚于 1006。

### 全部枚举入口

| 文件 | 管理内容 |
| --- | --- |
| [LocalStartupStage](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupStage.java) | 八个主阶段、编号、顺序、中文名称、异步属性 |
| [LocalStartupStatus](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupStatus.java) | STARTED、SUCCEEDED、FAILED、SKIPPED |
| [LocalStartupResultCode](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupResultCode.java) | Payload、DEX、ClassLoader、Application 等业务结果码 |
| [LocalStartupCheckpoint](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupCheckpoint.java) | ENTER → NATIVE_PATHS → CONSTRUCT 内部检查点；后两项只用于类加载阶段 |
| [LocalStartupDiagnosticCode](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupDiagnosticCode.java) | Application 回调、初始化、恢复、存储与投递诊断码 |
| [LocalStartupInternalEvent](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupInternalEvent.java) | SESSION_HEADER / STARTUP_DIAGNOSTIC，保留 eventId=0 |
| [LocalStartupRecordType](../jiagu-local-runtime/src/main/java/io/github/xjc/jiagu/local/LocalStartupRecordType.java) | STAGE / CHECKPOINT / DIAGNOSTIC |

### 检查点与诊断码目录

诊断码按生命周期和故障类别集中排列；失败/恢复是分支，不强行组成单一路径。以下完整目录包含本地审计，审计记录不自动变成远端业务阶段。

| 位置/类别 | 枚举项 |
| --- | --- |
| Application attach | APPLICATION_ATTACH_ENTER、APPLICATION_ATTACH_COMPLETED、APPLICATION_ATTACH_FAILED |
| Provider/上报器初始化 | MISSING_UPLOADER_CONFIG、CONTEXT_NOT_APPLICATION、EVENT_PROVIDER_FAILED、ACTIVITY_OBSERVER_INSTALL_FAILED、EVENT_INITIALIZATION_FAILED、EVENT_UPLOADER_INITIALIZATION_FAILED |
| Application onCreate | APPLICATION_ON_CREATE_ENTER、APPLICATION_ON_CREATE_COMPLETED、APPLICATION_ON_CREATE_FAILED |
| 采集一致性与持久化降级 | TERMINAL_CONFLICT、PERSISTENCE_DEGRADED |
| 后续启动恢复 | RECOVERY（内部模板）、PREVIOUS_STARTUP_INCOMPLETE |
| journal 及进程锁 | JOURNAL_CORRUPTED、JOURNAL_TRUNCATED、JOURNAL_QUARANTINE_FAILED、JOURNAL_DELETE_FAILED、LOCK_DELETE_FAILED、LOCK_PROBE_FAILED |
| 投递与保留 | TARGET_PAUSED、RETENTION_EVICTED、CAPACITY_EVICTED、CORRUPT_FILE_EXPIRED、CORRUPT_FILE_DELETE_FAILED、INVALID_PAYLOAD、UPLOADER_EXCEPTION |

推荐调用：

```java
LocalEventReporter.emit(LocalStartupStage.LOCAL_PAYLOAD_READ,
        LocalStartupStatus.SUCCEEDED, LocalStartupResultCode.PAYLOAD_READ, durationMs);
LocalEventReporter.applicationCheckpoint(LocalStartupDiagnosticCode.APPLICATION_ON_CREATE_ENTER);
```

内部加载流程使用 `LocalStartupCheckpoint`，内部诊断/审计接受 `LocalStartupDiagnosticCode`。事件可通过 `getRecordType()` / `getDiagnosticCode()` 读取对应枚举；无法识别的扩展值返回 null，原始 JSON 不改写。公开字符串 API 仅作为兼容入口，允许宿主自定义值；Logcat 文案、HTTP 动态状态码和 SQL 存储状态不属于业务阶段枚举。

## Native Runtime

`STARTUP_ATTEMPT(1)` → `SHELL_CORE_LOAD(2)` → `SHELL_ATTACH(3)` 开始。
`SHELL_ATTACH` 内部执行保护检查(4)、Runtime bundle 加载(5)、设备授权(6)、
payload 解密(7)、DEX 解压(8)、ClassLoader 注入(9)、真实 Application attach(10)。
完成后才上报 `SHELL_ATTACH` 成功，再执行真实 Application onCreate(11)，观察首帧(12)。

Java 保留既有 Stage、Status 和 AuthorizationSource，并增加 StartupResultCode。
Native 的 startup-events.h 定义对应强类型枚举，在 JNI 边界转换为原 ID、状态值和结果字符串。
协议一致性测试校验 Java 和 Native 的定义，防止两侧漂移。

耗时日志子步骤使用独立的 StartupTimingStep 枚举，保留原日志名称，
避免把授权子步骤或聚合计时误当成新增的上报阶段。

Local 的 Activity resumed 不等同于 Native 的首帧；ClassLoader create 不等同于 inject。
枚举重构不改变这些阶段的成功边界、失败归属和上传重试行为。
