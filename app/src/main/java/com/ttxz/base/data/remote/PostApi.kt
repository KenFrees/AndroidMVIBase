package com.ttxz.base.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

/** 声明演示服务的 GET /posts/{id}，由现有 Retrofit 实例创建。 */
interface PostApi {
    @GET("posts/{id}")
    suspend fun getPost(@Path("id") id: Int): PostDto
}
