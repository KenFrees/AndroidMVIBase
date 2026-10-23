package com.ttxz.base.data.remote

import androidx.annotation.Keep

/** 商品列表字段；价格不预设币种。Moshi 反射解析所需的名称与构造器在 Release 中保留。 */
@Keep
data class ProductDto(
    val id: Int,
    val title: String,
    val price: Double,
    val thumbnail: String? = null,
)

/** DummyJSON 分页响应；total 是当前结果总数，skip/limit 为本次分页信息。 */
@Keep
data class ProductPageDto(
    val products: List<ProductDto>,
    val total: Int,
    val skip: Int,
    val limit: Int,
)
