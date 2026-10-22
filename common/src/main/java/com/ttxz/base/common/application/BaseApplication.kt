package com.ttxz.base.common.application

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.ttxz.base.common.image.ImageLoadingConfig
import com.ttxz.base.common.image.createImageLoader

/**
 * 提供共享图片加载器默认配置的 Application 基类。
 *
 * 宿主在 Manifest 中注册自己的子类；使用 Hilt 时，将 HiltAndroidApp 注解放在宿主子类。
 * Coil 首次获取共享加载器时才创建实例，不在 onCreate 中主动加载图片。
 * 业务、日志、认证等初始化由宿主负责，不在此基类中自动执行。
 */
abstract class BaseApplication : Application(), SingletonImageLoader.Factory {

    /**
     * 返回创建共享加载器时使用的配置，默认采用 ImageLoadingConfig 的缓存容量。
     *
     * 可在宿主覆盖；此配置仅在创建加载器时读取，不提供运行时动态更新。
     * 配置中只保存应用级数据，不引用 Activity 或页面状态。
     */
    protected open fun imageLoadingConfig(): ImageLoadingConfig = ImageLoadingConfig()

    /**
     * 由 Coil 的共享实例机制调用，默认使用 [imageLoadingConfig] 创建加载器。
     *
     * 不要在页面直接调用本方法，它不会自行缓存返回实例。
     * 需要调整 Coil 原生 Builder 配置时，可覆盖本方法并复用 createImageLoader。
     */
    override fun newImageLoader(context: Context): ImageLoader =
        createImageLoader(context, imageLoadingConfig())
}
