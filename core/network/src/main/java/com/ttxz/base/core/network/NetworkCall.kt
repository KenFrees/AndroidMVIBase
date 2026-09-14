package com.ttxz.base.core.network

import com.ttxz.base.core.common.result.AppResult
import retrofit2.HttpException
import java.io.IOException

/**
 * 统一把 Retrofit 调用转成 [AppResult]，业务层不要直接裸调 try-catch。
 */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> {
    return try {
        AppResult.Success(block())
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
