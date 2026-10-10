# logging：Android 本地诊断日志

## 职责与边界

独立 Kotlin 日志模块，参考 xLog 的输出分离和 Logback 的异步/滚动设计，未复制或翻译其源码，也不依赖两库。宿主决定记录内容、脱敏规则、分享和上传。模块不依赖 app、network、ui、Hilt，不接管崩溃处理器，不抓取其他应用日志。

minSdk 26、compileSdk 37、JVM 字节码 11；沿用 AGP 内置 Kotlin 和现有 Coroutines。使用公开 Android API、java.time 和应用私有目录，无反射、native 库或存储权限。API 26–37 是兼容目标，设备验证必须另行记录。

## 接入与最小示例

消费者声明 `implementation(project(":logging"))`。Application.onCreate 初始化一次；业务类按固定名称获取 Logger，同名始终复用同一个对象。所有 Logger 共用后台写入器；默认 getExternalFilesDir(null)/logs（通常为 /sdcard/Android/data/包名/files/logs），导出 cacheDir/log-exports，无存储权限。自定义 directoryName 时，宿主同步修改备份排除规则。

```kotlin
// Application.onCreate
val initialization = Logging.initialize(this, LogConfig(
    directoryName = "logs",
    datePattern = "yyyy/MM/dd HH:mm:ss.SSS",
    logcatMinLevel = null,
    retentionDays = 7,
))

// 业务类成员
private val logger = Logging.getLogger("Upload")
// 或 Logging.getLogger(UploadRepository::class.java)，使用完整类名
logger.info("开始上传 operationId=u17")
logger.error("上传失败 operationId=u17", exception)
logger.warn("上传暂时失败，稍后重试", exception) // 所有等级均支持可选 Throwable
logger.debug { "昂贵调试正文：$details" } // 等级与容量允许后执行，lambda 在调用线程运行
logger.warn(exception) { "按需构造警告正文" } // 四个等级都支持延迟正文 + Throwable
if (logger.isEnabled(LogLevel.DEBUG)) {
    // 在准备多步诊断数据之前判断等级；不保证随后入队成功
}

// 挂起接口，勿 runBlocking 阻塞主线程
val result = Logging.flush()
val archive = Logging.export()
val status = Logging.status()
```

初始化入口 [Logging.kt](src/main/java/com/ttxz/base/logging/Logging.kt)；命名记录入口 [Logger.kt](src/main/java/com/ttxz/base/logging/Logger.kt)；共享写入器 [LogEngine.kt](src/main/java/com/ttxz/base/logging/LogEngine.kt)；配置 [LogConfig.kt](src/main/java/com/ttxz/base/logging/LogConfig.kt)；滚动清理 [LogFiles.kt](src/main/java/com/ttxz/base/logging/internal/LogFiles.kt)。

### 初始化、缓存和失败

未传参数使用默认值，LogConfig 构造不因值非法抛异常。initialize 返回 LogInitialization：enabled 表示配置有效且后台已创建，尚未探测磁盘可写性；运行时错误通过 status 查看。warnings 返回问题字段名或错误类型，directory 返回实际选择的日志目录绝对路径（创建实例失败为 null，目录按需创建）。每次 initialize 调用直接以 Logging 为 TAG 输出中文成功/失败信息及原因，不受 logcatMinLevel 影响，不进入文件队列；成功只表示后台已启动，未验证磁盘写入。无效日期格式回退默认，其余无效配置停用实例，不擅自增加预算。也可提前调用 config.validationWarnings()。停用时记录返回 false 并计入 dropped，flush/close 返回失败，export 返回 Failure，不输出 Logcat、不读写日志/导出目录。可预期初始化异常捕获后返回失败；不能保证 JVM 内存耗尽等系统故障可恢复。

初始化前 getLogger 可用，记录拒绝并计数，不保存待补写正文；初始化后已有 Logger 自动可用。重复初始化返回 AlreadyInitialized，保留首次结果和写入器；Logging.initialization 保存首次结果。首次初始化失败也不接受再次初始化，需修正配置后重启进程；关闭后不可重新初始化。只在主应用进程初始化，同一目录不允许多个进程共享。

