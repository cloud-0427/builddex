# 启动阶段与枚举

阶段 ID 是协议标识，不代表执行顺序。既有 ID 和结果码字符串保持不变。

## Local Runtime

主链：`STARTUP_ATTEMPT(1)` → `LOCAL_PAYLOAD_READ(1001)` → `PAYLOAD_DECRYPT(7)` →
`BUSINESS_DEX_PARSE(1002)` → `BUSINESS_CLASSLOADER_CREATE(1003)` →
`REAL_APPLICATION_CREATE(1004)` → `FIRST_ACTIVITY_RESUMED(1006)`。

`EVENT_REPORTER_READY(1005)` 由工作线程在上传器初始化成功后上报，属于异步旁路。
它和 `FIRST_ACTIVITY_RESUMED` 不保证上报顺序，也不是业务启动成功的前置条件。

`LocalStartupStage`、`LocalStartupStatus`、`LocalStartupResultCode` 分别表示阶段、状态和结果码。
加载器、解码器、ComponentFactory 和 Reporter 的内部上报使用枚举。
阶段去重使用 `EnumSet`，保留字符串入口以兼容自定义阶段和结果码。
事件公开字符串字段保持兼容；新增枚举 getter，自定义名称无法映射时返回 null。

JSON 字段规则：`event` 为 stage 名称，`eventId` 为 stageId 的十进制字符串；
`telemetryEventId` 为唯一事件 ID。自定义阶段沿用 stageId=0。

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
