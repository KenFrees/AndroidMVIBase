# logging：Android 本地诊断日志

## 职责与边界

独立 Kotlin 日志模块，参考 xLog 的输出分离和 Logback 的异步/滚动设计，未复制或翻译其源码，也不依赖两库。宿主决定记录内容、脱敏规则、分享和上传。模块不依赖 app、network、ui、Hilt，不接管崩溃处理器，不抓取其他应用日志。

minSdk 26、compileSdk 37、JVM 字节码 11；沿用 AGP 内置 Kotlin 和现有 Coroutines。使用公开 Android API、java.time 和应用私有目录，无反射、native 库或存储权限。API 26–37 是兼容目标，设备验证必须另行记录。

## 接入与最小示例

消费者声明 `implementation(project(":logging"))`。Application.onCreate 初始化一次；业务类按固定名称获取 Logger，同名始终复用同一个对象。所有 Logger 共用后台写入器；默认 getExternalFilesDir(null)/logs（通常为 /sdcard/Android/data/包名/files/logs），无存储权限。自定义 directoryName 时，宿主同步修改备份排除规则。

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
val filesResult = Logging.logFiles() // 使用 fold/onFailure 处理查询失败
val latest = filesResult.getOrNull()?.firstOrNull()
val dayFiles = Logging.logFiles(java.time.LocalDate.of(2026, 10, 10))
val status = Logging.status()
```

初始化入口 [Logging.kt](src/main/java/com/ttxz/base/logging/Logging.kt)；命名记录入口 [Logger.kt](src/main/java/com/ttxz/base/logging/Logger.kt)；共享写入器 [LogEngine.kt](src/main/java/com/ttxz/base/logging/LogEngine.kt)；配置 [LogConfig.kt](src/main/java/com/ttxz/base/logging/LogConfig.kt)；文件查询 [LogFileQueries.kt](src/main/java/com/ttxz/base/logging/LogFileQueries.kt)；滚动清理 [LogFiles.kt](src/main/java/com/ttxz/base/logging/internal/LogFiles.kt)。

### 初始化、缓存和失败

未传参数使用默认值，LogConfig 构造不因值非法抛异常。initialize 返回 LogInitialization：enabled 表示配置有效且后台已创建，尚未探测磁盘可写性；运行时错误通过 status 查看。warnings 返回问题字段名或错误类型，directory 返回实际选择的日志目录绝对路径（创建实例失败为 null，目录按需创建）。每次 initialize 调用直接以 Logging 为 TAG 输出中文成功/失败信息及原因，不受 logcatMinLevel 影响，不进入文件队列；成功只表示后台已启动，未验证磁盘写入。无效日期格式回退默认，其余无效配置停用实例，不擅自增加预算。也可提前调用 config.validationWarnings()。停用时记录返回 false 并计入 dropped，flush/close 返回失败，logFiles 返回失败，不输出 Logcat、不读写日志目录。可预期初始化异常捕获后返回失败；不能保证 JVM 内存耗尽等系统故障可恢复。

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
- UTF-8 字符流配合 8192 字符写缓冲，正文以最多 4096 字符的临时块转义、编码，不生成完整转义副本或整条 UTF-8 字节数组。写入前无分配扫描字节数以检查总预算；按 flushIntervalMillis 安排刷新（默认每秒），显式 flush/滚动/关闭也刷新。磁盘失败后退避 1–60 秒，期间不重试每条记录，计入 failed；恢复后只处理新日志，不无限保存失败日志。
- accepted 是接受数；dropped 是关闭/容量等拒绝数（过滤不计）；truncated 是已接受记录的截断数，仅覆盖 TAG 和有界异常快照，普通正文及脱敏扩张不截断；每条最多计一次，即使后来处理失败也保留该计数；written 是追加到文件缓冲成功数；failed 是处理失败、刷新/清理失败及退避期间文件记录跳过数，因此不等于独立日志条数。status 独立读取计数，不是事务快照。
- FlushResult.isSuccessful 只表示本次刷新未出现新错误，必须同时检查累计 status；已丢弃或失败记录不会恢复。flush 不做 fsync，关机/强杀可能丢失排队及未刷新的记录。稳定性优先，不承诺零丢失。
- 刷新命令不受普通记录丢弃策略影响。等待取消时，已入队控制操作先完成资源协调，再传播取消；阻塞磁盘 IO 无法保证立即取消。close 可取消等待，共享关闭仍继续。
- shutdown 用挂起 Logging.close(timeoutMillis)，默认等待超时为 1000ms（协程调度不构成硬实时期限）；超时返回失败，已请求的后台排空继续，已接受数据不会因超时主动清空。调用者取消继续传播且不撤销关闭请求；应用生命周期由宿主负责，Android 不保证调用 Application.onTerminate。不能用关闭钩子承诺末尾日志保留。

Logcat 单独按最多 1024 个 UTF-16 字符单元分块发送，防止一次提交巨大字符串；可能显示为多条 Logcat 行。文件保持一条完整记录。Logcat 平台本身的限制不构成文件正文截断。

### 实测吞吐范围（2026-10-10）

当前所有线程均不等待队列空间；限时等待实验已移除，没有 `enqueueTimeoutMillis` 参数。

下表为提交 2e0e55e 的历史实测；本次移除导出未重跑吞吐和堆峰值测试，写入路径保持原策略。

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

## 存储与文件查询

默认按日期、2 MiB 滚动阈值分片，保留最近 7 个日历日，日志总预算 20 MiB。参考上游完整记录写入：当前分片达到阈值后，在下一条日志前滚动；单条记录可以让分片超过阈值，不拆成多条记录。总预算仍是硬限制；单条转义编码后的记录超过总预算时报告文件写入失败，不部分写入、不为它删除历史日志。总预算压力也可能提前触发滚动。启动首次写入、日期切换、容量压力及内部固定每小时检查清理；清理周期不公开配置，刷新间隔超过一小时也不会推迟清理检查。持续无日志时定时任务也运行，但应用进程退出后不运行。保护当前文件，无法满足预算则停止此次写入并报告失败；预算只包含库命名的 .log 文件，不删除未知文件。

重新打开已有文件时先删除末尾未完成的行（包括残缺 UTF-8），避免与新记录拼接；此前完整行保留。文件查询不修改源文件、不修复残尾，读取方需要容忍骤停留下的不完整尾行。

时区在创建时固定；系统时钟回拨仍可能影响保留判断。目录不可与其他实例/进程共享，文件命名不提供跨进程锁。不得让外部程序任意篡改该目录。

文件查询使用 `Logging.logFiles(date: LocalDate? = null): Result<List<File>>`，也可导入 `com.ttxz.base.logging.logFiles`，对初始化结果调用 `initialization.logFiles(date)`。不增加配置项；查询使用初始化结果中实际目录，日期按文件名匹配（日志日期由初始化时的 zoneId 决定）。

全部结果按日期降序、同日分片序号数值降序排序；同日无序号文件与编号文件并存时，无序号文件视为最新，与写入器的兼容处理一致。`files.firstOrNull()` 获取最新文件；查询某天返回当天全部分片。忽略未知文件、目录、无效日期及超出 Long 范围的序号，兼容超过六位的有效分片序号。

IO 在 Dispatchers.IO 执行：未初始化、初始化停用、路径非目录或列举失败返回 Result.failure；未创建的目录与没有匹配文件成功返回空列表，协程取消传播。查询不自动 flush、不创建目录、不复制、不压缩、不读取正文，不改变写入失败计数。初始化成功后，即使关闭日志写入，仍可查询已有文件。

上游需要读取最近内容时先检查 `Logging.flush()`，再查询并在 IO 线程流式读取；大文件使用 `useLines`/输入流，避免 `readText` 一次装入内存。查询与读取不是原子快照：文件可能追加、首次滚动时重命名、被保留策略删除。上游应处理读取失败，需要稳定副本时由上游自行复制；不得随意重命名、删除或覆盖正在写入的文件。库不提供 ZIP、分享或上传，也不自动删除旧版本遗留的 cacheDir/log-exports，由宿主自行处理旧缓存。

## 学习与验证

先读配置和 Logging/Logger，再读 LogEngine 的 record → commands → consume → LogFiles；练习把容量设为 1，解释 true、written 和 flush 的区别。

```powershell
.\gradlew.bat :logging:testDebugUnitTest :logging:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :logging:connectedDebugAndroidTest --console=plain
```

单测覆盖前缀/注入、容量拒绝、文件查询、IO 失败恢复、滚动/保留/容量、日期校验及代理对转义。设备验收应覆盖 API 26、29/30、35、37，记录实际文件、重启、文件查询及主线程性能；普通单测和构建不等于设备证据。

整体审查和两个框架的机制对照见 [REVIEW.md](REVIEW.md)；第一阶段验收范围、可复现命令和实测结果见 [VERIFICATION.md](VERIFICATION.md)。构建、测试报告和真实设备证据分别记录，不把单设备结果视为跨版本或长期性能保证。

## 来源与当前限制

设计参考：[xLog](https://github.com/elvishew/xLog)、[logback-android](https://github.com/tony19/logback-android)。重点借鉴问题：xLog #74/#111/#144/#148；logback-android #184/#344/#369/#371。独立实现，不宣称修复了上游库。

第一版没有自动上传、共享文件多进程协调、文件加密、崩溃/ANR 收集、SLF4J 适配、任意 Printer/格式化接口或自定义字段 Map。正式环境应显式选择 Logcat 等级和脱敏；已有单设备短时压力与保留堆测量；生产负载、其他 Android 版本与更长期运行仍需宿主验证。当前验证范围见验收记录，不能视为全部 Android 版本或生产压力验证通过。

## 本次改造依据和验证

Logback 的 [LoggerContext](https://github.com/tony19/logback-android/blob/main/logback-android/src/main/java/ch/qos/logback/classic/LoggerContext.java) 提供同名缓存；[AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html) 提供队列阈值和关闭等待设计；[Appender](https://logback.qos.ch/manual/appenders.html) 提供输出递归保护、状态与生命周期设计。不等待满队列空间；不采用 xLog FilePrinter 的无界队列。后台脱敏和 Logcat 回调同步调用本日志系统时，递归记录拒绝并计数，避免回调产生无限日志。内部错误只记录累计计数与最近错误类型，不再次调用业务 Logger。正文生产和输出回调中递归记录会拒绝；回调内同步等待 flush 会返回失败以避免自等待。宿主不得在回调中无限阻塞、等待其他线程绕回日志系统或关闭全局日志。

第一阶段的实现与验证记录（当前收尾复测为 2026-10-10）统一维护在 [VERIFICATION.md](VERIFICATION.md)，避免多份测试数字相互漂移。

## 本次 API 调整

已移除 `LogConfig.maxRecordChars` 与 `queueTextBudget` 校验；已有配置删除对应命名参数即可。`maxFileBytes` 从硬上限调整为滚动阈值；`truncated` 只统计 TAG 和异常快照省略。普通正文完整写入，目录总预算和满队列拒绝仍生效。设计依据与本次验证见 [VERIFICATION.md](VERIFICATION.md)。

`cleanupIntervalMillis` 已从公开配置移除，清理检查内部固定为 1 小时；旧调用删除该命名参数即可。其余默认值不变。更大的队列、更小的刷新间隔仍有内存或唤醒开销，使用者按实际负载配置；初始化校验只检查有效值与必要关系，不承诺任意配置都有同样的性能。

单条超过总目录预算仅该条写入失败，不触发磁盘退避。

日志库内部诊断：初始化配置 `isDebugLog = true` 后，各等级内部诊断全部直接输出到 Logcat（TAG 为 Logging），不受业务 `logcatMinLevel` 影响；默认 false，关闭时不构造诊断正文。覆盖配置检查、目录选择、后台启动、文件打开/滚动、文件清理、手动刷新、关闭及内部错误，不输出业务正文。中文初始化最终结果仍始终输出，重复初始化保留首次配置。

外部应用专属存储不可用（getExternalFilesDir 返回 null）时，初始化返回停用结果并输出中文原因，不回退到其他目录。运行中存储不可写沿用失败计数与退避机制；应用卸载时该目录随应用数据删除。旧版本 filesDir/logs 不自动迁移或删除，备份规则继续排除旧目录。

携带 Throwable 时保留换行与制表缩进，堆栈采用异常标题、逐行 at 和 Caused by 格式；仍保留有界快照和统一脱敏，不保证完整 printStackTrace（省略超限帧和 suppressed）。多行记录的骤停修复仅移除未以换行结束的残尾，已写出的完整行可能属于不完整的异常记录。

日志文件命名：每日未分片时为 yyyy-MM-dd.log；首次滚动时将其重命名为 yyyy-MM-dd-000000.log，新分片为 yyyy-MM-dd-000001.log，之后序号递增。兼容已有带序号文件，重启追加最新可写分片；清理和查询同时识别两种名称。重命名失败按文件 IO 失败处理，不覆盖原文件。

内部诊断的失败信息包含中文操作名称与异常类型，不包含异常 message 或业务正文；文件流关闭失败输出错误提示。测试中的查找与清理以初始化结果/写入器实际目录为准，自定义目录用例彼此隔离。历史验收详见 VERIFICATION.md，当前文件查询调整的验收记录见文末。

## 第二阶段可靠性验证

本轮补充目录占用、首次滚动重命名失败、清理失败、多行 UTF-8 残尾恢复测试；压力与实际强杀实验改用当前外部专属存储。API 35 页面及文件验收通过，其他 Android 版本按用户选择保留待验收，不能宣称跨版本完成。结果、复现命令和限制见 [RELIABILITY.md](RELIABILITY.md)，强杀脚本见 [verify-force-stop.ps1](scripts/verify-force-stop.ps1)。


## 文件查询调整验收（2026-10-10）

移除 Logging.export、ExportResult、ZIP 快照/压缩/导出缓存管理，删除专属测试和注入入口；保留写入、滚动、故障恢复、多行残尾修复测试。增加 Logging.logFiles 与 LogInitialization.logFiles 扩展，查询排序、日期筛选、未知文件忽略、缺失目录、初始化停用、错误路径、取消以及关闭后查询均有测试。

53 条 JVM 测试、5 条 Android 15 设备回归通过；logging/app lint、Debug app 和测试 APK 构建通过。示例页面已通过 phone-harness 实际写入 INFO、刷新和查看文件路径，导出按钮已移除。跨版本仍待验收；本轮未重跑强杀、长期压力或吞吐测试，历史数值仅作参考。

查询取消复查：公开入口在判断初始化前检查当前协程状态；内部列举阶段注入取消异常，验证取消直接传播而非返回 Result.failure，并覆盖未初始化且调用方已取消的分支。本轮 53 条 JVM 测试及 logging lint 通过；未重复设备测试。

## 异常场景补充验收（2026-10-10）

本轮仅完善自动化测试，不修改生产代码或公共 API：

- 队列容量 1、四个等级全部启用，消费者暂停时并发提交 32 条记录：全部拒绝、延迟正文未执行；释放后槽位复用、刷新写入及关闭后拒绝继续验证通过。
- 目录预算 512 字节，保留期设为 Int.MAX_VALUE 天以排除过期清理，历史文件占满且删除被拒绝：接受与写入失败分别计数，退避期间跳过写入；解除删除故障并推进时钟后恢复，预算内写入成功，无关文件保留。
- 查询列举返回 null 或抛 SecurityException：返回 Result.failure，原文件不变；后续正常查询恢复。
- 查询后真实重命名、删除：旧 File 引用读取可能抛 FileNotFoundException，重新查询反映最新路径或空列表，不提供稳定快照。

验证命令：`./gradlew.bat :logging:testDebugUnitTest :logging:lintDebug --console=plain`，56 项 JVM 测试全部通过，logging lint 通过。首轮队列用例因未启用 DEBUG 导致拒绝计数预期错误，已显式启用全部等级后重新通过。

边界：目录预算不是设备物理剩余空间；SecurityException 为列举阶段注入，不代表 Android 挂载权限实测。此前目录占用/重开故障测试继续通过。本轮未填满真实磁盘、改变真实目录权限，已补跑 5 项 Android 15 设备回归及页面接入检查，结果见 [VERIFICATION.md](VERIFICATION.md)。长时间运行、跨版本或 R8 测试仍待验收。