按 Logback 使用 ConcurrentHashMap 缓存，不设数量上限、不淘汰；类参数等价于完整类名，R8 后类名可能变化；需要跨版本稳定 TAG 时传显式名称。不要把请求 ID、用户 ID 作为 Logger 名称，动态信息放正文。每个 Logger 只有名称和上下文引用，没有独立队列、文件、协程。旧 Logger.create 和逐条传 TAG 的接口已移除：改用 Logging.initialize + Logging.getLogger(name)，管理操作迁移到 Logging。

### 初始化参数

| 参数 | 默认值与含义 |
| --- | --- |
| directoryName | logs，getExternalFilesDir(null) 的直接子目录，允许字母、数字、下划线和短横线 |
| datePattern / zoneId | yyyy-MM-dd HH:mm:ss.SSS / 创建时系统时区 |
| fileMinLevel / logcatMinLevel | INFO / DEBUG，后者 null 关闭 Logcat，正式环境显式选择 |
| queueCapacity | 256，限制准备、排队和处理中记录的总条数；正文不截断 |
| discardingThreshold | null：按最终队列容量的 20% 计算；0 关闭提前丢弃 |
| maxFileBytes / maxDirectoryBytes | 2 MiB 滚动阈值 / 20 MiB 日志总预算 |
| retentionDays | 最近 7 个日历日 |
| flushIntervalMillis | 1000ms，缓冲刷新间隔；必须大于 0 |
| redact | 默认原样返回，在后台脱敏正文，不得阻塞或生成巨大字符串 |

校验边界：目录名 1–64 字符；日期格式 1–80 字符并包含年月日和小时；队列容量大于 0；阈值 0–queueCapacity；单文件滚动阈值和总目录预算大于 0，目录预算不少于单文件；保留天数、刷新间隔大于 0。采用属性类型允许的正值，不另设 4096 条、256 MiB、365 天或 100–60000ms 的策略范围。容量累计使用检查加法，预算比较避免加法/翻倍溢出。

剩余槽数小于 discardingThreshold 时，DEBUG/INFO 提前拒绝，给 WARN/ERROR 留出容量；满时所有等级拒绝，不等待空间。阈值参考 Logback AsyncAppender，槽数包含生产端准备和消费者正在处理的记录，竞争检查属于尽力策略，并非严格等级预留。

## 固定格式

DEBUG、INFO、WARN、ERROR 均支持 `(message, throwable)`，等级由影响程度决定，传异常不强制提升等级；不传异常不自动采集调用堆栈。异常仍受下述限长、脱敏和正文区域约束，原有单参数调用不变。

```text
2026-10-09 15:32:08.125 [Upload] 开始上传 operationId=u17
```

顺序和分隔符由库固定，不暴露整行模板。日期取调用时间；允许年月日、小时分钟秒、小数秒和时区字段及分隔符，不允许文字字面量、数字字面量、换行；初始化检查，非法格式回退默认。TAG 由调用方传入，最多 128 个转义后字符；截断带 `[truncated]` 标记，超长名称可能显示相同前缀。普通无异常正文的换行、反斜线、括号和控制字符转义；携带 Throwable 的记录保留正文和堆栈中的换行及制表符，异常标题、各帧和 Caused by 分行输出，一条记录可占多行；正文不截断，分块转义/编码不拆开代理对和转义序列。TAG 或异常快照省略时附加 `[truncated]` 标记。关联信息放正文，不插入前缀。等级只参与过滤。

异常保存类名、有界 message（每层最多 512 个 UTF-16 字符单元）和有限堆栈（最多 4 层原因、每层 16 帧、合计最多 4096 个 UTF-16 字符单元），不保留 Throwable 到队列，不调用可能生成巨大输出的 printStackTrace，message 与正文统一经过脱敏，不包含 suppressed 异常。Java/Android 的 stackTrace getter 会临时复制完整帧数组，16 帧限制约束遍历和保留，不能消除该临时分配；巨型异常宜改记错误码和简短上下文。普通正文不复制、不限长，队列满时不执行延迟正文；带异常时在后台组合正文和有界快照后执行一次脱敏，此时可能产生额外的完整字符串。脱敏在后台执行，只处理正文，调用方勿将敏感数据放入 TAG。脱敏抛异常时仅丢弃当前记录，保留健康文件流及已有缓冲，不输出未经脱敏内容；宿主回调不得阻塞或生成巨大字符串。

## 性能与丢失契约

