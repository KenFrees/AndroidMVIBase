package com.ttxz.base.demo.post

/** 页面发送给 ViewModel 的用户操作。 */
sealed interface PostDemoAction {
    data class LoadPost(val id: Int) : PostDemoAction
}
