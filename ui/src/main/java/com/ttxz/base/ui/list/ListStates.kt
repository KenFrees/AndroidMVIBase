package com.ttxz.base.ui.list

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState

/** 列表主体的展示状态；普通列表由宿主提供，PagingList 根据 Paging 状态计算。 */
sealed interface ListContentState {
    /** 没有可显示的数据，首次请求正在进行。 */
    data object Loading : ListContentState
    /** 请求已结束且没有数据；未开始请求时不要提前使用此状态。 */
    data object Empty : ListContentState
    /** 没有可显示的数据且请求失败；默认文案不直接展示底层异常。 */
    data class Error(val cause: Throwable) : ListContentState
    /** 展示已有条目；刷新或追加失败不应替换成整页错误。 */
    data object Content : ListContentState
}

/** 列表尾部的展示状态，不自行请求下一页。 */
sealed interface LoadMoreState {
    /** 当前不需要显示尾部。 */
    data object Idle : LoadMoreState
    /** 下一页正在加载。 */
    data object Loading : LoadMoreState
    /** 下一页失败，允许重试。 */
    data class Error(val cause: Throwable) : LoadMoreState
    /** 已达到数据源声明的分页末尾。 */
    data object End : LoadMoreState
}

internal data class PagingListPresentation(
    val content: ListContentState,
    val isRefreshing: Boolean,
    val refreshError: Throwable?,
    val append: LoadMoreState,
)

// 只推导展示，不改写分页数据或发送请求；占位条目不计入已有数据。
internal fun resolvePagingListPresentation(
    loadedItemCount: Int,
    states: CombinedLoadStates,
): PagingListPresentation {
    val refreshing = states.refresh is LoadState.Loading ||
        states.source.refresh is LoadState.Loading || states.mediator?.refresh is LoadState.Loading
    val refreshError = (states.refresh as? LoadState.Error)?.error
        ?: (states.source.refresh as? LoadState.Error)?.error
        ?: (states.mediator?.refresh as? LoadState.Error)?.error
    if (loadedItemCount == 0) {
        val content = when {
            refreshError != null -> ListContentState.Error(refreshError)
            refreshing -> ListContentState.Loading
            else -> ListContentState.Empty
        }
        return PagingListPresentation(content, false, null, LoadMoreState.Idle)
    }
    val appendLoadState = states.append
    val append = when {
        refreshing || refreshError != null -> LoadMoreState.Idle
        appendLoadState is LoadState.Loading -> LoadMoreState.Loading
        appendLoadState is LoadState.Error -> LoadMoreState.Error(appendLoadState.error)
        appendLoadState.endOfPaginationReached -> LoadMoreState.End
        else -> LoadMoreState.Idle
    }
    return PagingListPresentation(ListContentState.Content, refreshing, refreshError, append)
}
