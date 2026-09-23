package com.ttxz.base.demo.post

import com.ttxz.base.common.result.AppResult
/** 演示页持续显示的状态；与一次请求的 [AppResult] 分开。 */
sealed interface PostDemoUiState {
    data object Idle : PostDemoUiState
    data object Loading : PostDemoUiState
    data class Content(val title: String, val body: String) : PostDemoUiState
    data class Error(val httpCode: Int?, val isCertificateError: Boolean) : PostDemoUiState
}
