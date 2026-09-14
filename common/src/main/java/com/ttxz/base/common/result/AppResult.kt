package com.ttxz.base.common.result

/**
 * 统一结果封装：网络/业务层对外暴露此类型，避免到处 try-catch。
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val code: Int? = null,
    ) : AppResult<Nothing>

    data object Loading : AppResult<Nothing>
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (AppResult.Error) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(this)
    return this
}
