> 当前 API 已移除 ZIP 导出与文件快照，改为日志文件查询；下文导出相关内容属于历史记录。当前用法与边界以 [README](README.md#存储与文件查询) 为准。

> 2026-10-09：按用户要求移除临时 `loggingCheck` 构建类型及专用接收器。下文相关 R8 结果属于删除前已完成的历史验收，不代表当前仓库仍提供该构建类型；复现命令已移除失效入口。当前 Release 仍关闭优化，未来 R8 验证需另行配置。

> 当前源码验收见文末“2026-10-10 第一阶段收尾复测”；此前压力、强杀和 R8 数字属于对应历史版本，不能替代当前版本复测。

# logging 第一阶段验收记录

验收日期：2026-10-09。范围为本仓库 `logging` 模块与示例接入；不修改其他项目、不提交或推送。第一阶段定位是单进程、应用私有目录中的辅助诊断日志，稳定性优先。

## 完成判据与证据

| 判据 | 实现与验证 |
| --- | --- |
| 固定日期时间 → TAG → 正文，禁止注入额外前缀/伪造新行 | DateTimeFormatter 校验、TAG/正文控制字符转义；格式与代理对回归测试 |
| 统一初始化、命名 Logger 缓存、所有等级可传 Throwable | LoggingContext 并发缓存、提前获取、重复/失败初始化；普通和延迟正文测试 |
| 非法配置不因校验崩溃 | 日期回退、安全参数停用，无效目录/容量/阈值/间隔覆盖；停用不访问日志目录 |
| 队列条数与压力可控，不等待队列空间 | 准备前申请容量；生产中/消费中也占槽；满队列不执行 lambda；四生产者计数核对与设备压力测试 |
| 文件大小、目录预算、日历保留、定期清理 | 单写入者、完整记录后滚动、活动文件保护；滚动/跨日/过期/总预算测试；设备实际文件检查 |
| 文件失败不拖垮记录调用，恢复逻辑可验证 | IO 故障、1–60 秒退避、空刷新不重置退避；Logcat 故障不阻断文件写入 |
| 不夸大日志完整性 | accepted/written/flush/fsync 分别定义；强杀实验确认排队记录可能丢失，重开修复残缺尾行 |
| 导出不损坏原日志且可取消 | 非空/空 ZIP、取消等待资源协调、源文件保留测试 |
| 回调故障和递归有保护 | 正文、脱敏、Logcat 异常隔离；记录递归拒绝；同步 flush/export 自等待拒绝 |
| 可被实际宿主使用、优化后仍可运行 | 示例 Debug 构建；独立包名 loggingCheck 的 R8 构建及真实设备验收入口 |
| 契约可查 | [README](README.md)、公共 KDoc、示例说明及消费者 Skill 同步 |

下文的“当前正文策略验收”补充了后续行为调整与复测，首轮性能数字只属于旧策略。上述判据已完成。兼容目标是 API 26–37；当前环境只有 API 35 真实设备，跨版本运行和生产长期负载不计为已验证。

## 与上游源码对照

本阶段独立实现，没有复制或翻译上游源码，没有依赖其未发布分支。以下链接是 2026-10-09 检查的分支源码，后续可能变化。

| 来源 | 采用或调整 |
| --- | --- |
| xLog [FilePrinter](https://github.com/elvishew/xLog/blob/master/xlog/src/main/java/com/elvishew/xlog/printer/file/FilePrinter.java) | 借鉴后台文件消费与命名/清理职责分离。其 Worker 使用未指定容量的 LinkedBlockingQueue；这里以记录许可限制准备、排队和消费的总记录数。清理失败显式报告，不忽略 delete 结果。 |
| xLog [LogConfiguration](https://github.com/elvishew/xLog/blob/master/xlog/src/main/java/com/elvishew/xlog/LogConfiguration.java) | 借鉴缺省配置与输出选择；固定整行格式属于本库明确契约，不开放自由 Flattener。 |
| logback-android [LoggerContext](https://github.com/tony19/logback-android/blob/main/logback-android/src/main/java/ch/qos/logback/classic/LoggerContext.java) | 同名 Logger 缓存，记录入口与实际输出生命周期分离；按既定要求不设缓存淘汰。 |
| logback-android [AsyncAppenderBase](https://github.com/tony19/logback-android/blob/main/logback-android/src/main/java/ch/qos/logback/core/AsyncAppenderBase.java) | 采用容量、低等级提前丢弃阈值（默认容量的 1/5）与关闭等待思想；为 Android 辅助日志明确选择满队列拒绝，禁止等待空间。 |
| logback-android [UnsynchronizedAppenderBase](https://github.com/tony19/logback-android/blob/main/logback-android/src/main/java/ch/qos/logback/core/UnsynchronizedAppenderBase.java) | 参考输出递归保护与状态报告；本库进一步保护生产 lambda 递归和回调自等待，不通过业务 Logger 报告内部错误。 |
| logback-android [rolling](https://github.com/tony19/logback-android/tree/main/logback-android/src/main/java/ch/qos/logback/core/rolling) | 采用滚动、保留和容量管理职责分离；本阶段只实现固定日期+大小分片，不引入 XML、任意策略插件和完整 Logback 体系。 |

README 中的上游 issue 是问题调查线索，不能据此宣称问题已在上游修复。本库以针对自身实现的回归测试为验收依据。

## 首轮验收结果（正文策略调整前）

- JVM：23 个测试，0 失败；包含并发缓存、压力与计数、所有等级堆栈/延迟正文、独立输出过滤、回调故障、IO 退避、文件管理和 ZIP 内容。
- logging lint、示例 Debug、开启优化的 loggingCheck 构建通过。
- DAYU_A6 / Android 15 / API 35：常规 instrumentation 报告 3 个测试、0 失败、0 错误、0 跳过。覆盖公共入口、文件/导出/重开，以及 30000 次主线程提交压力。
- 另行设备压力：300000 次主线程提交，耗时 37627ms；accepted=153064，dropped=146936，全部已接受记录最终 written，failed=0，日志文件总量不超过 20 MiB。提交 P95=40μs，最大=6853μs。每轮 10000 条后刷新并 GC，保留 Java 堆从 4725680B 到最高 4799408B，增量约 72KiB。累计分配约 803MiB，不能将保留堆平稳理解为无分配或无 GC；该结果包含测试及协程开销，是单设备短时压力样本。
- 独立测试进程实际 force-stop：强杀前已 flush 的记录存在；故意阻塞在脱敏阶段的排队记录丢失；重启后可继续写入，恢复验证 `OK (1 test)`。此实验验证进程终止边界，不验证掉电后的物理持久性。
- R8 独立宿主安装并执行：`Broadcast completed: result=1, data="LOGGER_R8_OK api=35 written=2"`。验收读取 ZIP 核对正文和异常类名，确认同名缓存、公共 API 与优化后实际输出可用；没有添加全库 keep 规则。

测试报告分别查看 `build/test-results/testDebugUnitTest/`、`build/reports/lint-results-debug.html`、`build/outputs/androidTest-results/connected/debug/`。必须读取报告计数与失败项，不能仅用 Gradle 的 BUILD SUCCESSFUL 推断实际执行了测试。

## 复现命令

仓库根目录，使用项目配置的 JDK/SDK；设备序列号按实际设备替换。压力与强杀实验安装独立的 library 测试 APK；app 构建命令只生成 APK，不安装。

```powershell
.\gradlew.bat :logging:testDebugUnitTest :logging:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :logging:connectedDebugAndroidTest --console=plain
# 延长压力（默认常规测试 3 轮；每轮 10000 次，参数最多 120）
.\gradlew.bat :logging:assembleDebugAndroidTest --console=plain
adb -s SERIAL install -r logging/build/outputs/apk/androidTest/debug/logging-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e class com.ttxz.base.logging.LoggerStressDeviceTest -e stressRounds 30 com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL logcat -d -s System.out:I
```

强杀测试有两个阶段，默认常规测试排除该类。两个终端分别执行；只终止独立测试包：

```powershell
# 终端 A：等待出现 LOGGER_CRASH_READY
adb -s SERIAL shell am instrument -w -e class com.ttxz.base.logging.CrashRecoveryDeviceTest#prepareForForceStop -e crashStage prepare com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner
# 终端 B：准备阶段发出 READY 后立即执行；A 显示 Process crashed 是预期
adb -s SERIAL shell am force-stop com.ttxz.base.logging.test
adb -s SERIAL shell am instrument -w -e class com.ttxz.base.logging.CrashRecoveryDeviceTest#verifyAfterForceStop -e crashStage verify com.ttxz.base.logging.test/androidx.test.runner.AndroidJUnitRunner
```

## 验证边界与后续接入

API 26 和 37 没有运行证据，当前 SDK/AVD 未安装对应系统镜像。minSdk/compileSdk 与 lint 支持兼容目标，不替代边界版本设备测试。没有掉电、真实磁盘耗尽、数日运行或生产业务端到端证据；当前 IO 故障测试使用目录被普通文件占用来稳定复现失败和恢复。

宿主接入前明确脱敏、Logcat 等级及备份排除规则。不要动态生成 Logger 名称；不要在日志回调中阻塞。关闭是终态，不能作为页面退出操作。上传、加密、多进程共享、SLF4J、自动崩溃/ANR 收集属于后续独立能力，不纳入本阶段。

## 当前正文策略验收（2026-10-09）

根据用户确认，采用两个框架完整正文写入的思路：取消固定正文截断及容量×字符数的估算预算校验，保留按条数限制的非阻塞队列。参考 xLog 的字符流思路，显式 UTF-8、缓冲并分块转义编码；没有照搬其无界队列和逐条 flush。参考 Logback 的滚动阈值语义，一条记录可以使文件超过阈值，下一条记录前滚动。不是字节预算队列；大字符串积压仍可能增加堆占用。

总目录预算独立保留。单条编码后的文件记录超过总预算时失败，不截断、部分写入或删除历史日志。异常快照仍最多 4 层原因、每层 16 帧，文本合计最多 4096 UTF-16 字符单元；TAG 仍限 128 个转义后字符。Logcat 按小块输出，文件保持单条完整行。

新增验证包括：大于 1 MiB 的含中文/emoji 正文完整性、下条记录滚动、ZIP 逐字核对、脱敏扩张、全 UTF-16 字符扫描的转义/UTF-8 字节预算一致性，以及超目录预算的记录不会清除历史日志。本次最终验证结果：

- 25 个 JVM 测试通过，0 失败/错误/跳过；logging lint 无问题，示例 Debug 和 R8 loggingCheck 构建通过。
- Android 15 / API 35 / DAYU_A6 常规设备测试 4 个通过，0 失败/错误/跳过；包含新增的大于 1 MiB 正文、滚动和导出内容验证。
- 最新 R8 APK 安装后实际调用成功：`LOGGER_R8_OK api=35 written=2`。
- 新策略 300000 次主线程提交压力通过，elapsed=35537ms，accepted=146804，dropped=153196，全部已接受记录最终 written，failed=0，日志文件总量不超过 20 MiB。提交 P95=33μs，最大=2631μs；每轮刷新/GC 后保留 Java 堆从 4746192B 到最高 4819936B，增长约 72KiB；累计分配 358892672B（约 342MiB）。这是短正文、单设备、约 36 秒的样本，不代表大正文积压或长期内存保证。
- 源码空白、文档链接与公共配置调用已核对；未提交、推送代码。本轮未重跑强杀实验，上一节强杀结果属于首轮实现；此次残缺尾行修复仍由回归测试覆盖。

写入路径依据：[xLog SimpleWriter](https://github.com/elvishew/xLog/blob/master/xlog/src/main/java/com/elvishew/xlog/printer/file/writer/SimpleWriter.java)、[logback-android OutputStreamAppender](https://github.com/tony19/logback-android/blob/master/logback-android/src/main/java/ch/qos/logback/core/OutputStreamAppender.java)、[RollingFileAppender](https://github.com/tony19/logback-android/blob/master/logback-android/src/main/java/ch/qos/logback/core/rolling/RollingFileAppender.java)、[SizeBasedTriggeringPolicy](https://github.com/tony19/logback-android/blob/master/logback-android/src/main/java/ch/qos/logback/core/rolling/SizeBasedTriggeringPolicy.java)。采用完整记录与滚动阈值思想，不宣称复制了完整滚动体系。


## 配置精简复测（2026-10-09）

移除公开 `cleanupIntervalMillis`，内部每小时检查清理；刷新和清理分别按单调时钟截止点调度，刷新周期超过一小时或不是一小时整数倍时仍按各自周期执行。移除队列 4096 条、目录 256 MiB、保留 365 天及刷新 100–60000ms 的策略限制，保留正数、丢弃阈值范围和目录预算不少于单文件阈值等必要关系，默认值不变。

存储累计使用 Math.addExact，在无法表示总量时报告失败；容量比较改为减法比较，导出预留空间不再使用可能溢出的预算翻倍。保留天数以 Long 计算毫秒和日历偏移；周期计算先转毫秒，仅换算已逝的完整周期，避免极大刷新间隔转换纳秒溢出。

最终 28 个 JVM 测试、4 个 API 35 设备测试通过，0 失败/错误/跳过；logging lint 无问题，Debug、R8 loggingCheck 构建通过，最新独立 APK 实际验收返回 `LOGGER_R8_OK api=35 written=2`。新增测试覆盖原策略上限之外的合法值、Int/Long 最大正值配置初始化与写入、连续导出不误删旧归档，以及 1ms 定时刷新。没有额外执行一小时等待实验或重跑 30 万次压力测试；前节压力数字属于此前正文策略验收。

## 失败隔离与异常消息复测（2026-10-09）

单条超出目录预算只报告该条失败，不关闭健康写入流或启动退避；导出准备、快照及归档失败统一计数，快照/刷新失败只计一次。异常 message 每层最多保留 512 个 UTF-16 字符单元，保留原有 4096 总快照限制，并统一脱敏。

31 个 JVM 测试与 4 个 API 35 设备测试通过，0 失败/错误/跳过；新增回归覆盖超预算后立即继续写入、导出目录及快照失败各计一次并恢复，以及异常 message 限长、脱敏和堆栈保留。logging lint 无问题，Debug 与 R8 loggingCheck 构建通过；最新独立 R8 APK 返回 LOGGER_R8_OK api=35 written=2。未重新执行强杀或 30 万次压力实验，未提交或推送。

## 脱敏失败隔离与导出 IO 故障复测（2026-10-09）

脱敏回调失败只报告并丢弃当前记录，不再关闭健康文件流；真正的磁盘失败仍走文件故障退避。新增确定性 IO 故障测试覆盖：脱敏失败不关闭流且保留前后正常记录，导出刷新失败只计一次并恢复，ZIP 写入失败与重命名失败各计一次、保留源日志、清理临时文件且可再次导出。归档 IO 注入入口仅为 internal 构造参数，不改变公共 API 或默认文件行为。

34 个 JVM 测试、4 个 API 35 设备测试通过，0 失败/错误/跳过；logging lint 无问题，Debug 与 R8 loggingCheck 构建通过，最新独立 R8 APK 实测 LOGGER_R8_OK api=35 written=2。源码空白检查通过。本轮未重跑强杀或 30 万次压力实验，未提交或推送。

## 整体审查复测（2026-10-09，最终）

整体检查范围、两个框架的源码与 issue 对照、逐项闭环结果见 [REVIEW.md](REVIEW.md)。本轮统一修复导出清理错误被忽略/可能覆盖取消、分片序号按字符串排序及 Int 溢出、导出残缺尾行；补充块级压缩取消检查、实际刷新错误类型传递和未刷新缓冲回归。没有新增公共配置。

- 41 个 JVM 测试，4 个 API 35 常规设备测试，均为 0 失败/错误/跳过。新增 7 个整体链路测试，已有脱敏失败测试增加未刷新 pending 记录断言。
- logging lint 无问题；Debug、R8 loggingCheck 构建通过；最新独立 R8 APK 实际返回 LOGGER_R8_OK api=35 written=2。
- 最新测试 APK 单独执行 30 万次主线程提交：OK (1 test)，accepted=146286，dropped=153714，接受记录全部 written、failed=0；p95=32 微秒、最大 3958 微秒，测量段 35718ms。GC 后保留堆基线 4746192 字节、最大 4824032 字节，增长 77840 字节（约 76 KiB）；测量段累计分配 357401728 字节。此为当前 API 35 单设备短时样本，不是所有负载的性能承诺。
- 最新独立测试进程进入 LOGGER_CRASH_READY 后实际 force-stop，再运行 verifyAfterForceStop：OK (1 test)。已刷新的 persisted 保留、阻塞排队的 pending 丢失、重启 after restart 正常写入；不验证掉电 fsync 持久性。
- 最终复查生产源码、测试、公开调用、文档和备份规则；本轮确认的问题已闭环，没有留下已知阻断项。API 26/29/30/37 没有实机证据；未做长期生产负载试验。未提交或推送。

## 2026-10-10 第一阶段收尾复测

本轮修复设备测试使用旧内部目录的问题：每个外部目录用例传入独立 directoryName，读取与清理由 LogInitialization.directory / LogEngine.logDirectory 获取实际路径；重启复用同一配置。生产 LogFiles 的滚动、保留和总量清理始终操作构造时的同一个目录，没有另设清理路径。

内部错误新增中文操作上下文，区分正文准备、脱敏、业务 Logcat、写入/滚动、刷新、快照、归档、临时文件清理及关闭；不输出异常 message 或业务正文。关闭失败输出 ERROR 和失败文案，不再输出成功关闭提示。故障后释放文件流失败也计数并报告。

本轮执行：

```powershell
.\gradlew.bat :logging:testDebugUnitTest :logging:lintDebug :app:assembleDebug :app:lintDebug :logging:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ttxz.base.logging.LoggerDeviceTest' --console=plain
```

- BUILD SUCCESSFUL；49 个 JVM 测试，0 失败/错误/跳过。新增操作上下文与异常敏感信息隔离、关闭文件流失败的错误等级/文案及计数回归。
- DAYU_A6，Android 15 / API 35：LoggerDeviceTest 4 个测试全部通过，0 失败/错误/跳过。此前 300 条读到 302 条的数据串扰已消除；自定义外部目录、主线程提交、刷新、导出、重启追加及大 UTF-8 正文均通过。
- 新设备用例验证未滚动为日期.log、首次滚动改名为日期-000000.log 并创建日期-000001.log；异常标题与制表缩进的 at 帧在文件及 ZIP 中保留实际换行。
- 设备 Logcat 确认 isDebugLog=true、logcatMinLevel=null 时仍有初始化过程、实际外部目录、文件打开/滚动、手动刷新、导出及关闭诊断（TAG 为 Logging）。关闭开关不构造正文、各等级输出及输出异常隔离由 JVM 测试覆盖。
- logging Lint 无问题；app Lint 为 0 错误、26 个现有警告；Debug 构建及 git diff --check 通过。
- 本轮未重跑 30 万条压力、独立强杀或 R8 实验，也未验证 API 26/29/30/37；历史数据仅保留为历史证据。未进行示例 app 页面操作验收，未提交或推送。

本轮三个审查问题已修复并复查，当前限定范围的第一阶段收尾验收通过；跨版本、长期负载和正式接入仍以宿主验证为准。自动压缩历史日志不在本阶段范围。

## 2026-10-10 第二阶段可靠性验证

第一阶段生产实现不变，新增故障测试并复测外部目录性能、内存、真实强杀与示例页面。当前 53 个 JVM 测试、5 个 API 35 常规设备测试通过；两组各 30 万次压力及独立强杀恢复通过。详情与跨版本待验收项见 [RELIABILITY.md](RELIABILITY.md)，本节不替代此前历史记录。

历史限时等待实验（该参数和实现已移除）：测试和实际吞吐对比见 [RELIABILITY.md](RELIABILITY.md)。最新 60 条 JVM 测试全部通过；Android 15 吞吐对比及独立主线程保护测试通过，宿主构建和 logging/app lint 通过。

多生产线程补充：最新 62 条 JVM 全部通过，Android 15 的 8 线程/16K 正文/慢消费者测试通过；详情见 RELIABILITY.md 的多生产线程补充章节。仅测试与文档变化，未重复宿主构建。

当前最终方案：限时等待实验已移除，所有线程非阻塞；最新 54 条 JVM、5 条常规设备回归及各 1 条速率/主线程/并发大正文设备测试通过。最高测试零丢弃档为 2000 条/秒，条件与内存见 [README](README.md#实测吞吐范围2026-10-10)，原等待实验及 60/62 条数量均为历史记录。


## 2026-10-10 移除导出、增加文件查询

当前 API 以 logging README 的“存储与文件查询”为准。导出专属测试移除，写入/滚动/恢复测试保留，并增加 6 条文件查询测试；当前 53 条 JVM 和 5 条 Android 15 LoggerDeviceTest 全部通过。新增设备断言覆盖初始化失败时查询失败、公开入口查询、指定日期查询与实际正文读取。logging/app lint、app assembleDebug 与测试 APK 编译通过；页面写入/刷新/查询经 phone-harness 验证。本轮未重跑强杀、30 万条压力或分档吞吐，不将历史报告当作本版性能复测。

查询取消复查：公开入口在判断初始化前检查当前协程状态；内部列举阶段注入取消异常，验证取消直接传播而非返回 Result.failure，并覆盖未初始化且调用方已取消的分支。本轮 53 条 JVM 测试及 logging lint 通过；未重复设备测试。

## 异常场景补充验收（2026-10-10）

本轮仅完善自动化测试，不修改生产代码或公共 API：

- 队列容量 1、四个等级全部启用，消费者暂停时并发提交 32 条记录：全部拒绝、延迟正文未执行；释放后槽位复用、刷新写入及关闭后拒绝继续验证通过。
- 目录预算 512 字节，保留期设为 Int.MAX_VALUE 天以排除过期清理，历史文件占满且删除被拒绝：接受与写入失败分别计数，退避期间跳过写入；解除删除故障并推进时钟后恢复，预算内写入成功，无关文件保留。
- 查询列举返回 null 或抛 SecurityException：返回 Result.failure，原文件不变；后续正常查询恢复。
- 查询后真实重命名、删除：旧 File 引用读取可能抛 FileNotFoundException，重新查询反映最新路径或空列表，不提供稳定快照。

验证命令：`./gradlew.bat :logging:testDebugUnitTest :logging:lintDebug --console=plain`，56 项 JVM 测试全部通过，logging lint 通过。首轮队列用例因未启用 DEBUG 导致拒绝计数预期错误，已显式启用全部等级后重新通过。

边界：目录预算不是设备物理剩余空间；SecurityException 为列举阶段注入，不代表 Android 挂载权限实测。此前目录占用/重开故障测试继续通过。本轮未填满真实磁盘、改变真实目录权限，已补跑 5 项 Android 15 设备回归及页面接入检查，结果见 [VERIFICATION.md](VERIFICATION.md)。长时间运行、跨版本或 R8 测试仍待验收。

### Android 15 实机复验（2026-10-10）

通过 phone-harness Skill 操作已连接 DAYU_A6（API 35）：再次运行接入检查，应用累计接受/写入从 9/9 增至 12/12，拒绝与失败均为 0；DEBUG 过滤，INFO/WARN/ERROR 正文与多行异常核对 6/6。文件查询返回实际外部应用目录下的 2026-10-10.log，有限预览显示原有异常换行。

`:logging:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.ttxz.base.logging.LoggerDeviceTest` 通过，覆盖外部存储不可用、公共 Logger 复用、UTF-8 大记录、目录/分片/多行堆栈、主线程提交及重开，共 5 项设备测试。此次新增的预算和查询故障注入仍是 JVM 证据，未宣称在设备上复现真实磁盘耗尽或权限变化。未进行按钮闪烁逐帧测量、长时间运行或其他版本验证。
