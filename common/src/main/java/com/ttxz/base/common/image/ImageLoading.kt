package com.ttxz.base.common.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

/** 图片缓存配置；容量是上限，缓存可能被淘汰或由系统清理，不作为永久存储。 */
data class ImageLoadingConfig(
    /** 图片内存缓存占应用可用内存的比例，范围 (0, 1]。 */
    val memoryCachePercent: Double = 0.20,
    /** 磁盘缓存上限，单位字节。 */
    val diskCacheMaxBytes: Long = 128L * 1024 * 1024,
) {
    init {
        require(memoryCachePercent > 0 && memoryCachePercent <= 1)
        require(diskCacheMaxBytes > 0)
    }
}

/**
 * 创建图片加载器，不注册全局实例；宿主通过 SingletonImageLoader.Factory 创建并共享。
 *
 * 使用 applicationContext，缓存目录固定为 cacheDir/coil_images。此目录只能由一个活动加载器使用。
 * 默认使用 Coil 的 OkHttp 网络组件，不继承业务 API 的认证或 BODY 日志配置。
 * [configure] 可调整 Coil 原生配置；调用者负责配置有效性，不应在页面中反复创建加载器。
 * 请求失败、取消和缓存命中遵循 Coil 契约，不添加重试、Toast 或业务错误映射。
 */
fun createImageLoader(
    context: Context,
    config: ImageLoadingConfig = ImageLoadingConfig(),
    configure: ImageLoader.Builder.() -> Unit = {},
): ImageLoader {
    val appContext = context.applicationContext
    return ImageLoader.Builder(appContext)
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(appContext, config.memoryCachePercent).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("coil_images").toOkioPath())
                .maxSizeBytes(config.diskCacheMaxBytes)
                .build()
        }
        .apply(configure)
        .build()
}
