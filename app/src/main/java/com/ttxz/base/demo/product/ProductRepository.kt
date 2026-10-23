package com.ttxz.base.demo.product

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.ttxz.base.data.remote.ProductApi
import com.ttxz.base.data.remote.ProductDto
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** 商品分页数据入口；每代数据均创建新的 PagingSource，不在页面直接持有 API。 */
class ProductRepository @Inject constructor(private val api: ProductApi) {
    /** 创建一代分页流；delayMillis 仅用于观察服务端慢响应，实际缓存作用域由 ViewModel 提供。 */
    fun products(delayMillis: Int): Flow<PagingData<ProductDto>> = Pager(
        config = PagingConfig(
            pageSize = 10,
            initialLoadSize = 10,
            prefetchDistance = 2,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { ProductPagingSource(api, delayMillis) },
    ).flow
}
