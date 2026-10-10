# 本地 MVP dexReport 配置

当前插件仅启用本地加密和公开 API 加载流程。详细架构见 [设计文档](10-local-public-api-loading-design.md)，落地与验证记录见 [实现记录](11-local-mvp-implementation.md)。历史 online DSL 仍在扩展类型中保留，但不注册网络加密、授权、取钥或服务端发布任务。

## 最小配置

```groovy
dexReport {
    protectionMode = "local"
    payloadSelectionMode = "allowlist"
    payloadIncludePackages = ["com.example.app.business.**"]
    shellMinificationEnabled = true
    autoRunBuildTypes = ["debug", "release"]
}
```

Android 配置必须满足 minSdk >= 29。插件自动引入同版本 `jiagu-local-runtime`。不需要 Jiagu JNI、NDK、服务端凭据或加固专用签名配置；业务自身 JNI 和 APK 签名仍按 Android 工程配置。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| protectionMode | local | MVP 仅接受 local，online 会失败 |
| payloadSelectionMode | allowlist | MVP 仅接受 allowlist |
| payloadIncludePackages | 空 | 必须非空，指定加密业务包 |
| shellKeepPackages / shellKeepClasses | 空 | 优先保留在原始 ClassLoader 中的类 |
| autoRunBuildTypes | 空 | 空表示所有 buildType；非空仅选择指定类型 |
| shellMinificationEnabled | true | 仅优化 Jiagu Runtime；业务与第三方不进入该 R8 program |
| startupTelemetryEnabled | false | 可选启动事件采集和后台投递 |
| startupLogUploaderClass | 空 | 开启事件时必须提供 Shell 中上传器的完整类名 |
| resObfuscationEnabled | false | MVP 不支持开启 |

选中的变体关闭 AGP whole-program minify，禁止 shrinkResources。当前支持普通 APK 和不含 dynamic feature 的普通 AAB（base 模块及 ABI/语言/密度配置 splits），拒绝 dynamic feature、自定义组件工厂、自定义 ClassLoader、isolated splits、sharedUserId、显式 backupAgent 和 cantSaveState=true；支持平台默认工厂以及已验证的 AndroidX CoreComponentFactory。AAB 构建使用 `bundle<Variant>`，自动运行 `verifyJiaguBundle<Variant>`。支持范围及拆分安装验收边界见 [普通 AAB 说明](../DEX_REPORT_PLUGIN.md)。

`publish`、`antiDebugEnabled`、`signatureCheckEnabled`、网络凭据和旧取钥配置不参与 MVP。Payload 固定为未压缩 JG4，在构建阶段使用 AES-256-GCM 封装为 assets/jiagu/local-payload.jgl。本地密钥随 APK 分发，不提供在线授权或防重签名保证。

## 异步事件接入

```groovy
dexReport {
    startupTelemetryEnabled = true
    startupLogUploaderClass = "com.example.app.integration.StartupEventUploader"
    shellKeepClasses = ["com.example.app.integration.StartupEventUploader"]
}
```

上传器必须为 public 类、有 public 无参构造器，并实现 `io.github.xjc.jiagu.local.LocalEventUploader`。其依赖也必须留在 Shell，构建检查会拒绝 Shell 对 Payload 的静态依赖。

```java
public final class StartupEventUploader implements LocalEventUploader {
    public StartupEventUploader() {}
    @Override public void upload(Context context, LocalStartupEvent event) throws Exception {
        // 在后台线程调用；按业务协议发送 event.toJson()。
        // 收到服务端确认后返回；临时失败抛 LocalUploadException(code, true, cause)。
    }
}
```

每次上传必须自行限制在 10 秒内，服务端按 eventId 去重；不要在上传器中再增加无界队列或无限重试。运行库使用单一后台线程、有界内存队列，最多三次投递，重试间隔 1 秒和 5 秒。相同事件重试使用相同 eventId。永久失败或达到重试上限后丢弃，进程重启不补传。只在主进程初始化投递。

事件失败不影响解密和启动；解密前发生的致命错误可能没有机会得到 Context 并上传。FIRST_ACTIVITY_RESUMED 表示 Activity resumed，不代表首帧完成。

运行库不自动添加 INTERNET 权限；真正联网的应用上传器需要自行声明。示例 StartupEventUploader 展示 HttpsURLConnection 适配方式，示例已恢复既有 userEvent 外层格式，字段映射与新阶段编号见 [事件协议兼容说明](12-local-event-service-compatibility.md)；尚未验证真实服务端接收。
