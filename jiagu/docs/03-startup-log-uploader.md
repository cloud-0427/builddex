# 启动日志上传扩展

`startupLogUploaderClass` 是**可选配置**。不配置时，壳不会反射加载任何
使用方代码，原有启动链路保持不变；两条事件仅输出到 Logcat（Tag：
`Jiagu_Startup`）。

启用后，Runtime 会发送两类事件：

- `DECRYPT_STARTED`：壳进入 Native 授权/解密启动链路前。
- `STARTUP_COMPLETED`：真实 `Application.onCreate()` 返回后。

推荐在加固配置中指定一个壳侧 uploader：

```groovy
// app/build.gradle
dexReport {
    // 不需要启动日志时，删除或注释此行即可。
    startupLogUploaderClass = "com.example.StartupUploader"
}
```

本工程的 `app` 模块提供了一个可运行的最小实现
`lows.dgeon.ightr.jiagu.StartupEventUploader`：它以 `POST`
提交 JSON 到固定地址 `https://m9.blazepro.net/api/game/user_event`，路径沿用
`aiKeMeiTrackEvent`。请求仅包含 `productInfo`（包名/版本）、`device`（Android
ID、品牌、型号、系统、语言）和 `userEvent`（启动阶段、首次启动、授权来源）。

该类需要有 public 无参构造器，并实现 `JiaguStartupLogUploader`：

```java
import android.content.Context;
import java.util.Map;

public final class StartupUploader implements JiaguStartupLogUploader {
    public StartupUploader() {}

    @Override
    public void upload(Context appContext, JiaguStartupEvent event) {
        // 此回调已在 Jiagu-StartupLog 后台线程执行。
        // 可使用 appContext 初始化使用方日志 SDK、写本地队列或调度 WorkManager。
        // 请接入使用方自己的埋点/日志队列，不要抛出异常。
        Telemetry.enqueue("jiagu_startup", Map.of(
                "event", event.getType().name(),
                "sessionId", event.getSessionId(),
                "packageName", event.getPackageName(),
                "versionCode", event.getVersionCode(),
                "elapsedMs", event.getElapsedMs(),
                "firstLaunch", event.isFirstLaunch()));
    }
}
```

插件会将配置的 uploader 及其内部类留在壳中，并向最终 R8 生成 keep
规则。uploader 应保持轻量，只调用 Android 平台 API 或已经留在壳内的依赖；
`DECRYPT_STARTED` 发生时业务 DEX 尚不可用。

## 首次启动与成功率

`isFirstLaunch()` 与鉴权缓存无关。它表示**当前 App 数据生命周期中，主进程
记录到的第一次壳启动尝试**：首次安装、清除应用数据后会重新为 `true`；应用升级
不会重置。首次引入此能力的版本升级会被标记一次 `true`，因为旧版没有该本地状态。
该标记在进入 `DECRYPT_STARTED` 前同步保存，所以同一 `sessionId` 的
`DECRYPT_STARTED` 和 `STARTUP_COMPLETED` 会得到相同结果。

远程进程不会抢占主进程的首次启动标记，且事件的 `isMainProcess()` 为 `false`；
统计启动成功率时应只统计 `isMainProcess() == true` 的事件：

```java
if (event.isMainProcess() && event.isFirstLaunch()) {
    if (event.getType() == JiaguStartupEvent.Type.DECRYPT_STARTED) {
        telemetry.increment("jiagu_first_launch_started"); // 分母
    } else if (event.getType() == JiaguStartupEvent.Type.STARTUP_COMPLETED) {
        telemetry.increment("jiagu_first_launch_completed"); // 分子
    }
}
```

因为 uploader 为异步回调，想要统计崩溃/被杀导致的失败，使用方应将开始事件先
写入可持久化队列，再由后续启动或后台任务上传；不能只依赖一次即时 HTTP 请求。

## 解密凭据来源

`STARTUP_COMPLETED` 事件会携带 `authorizationSource`。可直接使用
`event.isNetworkAuthorizationRequired()` 判断本次启动是否需要联网取得解密授权；
需要诊断缓存命中情况时，使用 `event.getAuthorizationSource()`：

| 值 | 是否联网 | 含义 |
| --- | --- | --- |
| `LOCAL_AUTHORIZATION_CACHE` | 否 | 本地存在未过期、验签通过的 grant 与 wrapped payload key，并由 Android Keystore 解封。 |
| `NETWORK_BOOTSTRAP` | 是 | 首次启动或本地 device credential 不可用，走新版 bootstrap。 |
| `NETWORK_AUTHORIZE` | 是 | 本地 credential 可用，但 grant/key 缓存不存在、过期或校验失败，重新授权。 |
| `NETWORK_LEGACY_ENROLL_AUTHORIZE` | 是 | 服务端不支持新版 bootstrap，回退到 enroll + authorize。 |
| `UNKNOWN` | 不确定 | 仅会出现在 `DECRYPT_STARTED`，此时授权路径尚未解析。 |

例如：

```java
if (event.getType() == JiaguStartupEvent.Type.STARTUP_COMPLETED) {
    telemetry.put("jiagu_authorization_source", event.getAuthorizationSource().name());
    telemetry.put("jiagu_network_required", event.isNetworkAuthorizationRequired());
}
```

若无法在构建时配置，也可以在业务 `Application.onCreate()` 中注册。此前已经
发出的事件会回放给新的 uploader，因此仍可收到 `DECRYPT_STARTED`：

```java
@Override
public void onCreate() {
    super.onCreate();
    JiaguStartupReporter.setUploader(new StartupUploader());
}
```

传入的 `appContext` 是 Application Context，可安全保存。uploader 异常会被隔离
并写入 Logcat，不会阻塞或中断启动。建议 `upload()` 只
负责入队；网络重试与去重由使用方实现，可用 `sessionId + event type` 作为幂等键。
