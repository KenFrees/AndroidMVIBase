package com.ttxz.base.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit

/** 复用 network 模块提供的 Retrofit，绑定演示业务 API。 */
@Module
@InstallIn(SingletonComponent::class)
object PostApiModule {
    @Provides
    fun providePostApi(retrofit: Retrofit): PostApi = retrofit.create(PostApi::class.java)
}
