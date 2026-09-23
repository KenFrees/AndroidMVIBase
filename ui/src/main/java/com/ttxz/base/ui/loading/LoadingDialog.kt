package com.ttxz.base.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 在当前窗口显示阻塞式加载弹窗。
 *
 * 默认不响应返回键或点击弹窗外部。允许关闭时必须提供 [onDismissRequest]，
 * 并由调用方在回调中更新状态以移除弹窗；操作完成、失败或取消时也应结束显示。
 * @param visible 是否显示弹窗。
 * @param message 显示给用户的加载说明，由调用方提供本地化文案。
 * @param dismissOnBackPress 是否允许通过返回键请求关闭，默认不允许。
 * @param dismissOnClickOutside 是否允许通过点击弹窗外部请求关闭，默认不允许。
 * @param onDismissRequest 用户请求关闭时的回调；启用任一关闭方式时必传。
 */
@Composable
fun LoadingDialog(
    visible: Boolean,
    message: String,
    dismissOnBackPress: Boolean = false,
    dismissOnClickOutside: Boolean = false,
    onDismissRequest: (() -> Unit)? = null,
) {
    require(!(dismissOnBackPress || dismissOnClickOutside) || onDismissRequest != null) {
        "onDismissRequest is required when dismissal is enabled"
    }
    if (!visible) return

    Dialog(
        onDismissRequest = { onDismissRequest?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
