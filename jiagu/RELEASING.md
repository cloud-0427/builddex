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

## 失败处理

不要移动或覆盖已经发布的 Tag。修复代码后发布补丁版本，例如从 `0.1.0` 升到 `0.1.1`。JitPack 对公共仓库的构建产物会逐步冻结，因此新版本比重写旧版本更可靠。
