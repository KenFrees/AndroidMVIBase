package com.ttxz.base.demo.product

import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.ttxz.base.common.mvi.BaseViewModel
import com.ttxz.base.common.mvi.UiAction
import com.ttxz.base.common.mvi.UiEffect
import com.ttxz.base.common.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** 演示配置的操作；分页的刷新/重试直接由 PagingList 委托给 Paging。 */
sealed interface ProductListAction : UiAction {
    data class SetSlowLoading(val enabled: Boolean) : ProductListAction
}

/** 只保存页面配置；条目与加载状态由 Paging 持有，避免重复维护。 */
data class ProductListUiState(val slowLoading: Boolean = false) : UiState

/** 配置变化时切换数据流，取消旧收集；此页面不发送临时效果。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProductListViewModel @Inject constructor(
    repository: ProductRepository,
) : BaseViewModel<ProductListAction, ProductListUiState, UiEffect>(ProductListUiState()) {

    /** 页面范围内缓存分页结果，慢速设置变化时取消旧流并从第一页开始。 */
    val products = uiState
        .map { it.slowLoading }
        .distinctUntilChanged()
        .flatMapLatest { slow -> repository.products(delayMillis = if (slow) 1500 else 0) }
        .cachedIn(viewModelScope)

    override fun onAction(action: ProductListAction) {
        when (action) {
            is ProductListAction.SetSlowLoading -> updateUiState { copy(slowLoading = action.enabled) }
        }
    }
}
