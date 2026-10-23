package com.ttxz.base.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/** DummyJSON 商品接口；skip 为偏移量，不是页码。只请求列表使用的字段。 */
interface ProductApi {
    @GET("products")
    suspend fun getProducts(
        @Query("limit") limit: Int,
        @Query("skip") skip: Int,
        @Query("delay") delayMillis: Int = 0,
        @Query("select") fields: String = "id,title,price,thumbnail",
    ): ProductPageDto
}
