package com.ttxz.base.network

import com.ttxz.base.common.result.AppResult
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/**
 * 执行 [block] 并将返回值包装为 [AppResult.Success]，将 HTTP、IO 和其他异常包装为 [AppResult.Error]。
 * 不切换线程或产生 Loading；协程取消继续抛出，由调用方管理请求生命周期。
 */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> {
    return try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        AppResult.Error(
            message = e.message() ?: "HTTP ${e.code()}",
            throwable = e,
            code = e.code(),
        )
    } catch (e: IOException) {
        AppResult.Error(
            message = e.message ?: "Network error",
            throwable = e,
        )
    } catch (e: Exception) {
        AppResult.Error(
            message = e.message ?: "Unknown error",
            throwable = e,
        )
    }
}
