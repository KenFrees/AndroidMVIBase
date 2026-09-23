package com.ttxz.base.demo.post

import com.ttxz.base.common.result.AppResult
import com.ttxz.base.data.remote.PostApi
import com.ttxz.base.data.remote.PostDto
import com.ttxz.base.network.safeApiCall
import javax.inject.Inject

/** 演示数据层：调用 Retrofit API，并保留 HTTP / IO 错误与协程取消语义。 */
class PostRepository @Inject constructor(private val api: PostApi) {
    suspend fun getPost(id: Int): AppResult<PostDto> = safeApiCall { api.getPost(id) }
}
