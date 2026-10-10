# Jiagu Gradle Plugin

## 当前普通 AAB 支持范围（2026-10-10）

本地模式支持普通 APK 和不含 dynamic feature 的普通 AAB，仅支持 base 模块及 ABI、语言、密度配置 splits。加密 Payload 位于 base assets，壳代码位于 base DEX。运行时从 base APK 读取密文，JNI 搜索路径包含 base 与已安装的配置 split APK。

执行 `.\gradlew.bat :app:bundleRelease` 即可构建加固 AAB，产物位于 `app/build/outputs/bundle/release/`。`bundle<Variant>` 自动执行 `verifyJiaguBundle<Variant>`，检查 base 结构、密文与生产输入一致性、壳/业务类冲突及 ServiceLoader 声明；也可单独运行该校验任务。`assemble<Variant>` 保留 APK 校验。

不支持 dynamic feature、按需代码安装、isolated splits、自定义 ClassLoader 和任意自定义组件工厂。签名沿用宿主 AGP signingConfig；示例使用 debug 签名，正式发布需配置上传签名。本地模式未启用下文历史在线流程的 Release 锁和 Play 证书授权。

AAB 容器校验不能替代拆分安装验收。发布前使用 [官方 bundletool](https://developer.android.com/tools/bundletool) 生成并安装设备 APK 集，验证真实 Application/Activity、SPI、资源和业务 JNI；不要只测试 universal APK。当前环境没有连接设备，AAB splits 真机启动和完整 API/ABI/OEM 矩阵尚未验证。

```powershell
java -jar bundletool.jar build-apks --bundle=<app.aab> --output=<app.apks> --connected-device
java -jar bundletool.jar install-apks --apks=<app.apks>
```

启动解密/完成事件的可选上传扩展见 [启动日志上传扩展](docs/03-startup-log-uploader.md)。

这是当前换壳项目的 Gradle 插件，会处理 Android Variant 的字节码、Manifest、JNI 载荷和资源，并自动引入运行时 AAR。

以下为历史在线流程的 Release 构建一致性锁设计，不是当前本地模式的已实现功能。最终设计和实施顺序见 [Release 构建一致性锁实施计划](docs/02-release-build-lock-implementation-plan.md)。核心规则：

- 同一 `applicationId + versionCode` 只有一个 Release；
- 锁定最终业务 DEX、Manifest/resources/assets 和全部 ABI Native；
- DRAFT 可原地更新，PUBLISHED/REVOKED 必须提升 versionCode；
- 支持 APK、ABI Split、资源 Split 和 AAB；
- AAB 正式发布必须配置 Play App Signing 证书摘要；
- 同版本正式版发布后，内容不同的 Debug 构建会明确失败。

计划中的证书配置形式：

```groovy
dexReport {
    // 可选；未配置时默认使用 https://jg.nebulapro.net/
    // serverUrl = "https://jiagu.example.com"
    companyId = "acme"
    companyApiKey = providers.environmentVariable("JIAGU_COMPANY_KEY").get()
    certificateSha256Digests = [
        "PLAY_APP_SIGNING_CERT_SHA256_BASE64URL"
    ]
}
```

APK/Debug 默认合并本地 signingConfig 证书；AAB 配置用于加入 Play App Signing、受控侧载和证书轮换历史。

## 本地发布并使用

先把插件发布到 Maven Local：

```powershell
.\gradlew.bat -p dex-report-plugin clean publishToMavenLocal
```

本项目已经在 `settings.gradle` 的 `pluginManagement.repositories` 中加入 `mavenLocal()`，并在 `app/build.gradle` 中应用：

```groovy
plugins {
    id 'io.github.xjc.dex-report' version '0.1.8'
}
```

查看 Debug DEX：

```powershell
.\gradlew.bat :app:reportDebugDex --console=plain
```

查看 Release DEX：

```powershell
.\gradlew.bat :app:reportReleaseDex --console=plain
```

每次修改 `dex-report-plugin` 后，需要重新执行 `publishToMavenLocal`。开发阶段如果仍使用相同版本，可加 `--refresh-dependencies`，或者把本地版本改成新的版本。

## 跨 DEX 的壳依赖保留规则

业务 DEX 和壳分别经过 R8。插件在业务 DEX 裁剪完成后，用 R8 TraceReferences
扫描其实际引用的壳类、方法和字段，生成
`app/build/intermediates/jiagu/<variant>/shell-keep-rules.pro`，自动交给壳的 R8。
规则保留跨边界符号，未被业务引用的类和成员仍可由壳 R8 按自身可达性裁剪、混淆。
保留规则允许扩大访问权限，避免包内父类被固定后，子类重打包引起 `IllegalAccessError`。
无需添加 `-keep class kotlin.** { *; }`，依赖升级后白名单随构建重新生成。

此规则只用于壳编译，不作为业务 R8 的输入，避免构建依赖循环。
扫描覆盖静态字节码引用及继承成员解析；通过字符串反射、JNI 或外部配置访问的符号，
仍需要使用方或依赖库提供精确的 consumer/ProGuard 规则。它不替代这些动态入口规则，
也不检查 Android SDK 桩中缺失的可选平台 API。

## ServiceLoader 与加固 release 验证

插件合并输入 JAR 中的 `META-INF/services/*`（去重、保留所有 provider），同时提供给
业务 R8。根据声明生成精确的服务接口方法和实现类公共无参构造方法保留规则；不保留
整个 Kotlin/协程库，也不依赖静态引用扫描去发现反射入口。
原名保留策略使服务文件名、内容和最终类名保持一致。

每个 variant 的 `intermediates/jiagu/<variant>/service-descriptors.jar` 保存预期声明。
`verifyJiaguServices<Variant>` 在 APK 打包后检查最终声明与该清单相同、无重复条目，
并从壳 DEX 和 JG3 业务 DEX 的实际 class definitions 核对接口/实现类没有被删除或改名。
`assemble<Variant>` 自动运行 APK 检查；`bundle<Variant>` 自动运行 `verifyJiaguBundle<Variant>`，检查 AAB 的 base/root 服务声明和 base/dex 壳类。

壳 R8 仍可能报告这两个协程服务的 `Unexpected reference to missing service`：
业务类型在加密 DEX 中，对壳 R8 不可见。不要直接删除 SPI 资源或全局屏蔽警告。
只有 APK 检查和真机服务发现测试通过，才能确认对应 release 的这几条提示没有导致服务失效。

本示例工程提供 release 专用的平台 Instrumentation 运行器，避免 AndroidJUnitRunner
引用被业务 R8 单独改名的 AndroidX 类。它在已加固应用的 ClassLoader 中验证两个 provider
可被 ServiceLoader 实例化，并通过 Lifecycle 协程作用域（内部使用 Dispatchers.Main.immediate）
验证任务运行在主 Looper。不会额外保留 Dispatchers 或关闭 release 混淆、防调试。

验证前将测试版本的 `publish` 设为 `false`。PowerShell 构建命令：

```powershell
.\gradlew.bat :dex-report-plugin:test :app:assembleRelease :app:assembleReleaseAndroidTest '-Pjiagu.testBuildType=release'
```

安装该次构建的 release 应用 APK 与 release-androidTest APK 后运行：

```powershell
adb shell am instrument -w -r lows.dgeon.ightr.jiagu.test/lows.dgeon.ightr.jiagu.ServiceVerificationInstrumentation
```

通过时输出 `services=PASS`、`dispatchersMain=PASS` 和 `OK (2 service verification tests)`。
这验证服务加载与主线程调度，不通过制造应用崩溃测试协程未捕获异常的完整处理链。

## JitPack 发布

推送 GitHub 并创建对应版本的 Tag。JitPack 会按照仓库根目录的 `jitpack.yml` 同时发布 `dex-report-plugin` 和 `jiagu-runtime`。

使用方在 `settings.gradle` 中配置：

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri('https://jitpack.io') }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'io.github.xjc.dex-report') {
                useModule("com.github.cloud-0427.builddex:dex-report-plugin:${requested.version}")
            }
        }
    }
}
```

App 中仍然按插件 ID 应用：

```groovy
plugins {
    id 'io.github.xjc.dex-report' version '0.1.8'
}
```

插件会自动引入 `com.github.cloud-0427.builddex:jiagu-runtime:<同版本号>`。完整使用方式和发布步骤见仓库根目录 `README.md` 与 `RELEASING.md`。
