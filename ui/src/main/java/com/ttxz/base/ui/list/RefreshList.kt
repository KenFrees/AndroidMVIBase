package com.ttxz.base.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ttxz.base.ui.R

/**
 * 带首屏状态与下拉刷新的纵向懒列表，使用 Material 3 手势和指示器。
 *
 * 宿主同步维护 [isRefreshing]、[contentState] 和数据，首屏加载和刷新中忽略重复手势回调。
 * 空数据和错误页仍处于可滚动容器中，允许下拉重试。不管理请求、分页或生命周期。
 * @param onRefresh 手势触发的刷新通知，宿主负责请求与结束状态。
 * @param contentState 主体展示状态；普通列表由调用方区分未加载、成功空集与失败。
 * @param listState 由调用方持有可控制滚动位置；默认跟随当前 Composition 保存。
 * @param onRetry 首屏失败重试，默认复用刷新回调。
 * @param indicator 自定义刷新指示器，可使用传入的 Material 状态观察拖动进度。
 * @param content 原生 LazyListScope，保留 items 的稳定 key、contentType 及头尾布局能力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshList(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    contentState: ListContentState = ListContentState.Content,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    onRetry: () -> Unit = onRefresh,
    loadingContent: @Composable () -> Unit = { ListLoadingIndicator() },
    emptyContent: @Composable () -> Unit = { Text(stringResource(R.string.ui_list_empty)) },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        ListErrorMessage(stringResource(R.string.ui_list_load_failed), retry)
    },
    indicator: @Composable BoxScope.(Boolean, PullToRefreshState) -> Unit = { refreshing, state ->
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    },
    content: LazyListScope.() -> Unit,
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing && contentState != ListContentState.Loading) onRefresh()
        },
        modifier = modifier,
        state = refreshState,
        indicator = { indicator(isRefreshing, refreshState) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
        ) {
            if (contentState == ListContentState.Content) {
                content()
            } else {
                item {
                    ListStateLayout(
                        state = contentState,
                        onRetry = onRetry,
                        modifier = Modifier.fillParentMaxSize(),
                        loadingContent = loadingContent,
                        emptyContent = emptyContent,
                        errorContent = errorContent,
                        content = {},
                    )
                }
            }
        }
    }
}
