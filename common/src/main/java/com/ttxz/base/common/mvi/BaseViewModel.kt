package com.ttxz.base.common.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 统一轻量 MVI 的状态容器和临时效果流，业务处理由子类提供。
 *
 * 不自动排队 Action、发起请求或切换线程。State 应为不可变数据；相等状态不会重复通知。
 * 状态仅保留于此 ViewModel 的内存中，不提供进程恢复。Effect 的交付边界见 [sendEffect]。
 * @param initialState 业务页面提供的初始状态。
 */
abstract class BaseViewModel<Action : UiAction, State : UiState, Effect : UiEffect>(
    initialState: State,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState)

    /** 只读页面状态；新订阅者获得最新值，慢订阅者可能跳过中间状态。 */
    val uiState: StateFlow<State> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<Effect>(replay = 0, extraBufferCapacity = 1)

    /** 临时效果广播；多个订阅者分别接收，不提供全局只消费一次的保证。 */
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    /** 当前状态的瞬时快照；基于旧状态计算新值时使用 Lambda 版本的 [updateUiState]。 */
    protected val currentState: State
        get() = _uiState.value

    /** 接收页面操作；调用线程、重复请求、并发和取消策略由子类约定并处理。 */
    abstract fun onAction(action: Action)

    /** 替换整个状态；可并发调用，但并发替换以最后写入为准，不合并字段。 */
    protected fun updateUiState(newState: State) {
        _uiState.value = newState
    }

    /**
     * 基于最新状态原子更新，避免并发读取后写回造成的更新丢失。
     *
     * [reducer] 在竞争时可能执行多次，必须只计算新状态，不修改旧状态或执行外部副作用。
     * 此操作不保证请求完成顺序；过期结果仍由业务 ViewModel 处理。
     */
    protected fun updateUiState(reducer: State.() -> State) {
        _uiState.update { state -> state.reducer() }
    }

    /**
     * 在调用者协程中发送临时效果；有订阅者且一个额外缓冲位已满时挂起等待。
     *
     * 无订阅者时直接丢弃，新订阅者不接收历史效果。返回不代表 UI 已执行效果，
     * 订阅取消也可能导致效果未处理。等待可取消，取消异常继续传播；本方法不另建协程。
     * 必须保留的业务结果应放入 [uiState]，由页面呈现或另行设计确认机制。
     */
    protected suspend fun sendEffect(effect: Effect) {
        _effects.emit(effect)
    }
}
