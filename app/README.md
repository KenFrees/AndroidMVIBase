# app：示例宿主

## 职责与当前能力

app 用于启动、展示和验证基础模块。当前显示 Compose 欢迎页面和 Loading 弹窗演示，尚未实现登录、网络列表页或 MVI。

应用 ID / 命名空间为 `com.ttxz.base`，minSdk 26；依赖 common、network 和 ui，并配置 Compose、Hilt、KSP。

| 文件 | 作用 |
| --- | --- |
| [BaseApp.kt](src/main/java/com/ttxz/base/BaseApp.kt) | 带 `@HiltAndroidApp` 的 Application，Hilt 宿主入口 |
| [MainActivity.kt](src/main/java/com/ttxz/base/MainActivity.kt) | `@AndroidEntryPoint` Activity，通过 setContent 展示主题、Greeting 和 Loading 按钮演示 |
| [ui/theme](src/main/java/com/ttxz/base/ui/theme) | Compose 颜色、字体与主题 |
| [AndroidManifest.xml](src/main/AndroidManifest.xml) | 注册 Application / 启动 Activity，声明 INTERNET 权限 |
| [build.gradle.kts](build.gradle.kts) | 模块依赖、Compose/Hilt/KSP 与构建配置 |

## 如何运行与使用

Android Studio Sync 后选择 app，在 API 26 及以上设备运行。欢迎页显示 `Hello AndroidMVIBase!`；点击“展示 Loading”弹出加载框，两秒后自动关闭。该演示不发起网络请求。

仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

默认 Debug APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。编译通过后仍需安装运行来确认设备行为。

后续新增示例时：

1. 先确定本步要演示的基础能力，阅读对应模块 README。
2. 复用宿主的 Hilt 配置、主题和网络依赖；不要在 Activity 或 Composable 中创建 Retrofit/OkHttp。
3. 页面通过 ViewModel / Repository 使用基础模块。当前这些示例类还没有创建，需要在对应学习阶段设计。
4. 将示例保留为可阅读的用法说明，同时更新本 README 的文件入口和验证步骤。

调用 [common 的结果类型](../common/README.md) 和 [network 的请求封装](../network/README.md) 时，遵守各自语义与当前限制。

## 学习顺序

1. 看 Manifest → BaseApp：系统如何找到 Application，Hilt 从哪里进入？
2. 看 MainActivity → setContent → Theme → Scaffold → Greeting：Compose 如何组织界面？
3. 看 Gradle 依赖：为什么 app 同时依赖 common 和 network？
4. 看按钮如何改变本地状态以控制 `ui` 的 [LoadingDialog](../ui/components/loading-dialog.md)；以后再学习 ViewModel、StateFlow 和生命周期收集，当前没有这条状态链路。

练习：找到欢迎文字的参数来源，解释改变 name 为什么会影响 Text；再找到宿主的 INTERNET 权限，但说明“有权限”为什么不等于“页面已经请求网络”。

本步通过标准：能追踪应用启动到欢迎页面的调用关系，并分清宿主和基础模块的职责。

## 验证与当前限制

- 当前测试目录是模板示例，不覆盖业务网络、MVI 或真实用户流程。
- Navigation / ViewModel 等依赖已存在，但没有完整示例实现。
- 当前 Release 关闭优化，尚未完成正式发布验证。
- network 仍有取消传播、日志、环境地址等待完善项；详见该模块 README。
- 已完成的 Debug 构建记录见 [项目 README](../README.md)，后续修改须提供本次验证结果。

新增学习示例或变更入口时，更新本说明；不要把业务 API 和领域类型塞入公共基础模块。
