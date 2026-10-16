# Local 启动优化与 P0 可靠性实施、回归记录

日期：2026-10-16。设计依据：[P0 设计](15-local-startup-p0-reliability-plan.md)。下文区分已实现、已测及尚未覆盖的能力，不将设计矩阵视为全部通过。

## 1. 启动本体优化

- 已知 ZIP entry 长度时精确分配密文 byte[] 并直接读取，去掉 ByteArrayOutputStream 扩容及 toByteArray 完整副本。保留未知长度有界分支、重复 asset 检查、长度限制、短读和额外数据检查。
- 主 APK 的 Payload 查找、重复检查及 native ABI 识别合并一次扫描。各 split APK 各扫描一次，去掉每个 ABI 的重复 Collections.list 枚举，保持位数、ABI 优先级和 JNI 路径语义。
- Local 构建将 jgl 配置为 noCompress，最终 APK 验证为 STORED；原样本 12718 字节密文经 DEFLATED 反而占 12723 字节。AAB 文件内部压缩方式不等同于分发安装 APK 的压缩策略。
- 保留 AES-GCM、SHA-256、DEX 表/header/边界检查、DirectBuffer 与 DEX slice。失败清理使用固定 4 KiB 零块，不分配明文等大的清零数组，不让清理异常替换原始失败。
- 未引入分块解密、明文磁盘缓存、多个 ClassLoader 或延迟业务类加载；没有足够测量证据支持它们作为首批变更。

## 2. 事件可靠性实现

### 2.1 最早入口与存储

JiaguTask 在 Runtime R8 前生成 LocalBuildInfo，携带采集开关、包名、versionCode/versionName 及构建输入关联 hash。读取 Payload 前即可确定原版本，不依赖解密成功。buildId 用于关联输入，不是 APK 签名或完整性凭证。

早期使用 ApplicationInfo.dataDir 下的 `no_backup/jiagu-startup-v1`，Provider 后与 Context.getNoBackupFilesDir 规范路径对照。日志只保存阶段和有界元数据，不落盘明文 DEX、密钥或服务端响应正文。

每进程 session 独立 journal/alive 文件，记录 session header、阶段 ENTER/子步骤和终态。帧包含 magic、版本、长度、JSON、CRC32；append 返回前完成 fd.sync。CRC 仅检测损坏。

进程存活锁与导入锁分离；同进程 ACTIVE 集合避免重复打开/关闭自身存活锁文件。Provider 后后台导入 SQLite，事件与导入偏移同事务提交。死进程日志导入后清理；截断尾记审计，CRC/格式损坏隔离，合法前缀和其他日志继续处理。

### 2.2 投递与确认

队列状态为 PENDING / IN_FLIGHT / ACKED / QUARANTINED；内部检查点为 LOCAL。ACKED 不再发送，保留有界历史供对账后清理。这是对设计“ACK 后删除”的调整：逻辑出队与审计历史保留分开。

IN_FLIGHT 使用 owner/租约，死亡后可回收。临时及未分类上传异常持久化退避，不再三次失败后删除；支持有界 Retry-After，并处理明显时间回拨。LocalUploadException 保留原构造器，新增目标暂停及 retryAfterMillis。

示例对 401/403/404 暂停目标 15 分钟后再检查，构建/上传器配置变化可提前恢复；400/422 等隔离单条事件。网络不持有 Reporter 锁或数据库事务；本地 ACK 异常不进入 HTTP 异常处理。中断导致的重传由服务端幂等兜底。

### 2.3 元数据与失败

schemaVersion=4；保留 JSON eventId 为阶段号、telemetryEventId 为 UUID，保留 event/itemName；增加显式 status/resultCode、sequence、recordType、buildId、原 versionName/environment、relatedSessionId/checkpoint。

持久化事件解码、重试后保持原 JSON。示例上传器使用原版本和环境快照；示例 gameName 使用包名。按接入约定，外层 device.oaid 使用事件原始 sessionId：同一次启动各阶段相同，不同启动不同，历史补传不替换为当前进程会话。该字段在此接口中代表启动会话，不是真实设备 OAID。接收端及业务自定义 uploader 须遵守不可变负载与 ACK 契约。

恢复诊断关联旧 session，保存最后阶段、状态、结果码及检查点；不补造旧成功或 FAILED。1005/1006 不保证严格先后。无 Activity 或无证据的退出仍可为未知。

默认和 AndroidX 工厂覆盖 Application 构造。示例真实 Application 显式记录 attach/onCreate 的进入、完成和可捕获异常，保留原异常；Runtime 不 hook 任意宿主 Application，也不接管全局异常处理器。

### 2.4 上限与降级

journal/损坏日志逻辑上限 2 MiB，单 session 512 KiB；数据库事件逻辑负载目标 2 MiB、1024 条、7 天；审计最多 128 条，历史导入元数据有界清理。数据库页、索引和回滚文件开销另计。

