# 本地事件接入既有 user_event 服务

日期：2026-10-09。对照 Git HEAD 中旧 StartupEventUploader.createBody 的实际 JSON，而不是将本地事件平铺发送给既有服务。接口地址、JG_Event、渠道/产品默认配置和请求外层结构沿用旧实现。

## 请求结构

channel、device、gameUser、adData、productInfo、userEvent 六个顶层对象保持原格式。userEvent 包含 eventType=JG_Event、adPosition=""、data。新增事件信息仅放在 data 内。平台 HTTPS、后台投递与 Idempotency-Key 保持使用，不恢复网络授权、OkHttp 或 Conscrypt。

| 当前本地事件字段 | 既有 JSON 位置 | 含义 |
| --- | --- | --- |
| stage | userEvent.data.event | 阶段名称 |
| eventId（UUID） | userEvent.data.telemetryEventId | 同一事件重试沿用的唯一 ID；也用于 Idempotency-Key |
| stage 对应编号 | userEvent.data.eventId | 字符串阶段编号，不能填写 UUID |
| status + resultCode | userEvent.data.itemName | STATUS_CODE；code 为空时仅 STATUS |
| sessionId | userEvent.data.sessionId | 当前进程启动会话 |
| occurredAtMillis | userEvent.data.occurredAtMillis | 事件发生的墙上时间 |
| elapsedSinceStartMs | userEvent.data.elapsedMs | 启动后的相对耗时 |
| stageDurationMs | userEvent.data.stageDurationMs | 当前阶段耗时 |
| versionCode | userEvent.data.versionCode | 原始构建版本码 |
| packageName | userEvent.data.packageName | 新增实际包名；不能用固定业务产品 bundle 替代 |
| processName | userEvent.data.processName | 本地进程名；旧请求未发送该字段，新增于 data |
| mode | userEvent.data.mode | 新增 local 模式 |
| schemaVersion | userEvent.data.schemaVersion | 3；旧线上事件为 2，本地阶段定义不同 |

productInfo.ver 从应用 PackageInfo.versionName 读取，沿用旧版本名称含义。productInfo.bundle 保留旧固定产品 bundle，不强行改成实际包名。channel.id/name、productInfo.appId/bundle 四个业务配置沿用旧默认值，实际项目需保持与服务端配置一致。

## 阶段编号

| 本地阶段 | data.eventId | 处理 |
| --- | --- | --- |
| STARTUP_ATTEMPT | "1" | 与旧启动尝试相同 |
| PAYLOAD_DECRYPT | "7" | 与旧解密阶段相同 |
| LOCAL_PAYLOAD_READ | "1001" | 新阶段 |
| BUSINESS_DEX_PARSE | "1002" | 新阶段；不冒充旧解压阶段 8 |
| BUSINESS_CLASSLOADER_CREATE | "1003" | 新阶段；不冒充旧注入阶段 9 |
| REAL_APPLICATION_CREATE | "1004" | 新阶段；不冒充 attach/onCreate 10/11 |
| EVENT_REPORTER_READY | "1005" | 新阶段 |
| FIRST_ACTIVITY_RESUMED | "1006" | 新阶段；不冒充首帧完成 12 |

其它自定义阶段目前使用编号 "0"，保留实际名称于 event，不自动分配可能冲突的旧编号。新增编号保留旧 1～12 的语义，但服务端若限定编号白名单，需要登记 1001～1006；仅恢复 JSON 格式不能保证服务端接受新阶段。

## 无法直接复用的旧值

本地 MVP 不持久化安装身份，也不采集真实首帧，因此不伪造 startupInstanceId、activityName、activityResumedElapsedMs 或 failureClass。device.oaid 仍按旧方式尝试读取 oa id，读取不到填空字符串；不会拿 sessionId/事件 UUID 充当安装 ID。旧上传器的仅首次启动筛选不属于此次格式兼容，本地仍按现有主进程事件策略运行。

## 请求示例

```json
{
  "channel": {"id":"7","name":"xiaomiapk"},
  "device": {"brand":"example","checkCommonUse":"1","language":"zh-CN","model":"example","oaid":"","system":"android:10,api:29"},
  "gameUser": {"adPlan":"0","region":"cn"},
  "adData": {},
  "productInfo": {"appId":"370","bundle":"wacky.frenzy.blast.cann","gameName":"示例应用","ver":"1.0.24"},
  "userEvent": {
    "eventType":"JG_Event","adPosition":"",
    "data": {
      "event":"PAYLOAD_DECRYPT","eventId":"7","itemName":"SUCCEEDED_PAYLOAD_VERIFIED",
      "sessionId":"session-uuid","telemetryEventId":"event-uuid",
      "schemaVersion":3,"versionCode":24,"occurredAtMillis":1791500000000,
      "elapsedMs":20,"stageDurationMs":5,
      "packageName":"lows.dgeon.ightr.jiagu","processName":"lows.dgeon.ightr.jiagu","mode":"local"
    }
  }
}
```

格式回归通过 StartupEventProtocolTest 调用实际应用序列化器进行校验，包含外层对象、旧字段名和类型、阶段编号、itemName、稳定事件 ID 与重复序列化。测试只创建 JSON，不调用上传方法，不发送服务端请求。
验证：Debug 与 AndroidTest APK 构建通过；API 29 上使用实际运行库与实际 StartupEventUploader.createBody 校验全部 8 个阶段及空结果码，Instrumentation 返回 legacy event JSON PASS。未发送真实上报请求。
