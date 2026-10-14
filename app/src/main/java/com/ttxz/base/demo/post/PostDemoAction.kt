package com.ttxz.base.demo.post

import com.ttxz.base.common.mvi.UiAction

/** 页面发送给 ViewModel 的用户操作。 */
sealed interface PostDemoAction : UiAction {
    data class LoadPost(val id: Int) : PostDemoAction
}