磁盘错误、容量不足或记录器异常不改变业务结果。内存降级最多 64 条，后续尝试入库，并保留降级计数/审计。审计也有保留上限，不能无限期逐条证明所有淘汰。卸载、清数据、永久离线、不再启动或持久化前强杀不承诺送达。

## 3. 依赖与接收端约束

未新增第三方 Android runtime 依赖。使用 IO、文件锁、CRC32、JSONObject、SQLite、线程池、HttpsURLConnection；沿用现有 AndroidX compileOnly/测试依赖与构建侧 ASM。

**生产 user_event 接收端未在本次部署或验证。** 示例仍沿用 2xx 确认约定，必须确认其代表持久接收，并实现 telemetryEventId 唯一键、重复确认、冲突拒绝及足够长的去重保留期。客户端不能单方面保证外部服务的完整性。

回归使用 debug 专用 LocalReliabilityUploader、本机 HTTP 接收器及 adb reverse。测试端 SQLite 提交后才返回匹配 ID 的 ACK，可模拟断网/503/ACK 丢失/重复请求，不访问生产地址。cleartext 许可仅在 debug Manifest；上传 fixture 和故障入口不进入 Release。

## 4. 实测结果

设备：OPPO PBEM00，c2657c90，Android 10 / API 29。不能外推为 API 34/36、所有 ABI/OEM/splits 已通过。

| 项目 | 已完成结果 |
| --- | --- |
| 插件/Runtime JVM | 插件 46 项、Runtime JVM 1 项通过，含壳内配置生成、解码负向路径与枚举协议 |
| Android 存储/读取 | 13 项通过，含去重、内容冲突、半帧、损坏隔离、事务回滚、失效租约、目标暂停、环境冻结、过期审计、短读及大数组读取 |
| 加固完整应用 | 真实业务 Application/Activity ClassLoader、JNI、SPI、协程、事件 JSON 验证通过 |
| 断网→杀进程→显式重启 | 撤销 adb reverse 造成真实连接失败；恢复后按原 ID/内容补传通过 |
| 服务端提交后 ACK 丢失 | 重复 HTTP 请求产生；测试端逻辑事件唯一，重启补齐通过 |
| 五类早期失败 APK | 缺 asset、坏格式、认证失败、DEX 表损坏、Application 类不存在均留证；同签名修复包覆盖后 5 条失败全部按原 ID/内容补传 |
| APK/AAB | Debug/Release 构建及壳/业务边界校验通过；最终 APK jgl 为 STORED |
| 设备重启/逐边界进程死亡/禁用遥测 | 已通过，详情见第 7 节 |

4 MiB 内存输入微基准，9 次中位数：初轮精确读取 12,095,729 ns、原扩容路径 18,843,437 ns；最终存储用例复测为 14,506,093 ns 与 20,714,062 ns。计时含数组断言，不是磁盘冷读或整机启动指标；不承诺完整启动改善比例。阶段耗时包含对应路径中的同步留证成本，不能直接等同纯 AES/ART CPU 时间。

证据在 `build/p0/`（构建产物目录，不提交为源码）：`library-tests.txt`；`device-final/report.json`（固定快照 110 条事件全部接收，正常会话 gaps=0）；`failures/report.json` 与 tar；`device-reboot/` 重启前后证据；各 build 日志。中途安装/构建失败不替代最终成功记录。

## 5. 复现

```powershell
.\gradlew.bat :dex-report-plugin:test :jiagu-local-runtime:testDebugUnitTest :jiagu-local-runtime:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest :app:bundleDebug '-Pjiagu.telemetry=true' '-Pjiagu.uploader=lows.dgeon.ightr.jiagu.LocalReliabilityUploader'
python scripts/p0_event_server.py
python scripts/p0_install.py --serial <serial> <debug-apk>
adb -s <serial> reverse tcp:18765 tcp:18765
python scripts/p0_device_regression.py --serial <serial>
python scripts/p0_gap_report.py build/p0/device/final.json build/p0/device/receiver.json --output build/p0/device/gaps.json
```

服务端在独立终端持续运行。重跑指定独立 directory 保留证据；`--reboot` 实际重启设备，可能需要解锁/USB 授权。force-stop 后显式启动才继续补传。

`p0_failure_regression.py` 接受 APK、build-tools、同一测试签名 keystore，生成独立故障包并 finally 恢复正常包，未卸载/清数据。DEX 故障工具使用本机已有 Python cryptography，仅为外部测试工具，不进入项目/runtime 依赖。

`p0_process_boundaries.py` 配合 androidTest 专用 LocalRecoveryInstrumentation 执行真实进程死亡；Release 无注入入口。`p0_install.py` 处理已观察的测试安装确认控件，不修改设备安全设置。

