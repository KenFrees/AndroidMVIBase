package com.ttxz.base.data.remote

/** JSONPlaceholder 的文章数据；仅用于 app 的网络调用演示。 */
data class PostDto(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String,
)