- 默认最多 256 条准备、排队和处理中记录；参考 Logback 限制条数，取消固定正文截断及估算文本预算校验，没有另加待处理字节预算。队列持有完整原字符串，大正文积压仍可能增加内存，条数上限不等于堆内存上限；调用方和脱敏回调也会有额外分配。
- 非挂起记录返回 Boolean：true 仅代表接受；过滤、队列满或关闭返回 false。压力下先拒绝低等级，满时丢弃新记录，不等待队列空间、不执行调用方磁盘 IO。记录顺序以成功入队为准。
- UTF-8 字符流配合 8192 字符写缓冲，正文以最多 4096 字符的临时块转义、编码，不生成完整转义副本或整条 UTF-8 字节数组。写入前无分配扫描字节数以检查总预算；按 flushIntervalMillis 安排刷新（默认每秒），显式 flush/快照/滚动/关闭也刷新。磁盘失败后退避 1–60 秒，期间不重试每条记录，计入 failed；恢复后只处理新日志，不无限保存失败日志。
- accepted 是接受数；dropped 是关闭/容量等拒绝数（过滤不计）；truncated 是已接受记录的截断数，仅覆盖 TAG 和有界异常快照，普通正文及脱敏扩张不截断；每条最多计一次，即使后来处理失败也保留该计数；written 是追加到文件缓冲成功数；failed 是处理失败、刷新/清理失败及退避期间文件记录跳过数，因此不等于独立日志条数。status 独立读取计数，不是事务快照。
- FlushResult.isSuccessful 只表示本次刷新未出现新错误，必须同时检查累计 status；已丢弃或失败记录不会恢复。flush 不做 fsync，关机/强杀可能丢失排队及未刷新的记录。稳定性优先，不承诺零丢失。
- 刷新和快照命令不受普通记录丢弃策略影响。等待取消时，已入队控制操作先完成资源协调，再传播取消；阻塞磁盘 IO 无法保证立即取消。close 可取消等待，共享关闭仍继续。
- shutdown 用挂起 Logging.close(timeoutMillis)，默认等待超时为 1000ms（协程调度不构成硬实时期限）；超时返回失败，已请求的后台排空继续，已接受数据不会因超时主动清空。调用者取消继续传播且不撤销关闭请求；应用生命周期由宿主负责，Android 不保证调用 Application.onTerminate。不能用关闭钩子承诺末尾日志保留。

Logcat 单独按最多 1024 个 UTF-16 字符单元分块发送，防止一次提交巨大字符串；可能显示为多条 Logcat 行。文件保持一条完整记录。Logcat 平台本身的限制不构成文件正文截断。

### 实测吞吐范围（2026-10-10）

当前所有线程均不等待队列空间；限时等待实验已移除，没有 `enqueueTimeoutMillis` 参数。

DAYU_A6、Android 15/API 35，默认队列 256、提前丢弃阈值 51、每秒刷新，外部应用专属目录；单个后台生产线程，INFO 正文约 100 个汉字及编号，无 Throwable、Logcat 关闭、默认脱敏。每 100ms 提交一批，每档连续 10 秒、单轮测试：

| 提交速率 | 尝试条数 | 完整写入并刷新 | 拒绝条数 |
| --- | --- | --- | --- |
| 1000 条/秒 | 10000 | 10000 | 0 |
| 1500 条/秒 | 15000 | 15000 | 0 |
| 2000 条/秒 | 20000 | 20000 | 0 |
| 2500 条/秒 | 25000 | 23089 | 1911 |
| 3000 条/秒 | 30000 | 22970 | 7030 |

**本轮最高验证零丢弃档位为 2000 条/秒；2500 条/秒已出现拒绝。** 这是上述负载下的测试参考，不是精确最大吞吐或所有 Android 设备的保证。未细测 2000–2500 之间的速率，未证明全天持续运行；瞬间突发、主线程、较长正文、异常、Logcat、脱敏和设备存储性能都会改变结果。生产负载应留余量，并结合 dropped/failed 观察；不将提高队列容量作为持续过载的解决办法。

每 10ms 采样的整个进程 Java 堆最高约 8.67MiB；各档排空并 GC 后约 2.34–2.59MiB，较各档基线最多增加 8KiB。包含测试代码、数组及未回收临时分配；采样可能漏掉瞬时峰值，不包含 native/整个进程总内存，也不等于日志库独占内存。所有接受记录逐条核对编号和正文，无重复、无缺失，failed 为 0；刷新不代表 fsync。

