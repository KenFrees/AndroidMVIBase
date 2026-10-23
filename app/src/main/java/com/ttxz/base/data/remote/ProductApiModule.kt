package com.ttxz.base.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/** 只替换商品服务地址，继续复用 network 提供的 OkHttp、转换器及超时配置。 */
@Module
@InstallIn(SingletonComponent::class)
object ProductApiModule {
    @Provides
    @Singleton
    fun provideProductApi(retrofit: Retrofit): ProductApi =
        retrofit.newBuilder()
            .baseUrl("https://dummyjson.com/")
            .build()
            .create(ProductApi::class.java)
}
