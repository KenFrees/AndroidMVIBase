---
name: android-mvi-base
description: Use when integrating AndroidMVIBase into a business Android project, calling its existing APIs, or reviewing consumer-side usage. 面向框架使用者及其 AI，不用于维护框架内部实现或设计新公共模块。
---

# 框架使用者指南

## 面向谁

用于业务工程接入、调用和检查 AndroidMVIBase 的现有能力。编写或修改框架本身时使用 [维护者 Skill](../android-mvi-base-maintainer/SKILL.md)。

接入方遵守自己的 AGENTS、包名、架构与提交规则；不要求把业务代码命名为 com.ttxz.base，也不要求按本框架作者的学习步骤开发。不因接入需要就擅自修改框架源码。

## 确认版本与资料

先明确业务仓库、接入方式和实际使用的框架版本，再读对应版本的模块 README 与公开 API。当前框架尚未发布 Maven 坐标，没有自动初始化脚本；不得编造依赖坐标或声称可直接生产使用。

本文件的相对链接适用于保留仓库目录结构的情况。若仅复制 Skill 到业务工程，先由用户提供或从已知配置确认框架源码/文档根目录，再解析对应模块资料；找不到时说明缺失信息，不猜测磁盘路径、版本或全局安装状态。

| 使用内容 | 对应说明 |
| --- | --- |
| 结果分支与回调 | [common README](../../common/README.md) |
| 轻量 MVI 基类与状态/效果边界 | [common README](../../common/README.md#轻量-mvi-的使用与边界)：common.mvi.BaseViewModel；状态不可变，Lambda 原子更新且无副作用；sendEffect 在调用者协程中挂起发送，无订阅者丢弃、不重放，不保证 UI 执行；必须保留的结果写入 UiState，UI 按生命周期收集 |
| Retrofit/Moshi/OkHttp、异常包装 | [network README](../../network/README.md) |
| Compose 界面组件（Loading、Toast） | [UI 组件目录](../../ui/components/README.md) |
| 本地诊断日志 | [logging README](../../logging/README.md)：Application.onCreate 调用 Logging.initialize，业务使用 Logging.getLogger(name) 缓存固定名称，所有等级支持可选 Throwable 和延迟正文，先判断等级和容量再执行 lambda；普通正文不截断，队列仅限条数；所有线程满时立即拒绝，不等待队列空间；单文件大小是滚动阈值，目录总预算独立生效；清理内部固定每小时检查，不公开 cleanupIntervalMillis；数值配置仅校验正数和必要关系，默认值仍建议优先使用；记录 true 不等于已写入；挂起刷新/文件查询，单目录单实例，正式环境配置等级和脱敏；非法日期格式回退默认，其他非法配置停用实例，通过初始化结果 enabled / warnings 查询，directory 为实际目录路径；初始化直接输出中文 Logcat 反馈，不受 logcatMinLevel 影响，成功不代表磁盘写入已验证；flush/logFiles/close/status 由 Logging 统一管理；日志异常 message 每层最多保留 512 个 UTF-16 字符单元，与正文统一脱敏，脱敏失败只丢弃当前记录并保留健康文件流；单条超过总目录预算不触发磁盘退避。logFiles(date) 在 IO 线程查询实际目录，返回 Result<List<File>>，支持日期筛选及从新到旧排序；无文件为空列表，读取失败返回 failure，取消传播。查询不刷新、不复制，文件可能追加、滚动重命名或清理；读取与复制由宿主处理。无 ZIP 导出。 |
| Hilt 宿主与 Compose 示例入口 | [app README](../../app/README.md) |
| 已实现范围与当前限制 | [项目总览](../../README.md)中的当前进度和限制；学习路线不约束接入方 |

## 接入步骤与使用约束

1. 检查现有业务工程的技术栈、minSdk、Hilt 配置和依赖，确定哪些能力适合复用；不要直接覆盖业务工程的 Gradle 或 Application。
2. 当前同一 Gradle 工程使用模块依赖。消费者直接引用 AppResult 时声明 common；直接引用 Retrofit/Hilt 等类型时检查直接依赖，不假设 network 的 implementation 会导出编译 API。具体配置见模块说明。
3. 复用框架提供的网络对象和结果类型，在业务数据层增加 API/Repository。业务代码使用业务包名；页面经其 ViewModel/Repository 获取数据，不在 Composable 内创建 Retrofit/OkHttp。
4. 使用 AppResult 时完整区分 Success、Error、Loading；safeApiCall 不产生 Loading、不主动切线程、不自动解析业务失败码。onSuccess/onError 返回原结果，回调异常不会自动转换成 Error。
5. 若 Retrofit API 返回 Response<T>，明确检查 HTTP 成功状态，不将 Success<Response<T>> 直接等同于请求成功。
6. `safeApiCall` 目前会传播协程取消；使用前仍需核对日志和固定 Base URL 的限制。缺陷或缺少配置入口阻碍接入时，报告证据及影响，提出框架侧改进需求；未经授权不自行补丁框架或修改另一个仓库。
7. 接入 MVI 时使用 common.mvi 的 BaseViewModel，具体 Action / State / Effect 放在业务功能包；基类只提供容器与发送机制，不自动串行化 Action、管理请求或恢复进程。State 应不可变，基于已有字段计算时使用 updateUiState Lambda 且不执行副作用；sendEffect 为挂起函数，不建立隐藏作用域。UiState 使用 collectAsStateWithLifecycle，临时 Effect 在 LaunchedEffect 内结合 repeatOnLifecycle(STARTED) 收集；可遗漏提示才能使用该效果流，不把它当可靠业务结果通道。Compose 生命周期/Hilt 等依赖由宿主提供，详见 common README。
8. 记录业务工程实际接入方式并验证相关调用。原框架 Debug 构建成功不等于当前业务工程已集成或设备已验证。

## 使用方式 Review

范围是消费者是否正确使用框架。按 业务页面 → ViewModel → Repository → API/框架入口 追踪，重点检查：

- 实际版本、可用能力、直接依赖和宿主配置是否匹配。
- 是否复用已有对象，是否存在页面直连网络或重复创建客户端。
- 结果、HTTP/业务失败、加载、取消和生命周期语义是否处理正确。
- 演示服务地址、日志和已知限制是否影响目标环境。
- 使用示例和接入说明是否与实际调用一致，关键场景是否经过验证。

发现框架内部问题可以只读追踪，输出框架文件依据与对接入方的影响，但不因此切换到维护者模式实施修改。

Review 先列问题，给文件/行号、触发条件、影响和建议，再列验证范围与未知事项。只读审查不直接修复；不拿维护者的内部命名、教学进度或未落地 API 作为业务代码的违规依据。

## 完成标准

说明业务侧用了哪些能力、如何调用、已验证什么、还受哪些限制。明确区分源码审查、构建、接口与设备验证；不自动提交、推送或改变业务工程之外的状态。

日志库内部诊断：初始化配置 `isDebugLog = true` 后，各等级内部诊断全部直接输出到 Logcat（TAG 为 Logging），不受业务 `logcatMinLevel` 影响；默认 false，关闭时不构造诊断正文。覆盖配置检查、目录选择、后台启动、文件打开/滚动、文件清理、手动刷新、关闭及内部错误，不输出业务正文。中文初始化最终结果仍始终输出，重复初始化保留首次配置。

日志默认位置为 getExternalFilesDir(null)/logs（通常 /sdcard/Android/data/包名/files/logs），不需要存储权限；外部应用专属目录不可用时停用并报告初始化失败，不回退内部目录。备份排除需覆盖 external 域的日志子目录，保留 file 域规则以排除旧日志。

携带 Throwable 时保留换行与制表缩进，堆栈采用异常标题、逐行 at 和 Caused by 格式；仍保留有界快照和统一脱敏，不保证完整 printStackTrace（省略超限帧和 suppressed）。多行记录的骤停修复仅移除未以换行结束的残尾，已写出的完整行可能属于不完整的异常记录。

日志文件命名：每日未分片时为 yyyy-MM-dd.log；首次滚动时将其重命名为 yyyy-MM-dd-000000.log，新分片为 yyyy-MM-dd-000001.log，之后序号递增。兼容已有带序号文件，重启追加最新可写分片；清理和查询同时识别两种名称。重命名失败按文件 IO 失败处理，不覆盖原文件。

日志清理与写入使用同一实际配置目录；接入测试应从 LogInitialization.directory 获取路径，不硬编码内部 filesDir/logs。库内部错误诊断提供中文操作上下文和异常类型，不输出异常 message 或业务正文，关闭失败不会显示成功提示。

### logging 的 R8 接入

当前 logging 实现无需整包 keep 或额外 consumer 规则；不要为保留日志功能关闭宿主优化。宿主启用标准 R8 优化、保留默认诊断信息，并按每次最终构建归档 mapping.txt。异常文件可提取标准多行堆栈用 Retrace 还原，超出有界快照的帧不能恢复。类对象 Logger 的名称可能混淆，稳定 TAG 使用显式字符串。日志页的优化 release 验证不代表业务全部功能已通过 R8 验收，范围见 logging/README.md。

logging 历史日志会自动变为 `.log.gz`，查询仍返回 File，调用方按扩展名用 GZIPInputStream 流式读取，不能直接 readText 压缩文件。压缩、滚动或清理可能使已查询 File 失效，处理读取失败并按需重新查询；压缩不是 ZIP 导出，调用方式和预算边界见 logging/README.md。

文件日志固定格式为 `[日期时间] [LEVEL] [TAG] 正文`，LEVEL 为 D/I/W/E（对应 DEBUG/INFO/WARN/ERROR），由调用方法决定；宿主只能配置日期格式和传入 TAG，不能改变前缀结构。

## 图片加载

图片加载整体由 common 提供，阅读 [说明](../../common/image-loading.md)。消费者依赖 common 即可使用其 api 导出的 Coil Compose。宿主继承 common.application.BaseApplication，并在 Manifest 中注册自己的 Application；默认使用 ImageLoadingConfig，可覆盖 imageLoadingConfig()。Hilt 注解和业务、日志初始化保留在宿主，onCreate 调用 super；配置在共享加载器创建时读取，不动态更新。已有 Application 基类时可使用 common.image.createImageLoader 接入 Coil 工厂。页面决定占位、失败、空地址图片，不在页面直接调用工厂或反复创建加载器。
