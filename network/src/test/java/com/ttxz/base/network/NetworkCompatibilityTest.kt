package com.ttxz.base.network

import com.squareup.moshi.JsonDataException
import com.ttxz.base.common.result.AppResult
import com.ttxz.base.network.di.NetworkModule
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET

/** 验证真实 HTTP、转换器与 safeApiCall 的兼容性；仅访问本地回环服务器。 */
class NetworkCompatibilityTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NetworkModule.provideOkHttpClient().newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test
    fun successDecodesJson() = runBlocking {
        server.enqueue(MockResponse(body = """{"id":1,"title":"ok"}"""))

        val result = safeApiCall { createApi().item() }

        assertTrue(result is AppResult.Success)
        assertEquals(Item(1, "ok"), (result as AppResult.Success).data)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun httpErrorRetainsStatusAndException() = runBlocking {
        server.enqueue(MockResponse(code = 404, body = "{}"))

        val result = safeApiCall { createApi().item() }

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertEquals(404, error.code)
        assertTrue(error.throwable is HttpException)
    }

    @Test
    fun invalidFieldTypeReturnsParsingError() = runBlocking {
        server.enqueue(MockResponse(body = """{"id":"not-an-integer","title":"ok"}"""))

        val result = safeApiCall { createApi().item() }

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertNull(error.code)
        assertTrue(error.throwable is JsonDataException)
    }

    @Test
    fun slowBodyReturnsIoError() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"id":1,"title":"ok"}""")
                .bodyDelay(1, TimeUnit.SECONDS)
                .build(),
        )
        val shortTimeoutClient = client.newBuilder()
            .readTimeout(150, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        val result = safeApiCall { createApi(shortTimeoutClient).item() }

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertNull(error.code)
        assertTrue(error.throwable is IOException)
    }

    private fun createApi(httpClient: OkHttpClient = client): TestApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build()
            .create(TestApi::class.java)

    interface TestApi {
        @GET("item")
        suspend fun item(): Item
    }

    data class Item(val id: Int, val title: String)
}
