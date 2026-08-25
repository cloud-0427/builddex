# Jiagu Gradle Plugin

这是当前换壳项目的 Gradle 插件，会处理 Android Variant 的字节码、Manifest、JNI 载荷和资源，并自动引入运行时 AAR。

## 本地发布并使用

先把插件发布到 Maven Local：

```powershell
.\gradlew.bat -p dex-report-plugin clean publishToMavenLocal
```

本项目已经在 `settings.gradle` 的 `pluginManagement.repositories` 中加入 `mavenLocal()`，并在 `app/build.gradle` 中应用：

```groovy
plugins {
    id 'io.github.xjc.dex-report' version '0.1.0'
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

每次修改 `dex-report-plugin` 后，需要重新执行 `publishToMavenLocal`。开发阶段如果仍使用相同版本，可加 `--refresh-dependencies`，或者把本地版本从 `0.1.0` 改成新的版本。

## JitPack 发布

推送 GitHub 并创建例如 `0.1.0` 的 Tag。JitPack 会按照仓库根目录的 `jitpack.yml` 同时发布 `dex-report-plugin` 和 `jiagu-runtime`。

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
    id 'io.github.xjc.dex-report' version '0.1.0'
}
```

插件会自动引入 `com.github.cloud-0427.builddex:jiagu-runtime:<同版本号>`。完整使用方式和发布步骤见仓库根目录 `README.md` 与 `RELEASING.md`。
