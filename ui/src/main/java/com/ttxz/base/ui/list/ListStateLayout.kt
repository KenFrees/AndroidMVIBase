package com.ttxz.base.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttxz.base.ui.R

/**
 * 统一显示列表的首屏加载、空数据、失败或正常内容。
 *
 * 不启动请求，不更改状态；[onRetry] 只通知宿主。已有数据时使用 Content，
 * 刷新/追加中的反馈单独展示，避免隐藏已有条目。默认尺寸遵循 [modifier] 与父约束。
 * @param state 当前主体展示状态。
 * @param onRetry 整页失败时的重试回调，不自动发送 Toast 或处理异常。
 * @param loadingContent 加载状态布局，默认显示转圈与本地化文案。
 * @param emptyContent 空数据布局，可由业务替换。
 * @param errorContent 失败布局，接收原异常和重试回调；默认不显示异常 message。
 * @param content 正常内容，只在 Content 状态下进入 Composition。
 */
@Composable
fun ListStateLayout(
    state: ListContentState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    loadingContent: @Composable () -> Unit = { ListLoadingIndicator() },
    emptyContent: @Composable () -> Unit = { Text(stringResource(R.string.ui_list_empty)) },
    errorContent: @Composable (Throwable, () -> Unit) -> Unit = { _, retry ->
        ListErrorMessage(stringResource(R.string.ui_list_load_failed), retry)
    },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            ListContentState.Loading -> loadingContent()
            ListContentState.Empty -> emptyContent()
            is ListContentState.Error -> errorContent(state.cause, onRetry)
            ListContentState.Content -> content()
        }
    }
}

@Composable
internal fun ListLoadingIndicator() {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.ui_list_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ListErrorMessage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.ui_list_retry)) }
    }
}
