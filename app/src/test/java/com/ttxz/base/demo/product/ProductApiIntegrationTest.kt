package com.ttxz.base.demo.product

import androidx.paging.PagingSource
import com.ttxz.base.data.remote.ProductApi
import com.ttxz.base.data.remote.ProductApiModule
import com.ttxz.base.data.remote.ProductDto
import com.ttxz.base.network.di.NetworkModule
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/** 使用真实 API 绑定、HTTP 和 Moshi 转换器，但在拦截器中将请求送到本机服务器。 */
class ProductApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: ProductApi
    private val upstreamUrl = AtomicReference<HttpUrl>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = NetworkModule.provideOkHttpClient().newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                upstreamUrl.set(request.url)
                val local = server.url("/").newBuilder()
                    .encodedPath(request.url.encodedPath)
                    .encodedQuery(request.url.encodedQuery)
                    .build()
                chain.proceed(request.newBuilder().url(local).build())
            }
            .build()
        val retrofit = NetworkModule.provideRetrofit(client, NetworkModule.provideMoshi())
        api = ProductApiModule.provideProductApi(retrofit)
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test
    fun productBindingRetainsSharedConverterAndSendsPaginationQueries() = runBlocking {
        server.enqueue(MockResponse(body = """
            {
              "products": [
                {"id": 11, "title": "Product A", "price": 19.50, "thumbnail": "https://cdn.dummyjson.com/a.webp", "unused": true},
                {"id": 12, "title": "Product B", "price": 8}
              ],
              "total": 17, "skip": 10, "limit": 5
            }
        """.trimIndent()))
        val result = ProductPagingSource(api, 1500).load(
            PagingSource.LoadParams.Append(key = 10, loadSize = 5, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page<Int, ProductDto>

        assertEquals("dummyjson.com", upstreamUrl.get().host)
        assertEquals("/products", upstreamUrl.get().encodedPath)
        assertEquals("5", upstreamUrl.get().queryParameter("limit"))
        assertEquals("10", upstreamUrl.get().queryParameter("skip"))
        assertEquals("1500", upstreamUrl.get().queryParameter("delay"))
        assertEquals("id,title,price,thumbnail", upstreamUrl.get().queryParameter("select"))
        assertEquals(19.50, result.data.first().price, 0.0)
        assertEquals("https://cdn.dummyjson.com/a.webp", result.data.first().thumbnail)
        assertNull(result.data.last().thumbnail)
        assertEquals(12, result.nextKey)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun zeroLimitFromActualEmptyPageJsonEndsPagination() = runBlocking {
        server.enqueue(MockResponse(body = """{"products":[],"total":180,"skip":190,"limit":0}"""))
        val result = ProductPagingSource(api).load(
            PagingSource.LoadParams.Append(key = 190, loadSize = 10, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page<Int, ProductDto>
        assertTrue(result.data.isEmpty())
        assertNull(result.nextKey)
        assertEquals("190", upstreamUrl.get().queryParameter("skip"))
    }

    @Test
    fun actualHttpFailureIsExposedAsRetryablePagingError() = runBlocking {
        server.enqueue(MockResponse(code = 503, body = """{"message":"test unavailable"}"""))
        val result = ProductPagingSource(api).load(
            PagingSource.LoadParams.Refresh<Int>(null, 10, false),
        ) as PagingSource.LoadResult.Error<Int, ProductDto>
        assertTrue(result.throwable is HttpException)
        assertEquals(503, (result.throwable as HttpException).code())
    }
}
