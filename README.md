# BuildDex / Jiagu

Android APK 加固 Gradle 插件。插件会在构建期间处理字节码、Manifest、JNI 载荷与资源，并自动引入运行时 AAR。

当前要求：Android Gradle Plugin 9.3.1、JDK 17、minSdk 29，以及可用的 Android NDK。

## 使用 JitPack 版本

在使用方项目的 `settings.gradle` 中加入 JitPack。插件和普通依赖使用两套仓库配置，因此两处都要配置：

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

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri('https://jitpack.io')
            content { includeGroup('com.github.cloud-0427.builddex') }
        }
    }
}
```

在 App 模块中应用插件；插件会自动引入同版本的 `jiagu-runtime`：

```groovy
plugins {
    id 'com.android.application'
    id 'io.github.xjc.dex-report' version '0.1.1'
}

dexReport {
    publicKeyPath = 'https://example.com/jiagu-keys.json'
    publicKeyJsonKey = 'akmKeys'
    enableMultiVersion = true
    keyExpiryDays = 2

    antiDebugEnabled = true
    signatureCheckEnabled = true
    expectedSignature = 'your-lowercase-sha256-signature'

    resObfuscationEnabled = true
    resConfigs = ['zh', 'en']
}
```

发布版应固定使用正式 Tag，不建议依赖 `main-SNAPSHOT`。可在 [JitPack 项目页](https://jitpack.io/#cloud-0427/builddex) 查看版本和构建日志。

## 本地开发

```powershell
cd jiagu
.\gradlew.bat -p dex-report-plugin clean publishToMavenLocal
.\gradlew.bat :jiagu-runtime:publishToMavenLocal
.\gradlew.bat :app:assembleDebug
```

示例 App 通过 composite build 使用插件源码，并直接依赖 `:jiagu-runtime`。

### 示例 App 切换本地/JitPack

`jiagu/gradle.properties` 提供统一开关：

```properties
# 本地联调：插件源码和 runtime 源码都来自当前仓库
jiagu.source=local

# JitPack 验证：插件 JAR 和 runtime AAR 都来自 JitPack
jiagu.source=jitpack
jiagu.version=0.1.1
```

也可以不修改文件，直接在命令行临时覆盖：

```powershell
# 彻底使用本地模块
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath "-Pjiagu.source=local"

# 彻底使用 JitPack，并强制重新检查远程依赖
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath "-Pjiagu.source=jitpack" --refresh-dependencies
```

远程模式不会注册本地 composite plugin build 或 `:jiagu-runtime` project，也不会查询 Maven Local。两种模式的 App 输出也分别写入 `app/build/local` 和 `app/build/jitpack`，避免依赖与构建中间产物交叉污染。

## 发布新版本

版本号以 Git Tag 为唯一来源。提交通过本地验证后创建并推送 SemVer Tag：

```powershell
git tag -a 0.1.1 -m "Release 0.1.1"
git push origin 0.1.1
```

Tag 推送后，GitHub Actions 会重新验证两个发布物、触发 JitPack 构建，并在成功后创建同名 GitHub Release。发布检查详见 `jiagu/RELEASING.md`。

## License

[Apache License 2.0](LICENSE)