复现用例：`LoggerStressDeviceTest#measureNonBlockingRateRangeAndHeap`，控制台输出 `LOGGER_RANGE`。完整耗时、内存原始数据和历史实验见 [RELIABILITY.md](RELIABILITY.md)。

## 存储与导出

默认按日期、2 MiB 滚动阈值分片，保留最近 7 个日历日，日志总预算 20 MiB。参考上游完整记录写入：当前分片达到阈值后，在下一条日志前滚动；单条记录可以让分片超过阈值，不拆成多条记录。总预算仍是硬限制；单条转义编码后的记录超过总预算时报告文件写入失败，不部分写入、不为它删除历史日志。总预算压力也可能提前触发滚动。启动首次写入、日期切换、容量压力及内部固定每小时检查清理；清理周期不公开配置，刷新间隔超过一小时也不会推迟清理检查。持续无日志时定时任务也运行，但应用进程退出后不运行。保护当前文件，无法满足预算则停止此次写入并报告失败；预算只包含库命名的 .log 文件，不删除未知文件。

导出快照仅在副本中移除未完成的尾行，不修改源文件。重新打开已有文件时先删除末尾未完成的行（包括残缺 UTF-8），避免与新记录拼接；已经完整落入文件的行保留，上一进程的未知损失不计入新进程统计。

时区在创建时固定；系统时钟回拨仍可能影响保留判断。目录不可与其他实例/进程共享，文件命名不提供跨进程锁。不得让外部程序任意篡改该目录。

空目录 export 返回合法空 ZIP。export 在写入消费者中刷新、复制快照，再在 IO 线程压缩；复制期间普通日志仍可入队，队列满则丢弃计数。ZIP 临时文件成功后重命名；压缩每 8 KiB 检查取消，底层阻塞 IO 仍不保证立即中断。异常不删除源日志，取消后尝试清理本次快照。清理失败计入 failed / lastError，不覆盖原始失败或取消；已发布且可用的 ZIP 仍返回 Success，宿主可通过 status 发现清理错误。旧 ZIP 在导出前按保留天数和约两倍日志预算清理，成功导出的缓存文件以后可能被自动删除。宿主及时分享或保存；不保证持久保留导出文件。进程骤停遗留快照和临时 ZIP 在下次导出前清理。导出还需一份日志快照和临时 ZIP 的额外空间；日志预算不等于应用总磁盘预算，ZIP 元数据也有开销。

## 学习与验证

先读配置和 Logging/Logger，再读 LogEngine 的 record → commands → consume → LogFiles；练习把容量设为 1，解释 true、written 和 flush 的区别。

```powershell
.\gradlew.bat :logging:testDebugUnitTest :logging:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :logging:connectedDebugAndroidTest --console=plain
```

单测覆盖前缀/注入、容量拒绝、导出内容、IO 失败恢复、滚动/保留/容量、日期校验及代理对转义。设备验收应覆盖 API 26、29/30、35、37，记录实际文件、重启、导出及主线程性能；普通单测和构建不等于设备证据。

整体审查和两个框架的机制对照见 [REVIEW.md](REVIEW.md)；第一阶段验收范围、可复现命令和实测结果见 [VERIFICATION.md](VERIFICATION.md)。构建、测试报告和真实设备证据分别记录，不把单设备结果视为跨版本或长期性能保证。

## 来源与当前限制

