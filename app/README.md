# app：示例宿主

## 职责与当前能力

app 用于启动、展示和验证基础模块。首页提供 UI 组件、网络请求和日志测试入口；UI 演示页包含 Loading 弹窗及三类 Toast，网络演示页调用 JSONPlaceholder 文章接口并接入 common 的轻量 MVI 基类。尚未实现登录或网络列表页。

应用 ID / 命名空间为 `com.ttxz.base`，minSdk 26；依赖 common、network 和 ui，并配置 Compose、Hilt、KSP。

同时依赖 [logging](../logging/README.md)，BaseApp.onCreate 初始化 Logging，独立日志测试页按名称复用 Logger。首页提供“日志测试”入口；页面显示实际保存目录，支持正文输入、各等级写入、ERROR 异常堆栈、挂起刷新和查看文件绝对路径。日志默认保存于 getExternalFilesDir(null)/logs，新外部目录及旧内部目录均排除备份。读取、复制或分享日志由上游负责，示例不提供 ZIP 导出。

| 文件 | 作用 |
| --- | --- |
| [BaseApp.kt](src/main/java/com/ttxz/base/BaseApp.kt) | 带 `@HiltAndroidApp` 的 Application，Hilt 宿主入口 |
| [MainActivity.kt](src/main/java/com/ttxz/base/MainActivity.kt) | `@AndroidEntryPoint` Activity，通过 Navigation Compose 连接首页、UI、网络和日志演示页 |
| [LogDemoScreen.kt](src/main/java/com/ttxz/base/demo/logging/LogDemoScreen.kt) | 固定 TAG 的各等级日志写入、刷新、累计状态与文件查询演示 |
| [UiComponentsScreen.kt](src/main/java/com/ttxz/base/demo/ui/UiComponentsScreen.kt) | Loading 和 Toast 的页面内演示状态与按钮 |
| [data/remote](src/main/java/com/ttxz/base/data/remote) | 宿主内可复用的 PostApi、PostDto 和 Hilt 绑定；复用 network 模块的 Retrofit |
| [demo/post](src/main/java/com/ttxz/base/demo/post) | 文章功能包：Repository、Screen、ViewModel、Action、UiState 和 Effect |
| [Debug 网络安全配置](src/debug/res/xml/debug_network_security_config.xml) | 仅在 Debug 构建中让演示域名信任设备用户 CA，以支持 HTTPS 抓包代理 |
| [ui/theme](src/main/java/com/ttxz/base/ui/theme) | Compose 颜色、字体与主题 |
| [AndroidManifest.xml](src/main/AndroidManifest.xml) | 注册 Application / 启动 Activity，声明 INTERNET 权限 |
| [build.gradle.kts](build.gradle.kts) | 模块依赖、Compose/Hilt/KSP 与构建配置 |

## 如何运行与使用

Android Studio Sync 后选择 app，在 API 26 及以上设备运行。首页显示 `Hello AndroidMVIBase!`、 “UI 组件”和“网络请求示例”及“日志测试”三个入口。UI 页可测试两秒后自动关闭的 Loading 及三类 [ToastHost](../ui/components/toast.md) 消息。网络页点击“请求文章”访问 `GET /posts/1`，请求中显示公共 [LoadingDialog](../ui/components/loading-dialog.md)，成功后显示文章及成功 Toast；“测试 404 错误”也是按钮，请求不存在的文章后显示错误及失败 Toast。页面顶部和系统返回键均可返回首页。

网络页使用现有 Base URL `https://jsonplaceholder.typicode.com/`。该免费模拟服务适合学习，不能保证长期在线，也不应作为正式业务服务；网络不通时页面会显示失败并允许再次请求。

若调试设备使用 HTTPS 抓包代理，Debug 构建只对 `jsonplaceholder.typicode.com` 额外信任设备用户 CA；代理 CA 仍需正确安装在设备上。Release 构建不包含这项信任配置。证书验证失败时，演示页会明确提示检查设备代理及证书，不会关闭 TLS 校验。

仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

默认 Debug APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。编译通过后仍需安装运行来确认设备行为。

后续新增示例时：

1. 先确定本步要演示的基础能力，阅读对应模块 README。
2. 复用宿主的 Hilt 配置、主题和网络依赖；不要在 Activity 或 Composable 中创建 Retrofit/OkHttp。
3. 新功能先按功能建包；共享 API、DTO 和注入配置放入 `data/remote`，具体页面通过 ViewModel / Repository 使用基础模块。
4. 将示例保留为可阅读的用法说明，同时更新本 README 的文件入口和验证步骤。

调用 [common 的结果类型](../common/README.md) 和 [network 的请求封装](../network/README.md) 时，遵守各自语义与当前限制。

## 学习顺序