## 6. 尚未覆盖

- 生产 schema=4/幂等/ACK 接入和 15 号原始 session 明细重算。
- API 34/36、32 位、配置 splits JNI、更多 OEM 的新代码回归，不以旧版本结果替代。
- 真正 OOM、磁盘满、突然断电、锁定态存储域、大量并发远程进程、损坏数据库恢复。
- 大型真实业务 Payload 的内存峰值、冷启动 P95/P99、慢存储与同步留证开销的完整性能矩阵。

## 7. 最终补充结果

2026-10-16 枚举整理：主阶段按流程声明，补充显示顺序/中文名称/异步属性，新增检查点、诊断码、内部事件名、记录类型枚举；调用点改为枚举，保留公开字符串兼容入口。原事件编号和 JSON wire 字符串不变。插件 46 项、Runtime JVM 3 项、真机 14 项及完整应用验证通过；断网/强杀/重启/ACK 丢失回归快照 458 条事件逐 ID/内容对账通过。初次控制器轮询测试端超时的日志保留在 `enums-device.log`，增加有界轮询重试后复测通过，证据为 `enums-device-verified/`。完整枚举目录见 [阶段与枚举](13-startup-stage-enums.md)。

2026-10-16 接口映射补充：`device.oaid` 已按要求使用事件原始 sessionId。八个内置阶段均验证该映射，不同会话值不同，重复序列化保持一致；Debug 构建及 PBEM00 真机完整应用/协议回归通过，证据为 `oaid-session-build.log`、`oaid-device-test.txt`。测试关闭遥测，没有调用生产上报接口。

- **物理设备重启**：重启前快照中 232 条应传事件全部按原 ID/内容核对通过；包括历史已确认记录及本次待传事件，并非新增 232 次启动。USB 授权恢复后通过 p0_resume_reboot.py 继续对账，没有再次重启或清数据。
- **最终源码完整应用回归**：正常启动、真实连接失败、force-stop、应用重启、ACK 丢失、重复 HTTP 投递全部通过；固定快照 314 条应传事件与接收器逐 ID/内容一致。证据：`delivery-device/report.json`、`final.json`、`receiver.json`。
- **缺口解释**：33 个已记录会话中，28 个未收到成功事件的阶段位置，归为 5 个 CONFIRMED_FAILURE 和 23 个 NOT_REACHED。五个失败均为 ACKED；其余 23 个关联更早失败，标记 NOT_GENERATED，不当作网络漏报。见 `delivery-device/gaps.json`。这些是受控测试结果，不代表 15 号历史数据已经归因。
- **20 组真实进程死亡**：5 个阶段标签（读取、解密、DEX、ClassLoader、Application）× 4 个边界（ENTER sync 后、终态 sync 后、半帧写入后、SQLite 事务未提交）全部通过。新进程恢复检查点、唯一终态、恢复诊断和未提交事务回滚正确。测试驱动直接调用同一 journal/store，验证持久化机制；不声称覆盖框架内每条指令的任意强杀。证据：`process-boundaries/report.json`。
- **关闭遥测的最终 Release**：Application/Activity/JNI/SPI/协程/schema 验证通过，启动存储目录 SHA-256 为 `84e8fd1ea7225b3dbd63c6f5c222d0dd5a6a2cd6fb02e9ecceeda05b108d60ea`，执行前后不变。验证后恢复 Debug 测试包。证据：`disabled-release-test.log`、`disabled/instrumentation.txt`。
- **最终产物**：Debug APK/AAB、启用遥测的 Release APK/AAB、禁用遥测的 Release APK 均构建通过。启用版保存在 `build/p0/release-telemetry.apk`，仍使用项目既有调试签名，不是正式发布产物。检查该 APK 不含 LocalReliabilityUploader、LocalRecoveryInstrumentation 或 localhost 测试端地址；jgl 为 STORED。
- **测试收尾**：已恢复正常 Debug 测试 APK，没有保留故障包；停止测试应用及本机接收器，保留日志、数据库和测试证据供复核。未提交、推送或发布代码，未改动原有 `14-compatibility-security-upgrade-assessment.md`。

逐边界测试的构建入口：

```powershell
.\gradlew.bat :jiagu-local-runtime:assembleDebugAndroidTest '-Pjiagu.localTestRunner=io.github.xjc.jiagu.local.LocalRecoveryInstrumentation'
python scripts/p0_install.py --serial <serial> jiagu-local-runtime/build/outputs/apk/androidTest/debug/jiagu-local-runtime-debug-androidTest.apk
python scripts/p0_process_boundaries.py --serial <serial>
```

该属性只改变 androidTest runner，默认仍为 AndroidJUnitRunner，不增加 runtime 依赖。使用默认 runner 执行 13 项 JUnit 测试时重新按默认配置构建测试 APK。