设计参考：[xLog](https://github.com/elvishew/xLog)、[logback-android](https://github.com/tony19/logback-android)。重点借鉴问题：xLog #74/#111/#144/#148；logback-android #184/#344/#369/#371。独立实现，不宣称修复了上游库。

第一版没有自动上传、共享文件多进程协调、文件加密、崩溃/ANR 收集、SLF4J 适配、任意 Printer/格式化接口或自定义字段 Map。正式环境应显式选择 Logcat 等级和脱敏；已有单设备短时压力与保留堆测量；生产负载、其他 Android 版本与更长期运行仍需宿主验证。当前验证范围见验收记录，不能视为全部 Android 版本或生产压力验证通过。

## 本次改造依据和验证

Logback 的 [LoggerContext](https://github.com/tony19/logback-android/blob/main/logback-android/src/main/java/ch/qos/logback/classic/LoggerContext.java) 提供同名缓存；[AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html) 提供队列阈值和关闭等待设计；[Appender](https://logback.qos.ch/manual/appenders.html) 提供输出递归保护、状态与生命周期设计。不等待满队列空间；不采用 xLog FilePrinter 的无界队列。后台脱敏和 Logcat 回调同步调用本日志系统时，递归记录拒绝并计数，避免回调产生无限日志。内部错误只记录累计计数与最近错误类型，不再次调用业务 Logger。正文生产和输出回调中递归记录会拒绝；回调内同步等待 flush/export 会返回失败以避免自等待。宿主不得在回调中无限阻塞、等待其他线程绕回日志系统或关闭全局日志。

第一阶段的实现与验证记录（当前收尾复测为 2026-10-10）统一维护在 [VERIFICATION.md](VERIFICATION.md)，避免多份测试数字相互漂移。

## 本次 API 调整

已移除 `LogConfig.maxRecordChars` 与 `queueTextBudget` 校验；已有配置删除对应命名参数即可。`maxFileBytes` 从硬上限调整为滚动阈值；`truncated` 只统计 TAG 和异常快照省略。普通正文完整写入，目录总预算和满队列拒绝仍生效。设计依据与本次验证见 [VERIFICATION.md](VERIFICATION.md)。

`cleanupIntervalMillis` 已从公开配置移除，清理检查内部固定为 1 小时；旧调用删除该命名参数即可。其余默认值不变。更大的队列、更小的刷新间隔仍有内存或唤醒开销，使用者按实际负载配置；初始化校验只检查有效值与必要关系，不承诺任意配置都有同样的性能。

单条超过总目录预算仅该条写入失败，不触发磁盘退避。导出目录、快照、压缩与发布失败均计入 failed / lastError，同一错误只计一次；取消继续传播。

日志库内部诊断：初始化配置 `isDebugLog = true` 后，各等级内部诊断全部直接输出到 Logcat（TAG 为 Logging），不受业务 `logcatMinLevel` 影响；默认 false，关闭时不构造诊断正文。覆盖配置检查、目录选择、后台启动、文件打开/滚动、文件清理、手动刷新、导出、关闭及内部错误，不输出业务正文。中文初始化最终结果仍始终输出，重复初始化保留首次配置。

外部应用专属存储不可用（getExternalFilesDir 返回 null）时，初始化返回停用结果并输出中文原因，不回退到其他目录。运行中存储不可写沿用失败计数与退避机制；应用卸载时该目录随应用数据删除。旧版本 filesDir/logs 不自动迁移或删除，备份规则继续排除旧目录。

携带 Throwable 时保留换行与制表缩进，堆栈采用异常标题、逐行 at 和 Caused by 格式；仍保留有界快照和统一脱敏，不保证完整 printStackTrace（省略超限帧和 suppressed）。多行记录的骤停修复仅移除未以换行结束的残尾，已写出的完整行可能属于不完整的异常记录。

日志文件命名：每日未分片时为 yyyy-MM-dd.log；首次滚动时将其重命名为 yyyy-MM-dd-000000.log，新分片为 yyyy-MM-dd-000001.log，之后序号递增。兼容已有带序号文件，重启追加最新可写分片；清理和导出同时识别两种名称。重命名失败按文件 IO 失败处理，不覆盖原文件。

内部诊断的失败信息包含中文操作名称与异常类型，不包含异常 message 或业务正文；文件流关闭失败输出错误提示。测试中的查找与清理以初始化结果/写入器实际目录为准，自定义目录用例彼此隔离。最新验收为 49 个 JVM 测试和 4 个 API 35 设备测试，详见 VERIFICATION.md。

## 第二阶段可靠性验证

本轮补充目录占用、首次滚动重命名失败、清理失败、多行 UTF-8 残尾恢复测试；压力与实际强杀实验改用当前外部专属存储。API 35 页面及文件验收通过，其他 Android 版本按用户选择保留待验收，不能宣称跨版本完成。结果、复现命令和限制见 [RELIABILITY.md](RELIABILITY.md)，强杀脚本见 [verify-force-stop.ps1](scripts/verify-force-stop.ps1)。
