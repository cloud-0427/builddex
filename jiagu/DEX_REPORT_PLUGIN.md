# DEX Report Plugin

这是当前换壳项目的第一版 Gradle 插件，只负责打印 Android Variant 的 DEX 信息，不修改 APK。

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

推送 GitHub 并创建例如 `0.1.0` 的 Tag，然后在 JitPack 查询该仓库。JitPack 会按照根目录的 `jitpack.yml` 发布 `dex-report-plugin`。

假设 GitHub 仓库是 `USER/REPO`，使用方在 `settings.gradle` 中配置：

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
                useModule("com.github.USER.REPO:dex-report-plugin:${requested.version}")
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

其中 `USER`、`REPO`、版本号需要替换成真实 GitHub 信息。