1. 看 Manifest → BaseApp：系统如何找到 Application，Hilt 从哪里进入？
2. 看 MainActivity → setContent → Theme → NavHost → HomeScreen / UiComponentsScreen：Compose 如何组织页面与返回？
3. 看 Gradle 依赖：为什么 app 同时依赖 common 和 network？
4. 看 UI 演示页按钮如何控制 [LoadingDialog](../ui/components/loading-dialog.md) 并发送 Toast；再沿网络页的 Action → ViewModel → Repository → API → UiState / Effect → Compose 追踪一次请求。文章 ViewModel 继承 [common.mvi.BaseViewModel](../common/README.md#轻量-mvi-的使用与边界)，由基类创建 StateFlow / SharedFlow，业务状态、请求门控和错误映射留在具体 ViewModel。状态使用 collectAsStateWithLifecycle；效果通过 repeatOnLifecycle(STARTED) 收集，停止收集期间的 Toast 允许遗漏，返回页面后仍能读取最新状态。

练习：在网络页分别请求文章和 404，找到两个 Action 如何经过 Repository 更新 UiState 并发送 Effect；在请求中返回首页，观察 ViewModel 清除时请求是否取消；再测试请求中切到后台和旋转屏幕，核对最新 UiState 与临时提示的不同交付行为。

本步通过标准：能追踪应用启动到欢迎页面的调用关系，并分清宿主和基础模块的职责。

## 验证与当前限制

- app 当前测试目录是模板示例，不覆盖业务网络或真实用户流程；common 的 MVI 单测覆盖状态更新及临时效果流契约，不能替代文章页设备验收。
- Navigation Compose 目前只用于首页与演示页之间的导航；文章示例接入 common 的轻量 MVI 基类，不包含通用列表、请求或事件确认系统。
- 标准 release 已启用 R8 代码优化和资源压缩，日志接入在 API 35 实机通过；日志页跨版本验收见 logging/VERIFICATION.md；发布签名和其他网络/UI 全流程仍未完成。
- network 已修正取消传播；日志策略和环境地址配置仍待完善，详见该模块 README。
- 已完成的 Debug 构建记录见 [项目 README](../README.md)，后续修改须提供本次验证结果。

新增学习示例或变更入口时，更新本说明；不要把业务 API 和领域类型塞入公共基础模块。

日志测试：从首页进入“日志测试”，输入正文，点击 INFO/WARN/ERROR 后刷新，检查累计写入数；DEBUG 在当前宿主配置下会被过滤，ERROR 带演示堆栈。“查看日志文件”列出实际文件路径，查询失败显示错误类型；返回首页不关闭应用级日志系统。

日志接入检查：点击“运行接入检查”，检查初始化、同名 Logger 缓存、各等级接受状态，刷新后按唯一标识扫描文件，核对正文及异常换行。读取在 IO 线程分块执行，不加载整个文件；该检查以当前宿主 INFO 文件过滤、关闭业务 Logcat 的配置为基础，不代表长稳或跨版本验收。

文件查询支持输入 yyyy-MM-dd（留空查询全部）；“预览查询结果中的最新文件”仅读取该文件开头最多 8192 字符。查询和预览不会自动刷新，可先点击刷新。文件被清理或读取失败会显示错误类型，退出页面会取消检查及读取。

2026-10-10 验证：`:app:assembleDebug`、`:app:lintDebug` 通过；Android 15 / API 35 实机通过接入检查（INFO/WARN/ERROR 正文及多行异常 6/6、DEBUG 过滤、Logger 缓存）、按日期查询、有界文件预览，以及无效日期 2026-02-30 的提示。尚未覆盖其他 Android 版本、长时间运行和正式版 R8。

日志页执行刷新、查询或接入检查时会暂时禁用操作按钮以防重复触发，但保持按钮颜色；处理状态保留一行位置，避免快速完成时整页变灰闪烁或结果区域跳动。

接入检查按固定 TAG 与正文标识组合核对，异常中出现相同标识不能替代正文；已启用等级有任意记录被拒绝时，最终显示未通过，即使其余记录已写入。

## Release / R8 日志验证

AGP 9.4 的 `release.optimization.enable = true` 使用默认 Android 优化规则并启用资源压缩；没有新增构建类型，也没有将 release 签名固定为 debug。执行 `./gradlew.bat :app:assembleRelease :app:lintRelease --console=plain`，默认产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`，mapping 为 `app/build/outputs/mapping/release/mapping.txt`。

2026-10-10：标准 release 构建与 lint 通过。仅为设备验证，用本地 debug 证书签署了构建目录内的 `app-release-local-test.apk`，它仍是不可调试、已 R8 优化的 release，不能作为发布签名方案。安装覆盖当前示例 app 后，phone-harness 在 Android 15 验证初始化、Logger 缓存、INFO/WARN/ERROR、DEBUG 过滤、刷新、文件查询与预览，正文/多行异常 6/6。实际混淆堆栈已用本次 mapping 还原到 LogDemoChecks.kt 和 LogDemoScreen.kt。

只验收了日志页面，未据此声明网络示例或整个应用已完成 release 验收。已补验优化 APK 的 2 MiB 分片、过期测试文件清理和刷新后的强杀重启；实际数据见 logging/VERIFICATION.md。日志页面的文件读取会处理 IO 异常，但仍不提供稳定快照。

“分片与清理检查（写入约 3 MiB）”专用于示例默认配置（2 MiB 分片、7 天保留）：分 24 批写入 768 条、每条 4096 字符载荷，每批刷新；IO 线程分块核对编号完整、无重复并跨至少两个文件。检查会创建一个独立的 2000 年过期测试文件，确认滚动期间自动清理；失败/取消时仅移除此轮创建的夹具。已写入的测试日志遵循正常保留与容量策略，不自动删除。退出页面可取消检查，已接受记录仍可能继续写入。宿主修改阈值或保留期后应同步调整该演示，不能将其视为任意配置的通用验收器。

最终优化 release 已在 API 35 通过该检查，并在刷新后 force-stop/重启，通过新日志核对和历史文件字节比对。该实验不保证未刷新数据、断电持久性或异常多行原子性。

日志页检查和有限预览同时支持 `.log` / `.log.gz`，使用流式解压，不将历史归档整体加载到内存。历史自动压缩契约见 [logging README](../logging/README.md#历史日志自动压缩)。
