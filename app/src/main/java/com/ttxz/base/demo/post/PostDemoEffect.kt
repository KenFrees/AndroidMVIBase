package com.ttxz.base.demo.post

import com.ttxz.base.common.mvi.UiEffect

/** 仅通知当前页面一次的请求结果，用于显示 Toast，不作为可重放状态。 */
sealed interface PostDemoEffect : UiEffect {
    data object Success : PostDemoEffect
    data class Failure(val httpCode: Int?, val isCertificateError: Boolean) : PostDemoEffect
}
