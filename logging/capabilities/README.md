# logging 能力目录

按能力名称、用途或关键词查找；用法列有独立说明时提供链接，其余直接列出关键约束。只登记已实现能力，通用接入与详细说明见 [logging README](../README.md)。源码列包含内部实现供阅读定位，业务调用以公开 API 为准。

| 能力 | 适用场景与检索词 | 用法与约束 | 源码 |
| --- | --- | --- | --- |
| 初始化与配置 | `Logging.initialize`、`LogConfig`、目录、等级、脱敏、`LogInitialization` | 宿主显式初始化，重复调用保留首次配置；通过 enabled / warnings / directory 检查结果 | [Logging.kt](../src/main/java/com/ttxz/base/logging/Logging.kt)、[LogConfig.kt](../src/main/java/com/ttxz/base/logging/LogConfig.kt) |
| 分级日志与异常 | `getLogger`、DEBUG、INFO、WARN、ERROR、Throwable、延迟正文、Logcat | 同名 Logger 复用，名称保持固定；返回 true 仅代表接受，不等于落盘 | [Logger.kt](../src/main/java/com/ttxz/base/logging/Logger.kt)、[Logging.kt](../src/main/java/com/ttxz/base/logging/Logging.kt) |
| 有界异步写入 | 队列、非阻塞提交、满队列拒绝、分级过滤、输出递归保护 | 不等待队列空间；队列限制条数，不保证零丢失或固定内存占用 | [LogEngine.kt（内部实现）](../src/main/java/com/ttxz/base/logging/LogEngine.kt) |
| 文件滚动、清理与压缩 | 按日文件、大小分片、保留天数、总容量、GZIP、`.log.gz` | 自动处理；单文件大小是滚动阈值，总目录预算独立生效；不提供 ZIP 导出 | [LogConfig.kt](../src/main/java/com/ttxz/base/logging/LogConfig.kt)、[LogFiles.kt（内部实现）](../src/main/java/com/ttxz/base/logging/internal/LogFiles.kt) |
| 日志文件查询 | `logFiles`、按日期筛选、最新文件、文件列表、读取历史日志 | 挂起返回 Result，IO 执行、取消传播；查询不刷新或读取正文，文件可能滚动、压缩或清理 | [LogFileQueries.kt](../src/main/java/com/ttxz/base/logging/LogFileQueries.kt)、[Logging.kt](../src/main/java/com/ttxz/base/logging/Logging.kt) |
| 刷新、关闭与诊断状态 | `flush`、`close`、`status`、accepted、dropped、failed、内部诊断 | flush 不做 fsync；累计计数不是事务快照；关闭等待取消不撤销关闭请求 | [Logging.kt](../src/main/java/com/ttxz/base/logging/Logging.kt)、[LogConfig.kt](../src/main/java/com/ttxz/base/logging/LogConfig.kt) |

验证入口：[学习与验证](../README.md#学习与验证)、[第一阶段记录](../VERIFICATION.md)、[可靠性记录](../RELIABILITY.md)。

新增或调整公共能力时，同步本表与使用说明。崩溃/ANR 自动采集、自动上传、分享、文件加密、ZIP 导出和多进程共享目录协调尚未提供。
