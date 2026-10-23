package com.ttxz.base.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttxz.base.ui.R

/**
 * 显示下一页加载、失败重试或分页结束；Idle 时不占空间。
 *
 * 不监听滚动或自动请求，重试由 [onRetry] 交给数据层。默认文案可通过槽位替换。
 * @param state 尾部展示状态，与首屏及刷新状态分开管理。
 * @param onRetry 追加失败时的重试回调。
 * @param loadingContent 加载指示器，默认显示转圈和“正在加载”。
 * @param errorContent 接收失败原因和重试回调的自定义布局。
 * @param endContent 分页结束布局；不需要结束提示时可传空内容。
 */
@Composable
fun LoadMoreFooter(
    state: LoadMoreState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = { ListLoadingIndicator() },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        ListErrorMessage(stringResource(R.string.ui_list_load_failed), retry)
    },
    endContent: @Composable () -> Unit = {
        Text(
            stringResource(R.string.ui_list_no_more),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    },
) {
    if (state == LoadMoreState.Idle) return
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (state) {
            LoadMoreState.Idle -> Unit
            LoadMoreState.Loading -> loadingContent()
            is LoadMoreState.Error -> errorContent(state.cause, onRetry)
            LoadMoreState.End -> endContent()
        }
    }
}
