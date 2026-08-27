# 发布检查清单

## 自动完成

推送形如 `0.1.0` 的 Tag 后，`.github/workflows/release.yml` 会：

1. 使用 JDK 17 构建并发布 `jiagu-runtime` AAR 到临时 Maven Local。
2. 构建并发布 `dex-report-plugin` JAR 和 Gradle plugin marker。
3. 请求 JitPack 构建对应 Tag，并检查构建日志。
4. JitPack 成功后创建 GitHub Release，并自动生成 release notes。

JitPack 构建使用仓库根目录的 `jitpack.yml`，发布坐标如下：

```text
com.github.cloud-0427.builddex:dex-report-plugin:<tag>
com.github.cloud-0427.builddex:jiagu-runtime:<tag>
```

插件构建时会把同一组 runtime 坐标写入插件资源，所以插件与 runtime 始终使用相同 Tag。

## 首次发布前手工完成

1. 确认 `main` 分支包含准备发布的全部提交，`Verify publishable artifacts` 工作流为绿色，且本地 `git status` 干净。
2. 检查仓库不包含签名文件、私钥、`jiagu_keys.json`、服务端密钥或真实凭据。
3. 在 GitHub 仓库的 Actions 页面确认 GitHub Actions 已启用。发布工作流只申请创建 Release 所需的 `contents: write`，不需要额外 Secret；若账号或组织策略禁止写权限，需要管理员放行该权限。
4. 确认发布版本号。首版建议 `0.1.0`。
5. 创建并推送 Tag：

   ```powershell
   git tag -a 0.1.0 -m "Release 0.1.0"
   git push origin 0.1.0
   ```

6. 在 GitHub 的 Actions 页面确认 `Publish GitHub release and JitPack artifacts` 成功。
7. 打开 `https://jitpack.io/#cloud-0427/builddex`，确认两个模块均显示绿色状态。
8. 用一个仓库外的最小 Android 工程按根 README 的方式依赖 `0.1.0`，至少执行一次 `assembleDebug` 或 `assembleRelease`。

## Release 构建锁协议发布

构建一致性锁启用 RuntimeConfig/ENROLL/AUTHORIZE V2，服务端、插件和 Runtime 必须作为同一协议版本协调发布，不能只升级其中一个组件。

上线前额外确认：

1. 清空旧公司数据库并使用新 Schema 重新创建公司和 API Key；本轮不支持旧数据迁移。
2. 服务端、插件和 Runtime 对 `releaseBuildSha256`、`certificateSetSha256` 和 `payloadKeyVersion` 使用相同 canonical 测试向量。
3. RSA-OAEP 端到端测试使用 SHA-1、MGF1-SHA1 和空 Label。
4. 分别构建普通 APK、ABI Split、资源 Split 和 AAB，所有 Output 使用同一 versionCode。
5. AAB 配置真实 Play App Signing 证书，并从 Play 测试轨道安装后完成 ENROLL/AUTHORIZE/download。
6. 发布正式 Release 后，以相同 package/version 构建内容不同的 Debug Variant，确认收到明确 409 和提升版本提示。
7. 检查服务端、Gradle 和 Logcat 不输出 API Key、Credential、Grant、wrappedPayloadKey 或 Integrity Token。

完整实施和验收矩阵见 [Release 构建一致性锁实施计划](docs/02-release-build-lock-implementation-plan.md)。

## 失败处理

不要移动或覆盖已经发布的 Tag。修复代码后发布补丁版本，例如从 `0.1.0` 升到 `0.1.1`。JitPack 对公共仓库的构建产物会逐步冻结，因此新版本比重写旧版本更可靠。
