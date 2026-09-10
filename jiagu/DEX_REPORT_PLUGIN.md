# Jiagu Gradle Plugin

这是当前换壳项目的 Gradle 插件，会处理 Android Variant 的字节码、Manifest、JNI 载荷和资源，并自动引入运行时 AAR。

Release 构建一致性锁的最终设计和实施顺序见 [Release 构建一致性锁实施计划](docs/02-release-build-lock-implementation-plan.md)。核心规则：

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
    id 'io.github.xjc.dex-report' version '0.1.7'
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
`assemble<Variant>` 自动运行该检查；当前检查对象为 APK，不涵盖 AAB。

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
    id 'io.github.xjc.dex-report' version '0.1.7'
}
```

插件会自动引入 `com.github.cloud-0427.builddex:jiagu-runtime:<同版本号>`。完整使用方式和发布步骤见仓库根目录 `README.md` 与 `RELEASING.md`。
