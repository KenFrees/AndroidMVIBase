# 图片加载

common 提供 BaseApplication、ImageLoadingConfig 和 createImageLoader，基于 Coil 3；页面直接使用 AsyncImage。源码：[ImageLoading.kt](../src/main/java/com/ttxz/base/common/image/ImageLoading.kt)、[BaseApplication.kt](../src/main/java/com/ttxz/base/common/application/BaseApplication.kt)。

本页导航：[支持范围](#支持哪些能力) · [最小调用](#最小调用) · [宿主接入](#接入) · [使用示例](#使用示例) · [运行演示与验证](#示例学习与验证)。返回 [common 能力目录](README.md)。

## 支持哪些能力

“已接入”表示当前依赖与配置已提供该能力；具体设备验证范围见文末，不表示表内所有场景均已实测。

| 能力 | 当前支持方式 | 使用入口与边界 |
| --- | --- | --- |
| 全局共享加载器 | common 已封装 | 宿主继承 BaseApplication；默认配置可覆盖，见 [接入](#接入) |
| 网络静态图片 | 已接入 Coil OkHttp 网络组件 | AsyncImage 传 HTTPS URL；宿主声明 INTERNET 权限，见 [网络图片](#网络图片与占位图)；实际格式解码受设备支持范围影响 |
| 本地图片 | 直接使用 Coil 的 Uri 加载能力 | 传选择器返回的 Uri；调用方负责读取授权，见 [本地图片](#选择并加载本地图片) |
| 内存与磁盘缓存 | common 已配置共享缓存 | 内存上限默认 20%，磁盘上限默认 128 MiB；无需页面自行实现缓存，命中不保证永久有效 |
| 加载中、失败、空地址图片 | 直接使用 Coil Compose 参数 | placeholder / error / fallback 由页面分别传入；没有统一默认占位图，见 [网络图片](#网络图片与占位图) |
| 等比显示、填满裁切、圆形头像 | 使用 Compose 布局与裁切 | ContentScale.Fit / Crop、Modifier.clip；见 [圆形头像](#圆形头像与显示裁切)，不改变原文件 |
| 加载回调与缓存来源 | 直接使用 Coil 回调 | onLoading / onSuccess / onError；成功结果的 dataSource 可观察来源，见 [回调与缓存观察](#加载回调与缓存观察) |
| 原生加载器配置扩展 | common 提供 createImageLoader 的 configure 参数 | 在宿主加载器工厂中调整 Coil Builder；页面不创建加载器，配置有效性由调用方负责 |

当前未集成的能力：GIF 动画、SVG、视频帧需要额外解码组件；业务 Token/认证、缓存随账号隔离或退出清理策略尚未统一封装。当前网络缓存未接入 Cache-Control 扩展。图片上传、JPEG 文件压缩、持久离线图库不在这个加载封装的职责内。

## 最小调用

调用方依赖 `:common`，并按下节完成 Application 和网络权限配置后，在 Compose 页面中调用：

```kotlin
import coil3.compose.AsyncImage

AsyncImage(
    model = imageUrl,
    contentDescription = description,
)
```

这是参考片段，imageUrl 和 description 由页面提供；需要固定显示尺寸或区分加载、失败、空地址时，见下方 [常见用法](#使用示例)。真实演示见 [ImageDemoScreen.kt](../../app/src/main/java/com/ttxz/base/demo/image/ImageDemoScreen.kt)：首页进入“图片加载示例”，可切换网络图、本地图片、失败和空地址。

## 接入

消费者声明 implementation(project(":common")) 即可，common 通过 api 导出 Coil Compose，消费者无需重复声明 Coil 依赖。
网络组件通过 common 的运行时依赖提供，宿主需要 INTERNET 权限。common 因此带入图片及 Compose 依赖，这是统一底座的明确取舍；common 不反向依赖 ui、network 或 app。

宿主 Application 继承 common.application.BaseApplication，并在 Manifest 注册。使用 Hilt 时，@HiltAndroidApp 保留在宿主子类。
真实示例见 [AppApplication](../../app/src/main/java/com/ttxz/base/AppApplication.kt)。基类实现 SingletonImageLoader.Factory，Coil 首次获取共享加载器时按默认配置创建。
页面不要直接调用 newImageLoader，也不要反复创建加载器。日志及其他业务初始化仍留在宿主 onCreate，必须调用 super.onCreate()。

默认配置接入：

```kotlin
import com.ttxz.base.common.application.BaseApplication
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AppApplication : BaseApplication()
```

覆盖配置的拟议示例（当前宿主沿用默认值）：

```kotlin
import com.ttxz.base.common.application.BaseApplication
import com.ttxz.base.common.image.ImageLoadingConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AppApplication : BaseApplication() {
    override fun imageLoadingConfig() = ImageLoadingConfig(
        memoryCachePercent = 0.15,
        diskCacheMaxBytes = 64L * 1024 * 1024,
    )
}
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

## 使用示例

以下为接入方可参考的页面写法，未新增公共组件。先完成上述宿主接入；Compose、Activity Result 等宿主依赖仍需按各示例配置。真实运行示例位于 [ImageDemoScreen.kt](../../app/src/main/java/com/ttxz/base/demo/image/ImageDemoScreen.kt)。

### 网络图片与占位图

```kotlin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ttxz.base.R

@Composable
fun RemoteImageExample(imageUrl: String?, description: String?) {
    AsyncImage(
        model = imageUrl?.takeIf { it.isNotBlank() },
        contentDescription = description,
        placeholder = painterResource(R.drawable.image_demo_placeholder),
        error = painterResource(R.drawable.image_demo_error),
        fallback = painterResource(R.drawable.image_demo_empty),
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentScale = ContentScale.Fit,
    )
}
```

示例 R 和三个 drawable 是当前 app 的资源，接入业务工程时替换为自己的包名和资源。placeholder 在加载时使用，error 在失败时使用，fallback 在 model 为 null 时使用。示例主动将空白 URL 转成 null，避免将空字符串误当成有效地址。description 由调用方提供，业务文案使用字符串资源；纯装饰图可传 null。

### 选择并加载本地图片

```kotlin
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun LocalImageExample(buttonText: String, description: String?) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selectedUri = uri
    }
    Column {
        Button(onClick = { picker.launch("image/*") }) { Text(buttonText) }
        AsyncImage(
            model = selectedUri,
            contentDescription = description,
            modifier = Modifier.size(160.dp),
        )
    }
}
```

这里使用系统选择器及其返回的临时读取授权，不保存图片或长期授权，示例状态也不跨页面重建恢复。若业务需要下次启动继续读取，应另行设计持久授权或复制到应用目录；不能仅保存 Uri 字符串就认为永久可读。

### 圆形头像与显示裁切

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun AvatarExample(imageUrl: String?, description: String?) {
    AsyncImage(
        model = imageUrl?.takeIf { it.isNotBlank() },
        contentDescription = description,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(64.dp).clip(CircleShape),
    )
}
```

ContentScale.Fit 等比完整显示，容器比例不同时可能留白；Crop 等比填满容器，超出部分被裁去。clip(CircleShape) 只改变显示轮廓；圆角矩形可改用 RoundedCornerShape。上述操作不会生成裁切后的文件，也不执行上传压缩。

### 加载回调与缓存观察

需要展示加载状态或诊断失败时，向 AsyncImage 增加 onLoading / onSuccess / onError；页面决定如何更新状态或展示反馈。可直接参考 [运行示例中的回调](../../app/src/main/java/com/ttxz/base/demo/image/ImageDemoScreen.kt)。

- onLoading：进入加载状态。
- onSuccess：从回调参数的 result.dataSource 读取 MEMORY_CACHE、DISK、NETWORK 等来源；不是每次重新显示都访问网络。
- onError：从 result.throwable 获取失败原因；业务自行决定是否提示、如何重试，不自动转为 AppResult。

缓存键和请求尺寸等因素会影响命中。切换到空地址后再请求同一 URL，可观察内存缓存；结束进程后再进入可观察磁盘缓存，前提是缓存未清理且请求配置一致。不通过给 URL 拼随机参数来验证缓存。

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

Coil 原生行为参考官方说明：[加载器与缓存](https://coil-kt.github.io/coil/image_loaders/)、[Compose 参数与裁切示例](https://coil-kt.github.io/coil/compose/)、[网络与 Cache-Control](https://coil-kt.github.io/coil/network/)。本项目已接入哪些能力以上表和实际 Gradle/源码为准。

版本说明：使用 Coil 3.6.3，项目 Kotlin 与 Compose 编译插件统一为 2.4.21。原 Kotlin 2.2 编译器无法读取其依赖的 kotlin-stdlib 2.4.10 元数据；本次升级编译器解决，不跳过元数据检查。

2026-10-22 迁移至 common 后，AppApplication 继承 BaseApplication 且未覆盖默认配置。Debug/Release（含 Hilt、R8）、82 项 JVM 单测、lint 通过；DAYU_A6 验证图片显示、DISK 和 MEMORY_CACHE 来源。本地 Uri 未重新验收，自定义配置覆盖示例未做专项设备测试。
