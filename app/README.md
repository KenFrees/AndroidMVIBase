# app：示例宿主

## 职责与当前能力

app 用于启动、展示和验证基础模块。首页提供 UI 组件、网络请求和日志测试入口；UI 演示页包含 Loading 弹窗及三类 Toast，网络演示页调用 JSONPlaceholder 文章接口。尚未实现登录、网络列表页或通用 MVI 基类。

应用 ID / 命名空间为 `com.ttxz.base`，minSdk 26；依赖 common、network 和 ui，并配置 Compose、Hilt、KSP。

同时依赖 [logging](../logging/README.md)，BaseApp.onCreate 初始化 Logging，独立日志测试页按名称获取并复用 Logger。首页提供“日志测试”跳转入口；测试页显示实际日志保存目录，支持自定义正文、各等级写入、ERROR 异常堆栈、挂起刷新和导出；导出 ZIP 保存到私有缓存目录，示例不自动上传或分享。日志默认保存于 getExternalFilesDir(null)/logs，页面显示实际绝对路径；新外部目录及旧内部日志目录均排除备份。页面协程取消时仍先完成已提交快照协调，再清理临时文件。

| 文件 | 作用 |
| --- | --- |
| [BaseApp.kt](src/main/java/com/ttxz/base/BaseApp.kt) | 带 `@HiltAndroidApp` 的 Application，Hilt 宿主入口 |
| [MainActivity.kt](src/main/java/com/ttxz/base/MainActivity.kt) | `@AndroidEntryPoint` Activity，通过 Navigation Compose 连接首页、UI、网络和日志演示页 |
| [LogDemoScreen.kt](src/main/java/com/ttxz/base/demo/logging/LogDemoScreen.kt) | 固定 TAG 的各等级日志写入、刷新、累计状态与导出演示 |
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
4. 看 UI 演示页按钮如何控制 [LoadingDialog](../ui/components/loading-dialog.md) 并发送 Toast；再沿网络页的 Action → ViewModel → Repository → API → UiState / Effect → Compose 追踪一次请求。网络页用 StateFlow 保存持续状态，用不重放的 SharedFlow 触发当前页面的一次性 Toast；此例不代表通用 MVI 基类已设计完成。

练习：在网络页分别请求文章和 404，找到两个 Action 如何经过 Repository 更新 UiState 并发送 Effect；在请求中返回首页，观察请求是否随页面退出而取消。

本步通过标准：能追踪应用启动到欢迎页面的调用关系，并分清宿主和基础模块的职责。

## 验证与当前限制

- 当前测试目录是模板示例，不覆盖业务网络、MVI 或真实用户流程。
- Navigation Compose 目前只用于首页与演示页之间的导航；文章示例有具体 ViewModel，但尚无通用 MVI 基类。
- 当前 Release 关闭优化，尚未完成正式发布验证。
- network 已修正取消传播；日志策略和环境地址配置仍待完善，详见该模块 README。
- 已完成的 Debug 构建记录见 [项目 README](../README.md)，后续修改须提供本次验证结果。

新增学习示例或变更入口时，更新本说明；不要把业务 API 和领域类型塞入公共基础模块。

日志测试：从首页进入“日志测试”，输入正文，点击 INFO/WARN/ERROR 后刷新，检查累计写入数；DEBUG 在当前宿主配置下会被过滤，ERROR 带演示堆栈。导出 ZIP 仅保存到应用私有缓存，不自动上传或分享；返回首页不关闭应用级日志系统。
