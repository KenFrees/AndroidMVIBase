package com.ttxz.base.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.ttxz.base.ui.R

/**
 * 从第一页向后自动追加的 Paging 列表，统一首屏、下拉刷新、追加和失败重试展示。
 *
 * 读取 [items] 的条目触发 Paging 按需加载，预取距离由数据层 PagingConfig 决定。
 * 有真实数据时保留列表；刷新失败在列表上方固定显示重试，追加失败在底部显示重试。
 * 刷新调用 items.refresh，失败重试调用 items.retry；不自行创建协程、吞异常或改写数据。
 * 不主动改变滚动位置，不提供业务 API、页码计算、筛选及进程恢复。
 * @param items 宿主在 Composition 中 collectAsLazyPagingItems 得到的分页数据。
 * @param itemKey 条目稳定且唯一的 key，建议传业务 ID；不要与头尾自定义 key 重复。
 * @param itemContentType 多种条目结构的复用标识，默认统一类型。
 * @param header 正常内容区域的原生 LazyListScope 头部，不在首屏状态页中展示。
 * @param footerContent 统一追加尾部的替换入口；Idle 时不调用，End 可通过 showEndOfList 隐藏。
 * @param refreshErrorContent 已有数据时刷新失败的提示及重试布局。
 * @param placeholderContent 启用 Paging 占位时显示 null 条目，默认提供 56dp 高度。
 * @param itemContent 真实条目的布局，点击、选择等业务行为由调用方提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> PagingList(
    items: LazyPagingItems<T>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemKey: ((T) -> Any)? = null,
    itemContentType: (T) -> Any? = { null },
    showEndOfList: Boolean = true,
    header: LazyListScope.() -> Unit = {},
    loadingContent: @Composable () -> Unit = { ListLoadingIndicator() },
    emptyContent: @Composable () -> Unit = { Text(stringResource(R.string.ui_list_empty)) },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        ListErrorMessage(stringResource(R.string.ui_list_load_failed), retry)
    },
    refreshErrorContent: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        ListErrorMessage(stringResource(R.string.ui_list_refresh_failed), retry)
    },
    footerContent: @Composable (LoadMoreState, () -> Unit) -> Unit = { state, retry ->
        LoadMoreFooter(state, retry)
    },
    placeholderContent: @Composable LazyItemScope.() -> Unit = {
        Box(Modifier.fillMaxWidth().height(56.dp))
    },
    indicator: @Composable BoxScope.(Boolean, PullToRefreshState) -> Unit = { refreshing, state ->
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val presentation = resolvePagingListPresentation(items.itemSnapshotList.items.size, items.loadState)
    Column(modifier) {
        // 放在滚动容器外，避免稳定 key 的位置保持把新插入的失败提示留在屏幕上方。
        presentation.refreshError?.let { error ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                refreshErrorContent(error, items::retry)
            }
        }
        RefreshList(
            isRefreshing = presentation.isRefreshing,
            onRefresh = items::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentState = presentation.content,
            listState = listState,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            onRetry = items::retry,
            loadingContent = loadingContent,
            emptyContent = emptyContent,
            errorContent = errorContent,
            indicator = indicator,
        ) {
            header()
            items(
                count = items.itemCount,
                key = items.itemKey(itemKey),
                contentType = items.itemContentType(itemContentType),
            ) { index ->
                val value = items[index]
                if (value == null) placeholderContent() else itemContent(value)
            }
            if (presentation.append != LoadMoreState.Idle &&
                (showEndOfList || presentation.append != LoadMoreState.End)
            ) {
                item { footerContent(presentation.append, items::retry) }
            }
        }
    }
}
