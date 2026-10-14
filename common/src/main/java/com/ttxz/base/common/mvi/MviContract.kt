package com.ttxz.base.common.mvi

/** 页面操作的角色标记；操作的执行顺序、去重和取消策略由具体 ViewModel 决定。 */
interface UiAction

/** 页面状态的角色标记；实现应使用不可变数据，本接口不提供磁盘持久化或进程恢复。 */
interface UiState

/** 允许遗漏、不向新订阅者重放的临时 UI 效果标记；必须保留的结果应表达为 UiState。 */
interface UiEffect
