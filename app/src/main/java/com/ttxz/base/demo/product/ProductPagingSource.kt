package com.ttxz.base.demo.product

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ttxz.base.data.remote.ProductApi
import com.ttxz.base.data.remote.ProductDto
import kotlinx.coroutines.CancellationException

/**
 * 将 DummyJSON 的 limit/skip 响应转换为单向 Paging 数据。
 *
 * key 是偏移量，下一偏移量按实际返回条数计算；达到 total 或空页时停止追加。
 * 刷新从第一页开始；异常交给 Paging 展示重试，协程取消继续传播。
 * @param delayMillis 服务端演示延迟，单位毫秒，范围 0..5000。
 */
class ProductPagingSource(
    private val api: ProductApi,
    private val delayMillis: Int = 0,
) : PagingSource<Int, ProductDto>() {
    init {
        require(delayMillis in 0..5000)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ProductDto> = try {
        val offset = params.key ?: 0
        val page = api.getProducts(limit = params.loadSize, skip = offset, delayMillis = delayMillis)
        // DummyJSON 响应的 limit 为实际返回条数；合法空页会返回 0。
        val validLimit = page.limit > 0 || (page.limit == 0 && page.products.isEmpty())
        check(page.skip == offset && page.total >= 0 && validLimit) {
            "Unexpected DummyJSON pagination metadata"
        }
        val nextOffset = offset + page.products.size
        LoadResult.Page(
            data = page.products,
            prevKey = null,
            nextKey = if (page.products.isEmpty() || nextOffset >= page.total) null else nextOffset,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    /** 下拉刷新总是重新读取第一批商品，不从旧的滚动锚点请求。 */
    override fun getRefreshKey(state: PagingState<Int, ProductDto>): Int? = null
}
