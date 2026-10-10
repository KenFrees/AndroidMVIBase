> 当前 API 已移除 ZIP 导出与文件快照，改为日志文件查询；下文导出相关内容属于历史记录。当前用法与边界以 [README](README.md#存储与文件查询) 为准。

# logging 第一阶段整体审查（2026-10-09）

> 2026-10-10 更新：下文为 10 月 9 日审查快照；当前目录、多行堆栈、文件命名及内部诊断变更的复查结果见文末和 VERIFICATION.md 最新章节。

> 2026-10-09：临时 loggingCheck 构建及接收器已按用户要求删除。本文 R8 证据为删除前的历史验收，当前配置不提供独立 R8 验收包。

## 范围与结论

本次沿 Logging → Logger → LogEngine → LogFiles 检查初始化、过滤、准备、入队、消费、刷新、滚动、清理、导出、取消和关闭，并检查示例宿主、备份规则、README、消费者 Skill 和回归测试。没有修改其他业务模块、提交或推送。

第一阶段采用独立 Kotlin 实现，借鉴成熟机制，维持诊断日志的有限职责。此次审查确认的缺陷已处理；最终源码复查没有留下已知的阻断项。此结论限于列明的源码、故障测试和设备证据，不承诺未来不会发现问题。

## 两个框架的对照

以下按本轮可读取的源码页面核对，比较具体实现，不把分支源码等同于发布版本，也不据此推断依赖升级可用性。

| 机制 | xLog | logback-android | 当前实现与决定 |
| --- | --- | --- | --- |
| 队列与丢弃 | FilePrinter 使用未指定容量的 LinkedBlockingQueue | AsyncAppenderBase 默认 256 条，默认丢弃阈值为容量的 1/5；neverBlock 可选择 offer | 256 个槽位包括准备、排队和消费；低等级提前丢弃，满时所有等级拒绝，调用者不等磁盘 |
| 输出格式 | Flattener 可定义输出 | Encoder/Layout 可定义输出 | 固定日期时间、等级、TAG、正文；仅开放日期格式，保护业务约定 |
| 写入与刷新 | SimpleWriter 使用字符缓冲并逐条 flush | OutputStreamAppender 先编码为 byte[]，默认 immediateFlush | 明确 UTF-8，分块转义与编码，定时/显式刷新；普通正文完整保留，避免整条转义副本与字节数组 |
| 滚动 | 写入前检查备份策略 | RollingFileAppender 和大小触发策略在追加前决定滚动 | 大小为滚动阈值，完整记录可超阈值，下一条滚动；目录总预算另外约束 |
| 配置与错误 | Builder 补默认策略，Writer 捕获常见 IO 异常 | Appender 配置失败不启动，IO 错误进入状态系统，流关闭错误也报告 | 非法日期回退，其他非法配置停用；内部计数记录错误，不递归输出内部日志；磁盘故障退避，正文处理失败只影响当前记录 |
| 关闭 | FilePrinter 工作线程的生命周期能力较简单 | AsyncAppender 停止时等待并排空队列 | 请求停止后拒绝新记录，超时只停止等待，后台继续排空 |
| 导出与骤停 | 文件命名、备份和清理策略可组合 | 滚动策略可包含压缩 | 运行日志不在滚动时压缩；单独复制快照、生成临时 ZIP、重命名发布；导出失败保留源日志 |

源码证据：[xLog FilePrinter](https://raw.githubusercontent.com/elvishew/xLog/master/xlog/src/main/java/com/elvishew/xlog/printer/file/FilePrinter.java)、[SimpleWriter](https://raw.githubusercontent.com/elvishew/xLog/master/xlog/src/main/java/com/elvishew/xlog/printer/file/writer/SimpleWriter.java)、[AsyncAppenderBase](https://raw.githubusercontent.com/tony19/logback-android/main/logback-android/src/main/java/ch/qos/logback/core/AsyncAppenderBase.java)、[OutputStreamAppender](https://raw.githubusercontent.com/tony19/logback-android/master/logback-android/src/main/java/ch/qos/logback/core/OutputStreamAppender.java)、[RollingFileAppender](https://raw.githubusercontent.com/tony19/logback-android/master/logback-android/src/main/java/ch/qos/logback/core/rolling/RollingFileAppender.java)、[SizeBasedTriggeringPolicy](https://raw.githubusercontent.com/tony19/logback-android/master/logback-android/src/main/java/ch/qos/logback/core/rolling/SizeBasedTriggeringPolicy.java)。借鉴机制，未复制源码。

## 相关 issue 的处理边界

这里只记录读取页面展示的状态与对本模块的启示，不把 issue 关闭等同于所有发布版本已修复。

| 上游问题 | 页面展示状态 | 当前处理及证据 |
| --- | --- | --- |
| [xLog #74](https://github.com/elvishew/xLog/issues/74)：清理策略没有及时生效 | Open | 首次写入、切日、容量压力及每小时维护检查；容量/保留单测覆盖，未用一小时实等替代源码调度检查 |
| [xLog #111](https://github.com/elvishew/xLog/issues/111)：存储总量控制需求 | 本轮读取到问题页面，不据此判定发布支持情况 | 单文件滚动与总目录预算独立，预算失败不部分写入、不为超大单条清空历史 |
| [xLog #148](https://github.com/elvishew/xLog/issues/148)：日志改变线程中断标志 | Open | 不采集线程信息或清除中断；新增独立调用线程中断标志回归 |
| [logback-android #184](https://github.com/tony19/logback-android/issues/184)：保留历史没有删除旧文件 | Closed / archived | 日历日期保留与目录预算共同生效，活动文件受保护，不依赖 XML 策略组合 |
| [logback-android #344](https://github.com/tony19/logback-android/issues/344)：AGP/R8 日期转换器问题 | Open | 无反射或动态转换器，最新 R8 APK 实际验收通过 |
| [logback-android #369](https://github.com/tony19/logback-android/issues/369)：滚动压缩时进程死亡导致残缺归档 | Closed，页面关联 #427 | 不在滚动时压缩原日志；单独快照与临时归档，源日志不因归档失败删除；不推断 #427 所在发布版本 |
| [logback-android #371](https://github.com/tony19/logback-android/issues/371)：设备关机日志丢失 | Open | 明确 accepted、written、flush 的区别；不做 fsync；最新独立进程强杀实验验证已刷新保留、排队记录可能丢失及重启写入 |

## 整体检查结果与统一修复

| 链路 | 核对结果 / 本次修复 | 主要回归证据 |
| --- | --- | --- |
| 初始化与对象缓存 | 单次初始化、重复调用、预初始化 Logger、无效配置和关闭后行为一致；固定名称缓存不淘汰 | LoggingContextTest、LogConfigPolicyTest |
| 过滤与准备 | 先检查等级/容量，再执行延迟正文；异常快照有界、正文不截断；不清除线程中断 | LoggingContextTest、StageOneRegressionTest、中断线程测试 |
| 队列与递归 | 槽位覆盖准备和消费，控制命令串行，Tick 合并；关闭期间尚未接受的准备记录拒绝并释放槽位 | 并发生产、容量拒绝、回调递归、关闭准备中记录测试 |
| 正文与缓冲 | 脱敏失败不关闭健康流；新增 pending 未刷新断言，确认故障后该记录仍可刷出 | redactionFailureKeepsHealthyStreamAndBufferedRecords |
| 文件 IO 与恢复 | 超预算只影响该条；磁盘错误退避；部分写入后的源文件在重新打开时修复 | oversizedRecordDoesNotBackOffHealthyWriter、partialWriteExportsOnlyCompleteLinesAndNextWriteRepairsSource |
| 滚动与序号 | 改用 Long 数值排序和检查加法，解决六位之后排序错误、Int 溢出；其他文件名格式不变 | rolloverUsesNumericIndicesBeyondSixDigitsAndIntRange |
| 清理与预算 | 已有日期、容量、未知文件保护与溢出检查保留，不增加公开参数 | LoggerTest、LogConfigPolicyTest、StageOneRegressionTest |
| 导出快照 | 快照副本去除不完整尾行；源文件保持原样，等后续写入恢复；快照不再构造无用的返回列表 | 部分写入 → 导出 → 源文件不变 → 恢复写入测试 |
| 导出错误与清理 | 刷新失败传递实际错误类型且只计一次；临时文件删除 false/抛异常均计数；清理错误不覆盖原始失败或取消 | exportFlushFailureCountsOnceAndWriterRecovers、cleanupFalseOrExceptionIsReportedWithoutLosingUsableArchive、cleanupExceptionDoesNotReplacePrimaryFailureOrCancellation |
| 压缩与取消 | 复用 8 KiB 缓冲，每块检查取消；发布、压缩失败均保留源日志并清理临时文件 | archiveWriteAndPublishFailuresCountOnceCleanTempsAndRecover、cancellationDuringArchiveWriteCleansTempsAndDoesNotFailLogger |
| 关闭与结果 | 停止接收、排空、超时继续后台关闭；flush 结果与累计错误不是同一语义 | closeTimeoutStopsAdmissionAndWriterFinishesLater、closeRejectsRecordStillBeingPreparedAndAllControlsFinish |
| Android 集成与文档 | 私有目录、备份排除、无存储权限，正式示例关闭 Logcat；README/Skill/验收记录同步 | 构建、Lint、API 35 设备及 R8 APK 验收 |

## 保留的设计取舍

- 队列限制条数，不是堆字节硬上限。普通正文完整保留；大正文、巨大异常 stackTrace getter 或宿主回调仍可造成分配压力。Logger 名称必须固定；不新增缓存淘汰和正文截断。
- 磁盘写入失败可能部分写入，且缓冲成功不等于持久化。恢复与导出处理残尾，但不能承诺故障前每条记录恰好写入一次或零丢失。
- 清理失败计入状态。已成功发布的 ZIP 仍返回 Success；它与清理遗留快照是两个独立结果。后续导出先重试清理遗留文件，失败时停止新导出，避免持续叠加快照。
- 取消在压缩块之间响应；底层阻塞 IO 不保证立即中断。已入队快照先协调完毕才清理，防止消费者仍在写时删除目录。
- 单目录只允许一个进程/实例。不增加多进程文件锁、加密、上传、ANR/崩溃捕获或完整 SLF4J 体系。
- API 26–37 为编译/兼容目标；实际设备仅 API 35。没有其他版本设备证据，不把最新 SDK 编译通过写成跨版本运行通过。

## 验收

最终命令、测试数量、压力实测、R8 和强杀结果统一记录在 [VERIFICATION.md](VERIFICATION.md) 的整体审查复测章节。此文件保存检查范围、设计依据和闭环结果，避免重复维护测试数字。

## 2026-10-10 收尾复查

本次审查发现并修复：设备测试仍查找/删除内部 logs 目录造成用例串扰；内部错误只有异常类型而没有操作上下文；关闭失败仍显示成功诊断；验收记录没有包含后续变更。现已按实际配置目录读写与清理，各外部目录用例独立，错误增加中文操作名称，关闭诊断按 FlushResult 实际结果输出。生产清理与写入共用 LogFiles.directory。

当前 49 个 JVM 测试、4 个 API 35 设备测试通过，logging/app Lint 和 Debug 构建通过。设备已验证自定义外部目录、初始化过程诊断、多行异常文件及 ZIP、首次分片重命名和重启追加。失败路径的操作诊断、关闭错误提示及敏感信息隔离有确定性 JVM 故障测试。详见 [最新验收记录](VERIFICATION.md)。本轮确认的问题已闭环；跨版本、长期负载、强杀与 R8 的本版本证据仍受验收记录所列限制。
