# 图片加载

common 提供 BaseApplication、ImageLoadingConfig 和 createImageLoader，基于 Coil 3；页面直接使用 AsyncImage。

## 接入

消费者声明 implementation(project(":common")) 即可，common 通过 api 导出 Coil Compose，消费者无需重复声明 Coil 依赖。
网络组件通过 common 的运行时依赖提供，宿主需要 INTERNET 权限。common 因此带入图片及 Compose 依赖，这是统一底座的明确取舍；common 不反向依赖 ui、network 或 app。

宿主 Application 继承 common.application.BaseApplication，并在 Manifest 注册。使用 Hilt 时，@HiltAndroidApp 保留在宿主子类。
真实示例见 [AppApplication](../app/src/main/java/com/ttxz/base/AppApplication.kt)。基类实现 SingletonImageLoader.Factory，Coil 首次获取共享加载器时按默认配置创建。
页面不要直接调用 newImageLoader，也不要反复创建加载器。日志及其他业务初始化仍留在宿主 onCreate，必须调用 super.onCreate()。

默认配置接入：

```kotlin
@HiltAndroidApp
class AppApplication : BaseApplication()
```

覆盖配置的拟议示例（当前宿主沿用默认值）：

```kotlin
override fun imageLoadingConfig() = ImageLoadingConfig(
    memoryCachePercent = 0.15,
    diskCacheMaxBytes = 64L * 1024 * 1024,
)
```

配置仅在创建加载器时读取，不提供运行时切换。新接入方式替代原 ui.image 工厂入口：包名改为 common.image，移除宿主重复的 Coil 依赖声明。
已有其他 Application 继承要求时，可不继承 BaseApplication，继续实现 Coil 工厂并调用 common.image.createImageLoader；不强制替换第三方基类。
默认内存缓存上限 20%，磁盘上限 128 MiB，目录 cacheDir/coil_images；同一目录只能由一个活动加载器使用。
缓存可能被淘汰或系统清理，不是永久离线存储。容量参数在构造配置时校验。
createImageLoader 的 configure Lambda 可调整 Coil 原生 Builder 配置，调用方负责有效性。
默认使用独立的 Coil OkHttp 网络组件，不继承业务 Token 或 BODY 日志。
Coil 3 默认不遵循 Cache-Control，本次未引入 cache-control 扩展。

页面通过 AsyncImage(model, contentDescription, modifier = ...) 展示图片，按场景传入 placeholder、error、fallback 和 contentScale。
占位图使用 vector 或位图，不设置公共默认图。请求失败、取消、缓存策略遵循 Coil，不额外自动重试、Toast 或转为 AppResult。

## 示例、学习与验证

首页“图片加载示例”：网络 PNG、本地 Uri、无效域名、null 地址。蓝灰色加载占位、红色失败、灰色空地址均为示例资源。
本地选择仅使用临时 Uri 授权，不保存长期读取权限；恢复后可能需要重新选择。
网络样例为外部服务，不保证可用性。GIF、SVG、视频解码和认证尚未接入。

点击相同来源会显式重建图片请求，不修改 URL 或缓存键，允许失败后重试；结果栏由加载回调更新，不在点击时清空已完成结果。
演示失败时显示异常类型和消息，并以 ImageDemo 标签写入 Logcat，仅用于示例诊断。
Debug 宿主的 debug_network_security_config 对 raw.githubusercontent.com 信任系统与用户 CA，便于使用已安装证书的 HTTPS 抓包代理；与现有文章示例规则一致，仅限该域名，不信任任意证书。该配置只存在于 src/debug，Release 使用系统默认校验。

练习：网络加载成功后切换空地址，再加载原图，查看 MEMORY / DISK / NETWORK 来源。
结束进程后再进入可以观察磁盘缓存，前提是缓存未清理且请求配置一致。
验收：能显示网络和本地图片，识别失败与空地址，能说明加载器和展示组件各自职责。
编译使用 :common:assembleDebug :app:assembleDebug；实际显示、Uri 授权、缓存来源需设备验证。

2026-10-22 在 DAYU_A6 上复现 HTTPS 证书链不受信任。补充 Debug 演示域名的用户 CA 信任后，网络 PNG 显示成功（NETWORK），再次点击同一按钮返回 MEMORY_CACHE，截图确认图片显示。Debug 构建与 Release 合并 Manifest 检查通过，Release 未包含 networkSecurityConfig。此记录不覆盖本地 Uri、磁盘缓存及其他设备。

官方说明：https://coil-kt.github.io/coil/image_loaders/ 和 https://coil-kt.github.io/coil/compose/

版本说明：使用 Coil 3.6.3，项目 Kotlin 与 Compose 编译插件统一为 2.4.21。原 Kotlin 2.2 编译器无法读取其依赖的 kotlin-stdlib 2.4.10 元数据；本次升级编译器解决，不跳过元数据检查。

2026-10-22 迁移至 common 后，AppApplication 继承 BaseApplication 且未覆盖默认配置。Debug/Release（含 Hilt、R8）、82 项 JVM 单测、lint 通过；DAYU_A6 验证图片显示、DISK 和 MEMORY_CACHE 来源。本地 Uri 未重新验收，自定义配置覆盖示例未做专项设备测试。
