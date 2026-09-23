package com.ttxz.base.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ttxz.base.R
import com.ttxz.base.ui.loading.LoadingDialog
import com.ttxz.base.ui.toast.ToastDuration
import com.ttxz.base.ui.toast.ToastHost
import com.ttxz.base.ui.toast.rememberToastHostState
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import kotlinx.coroutines.delay

/** app 中的 UI 组件演示页；演示状态只在当前页面存活。 */
@Composable
fun UiComponentsScreen(onBack: () -> Unit) {
    val toastHostState = rememberToastHostState()
    var isLoading by rememberSaveable { mutableStateOf(false) }
    val successMessage = stringResource(R.string.toast_success_message)
    val failureMessage = stringResource(R.string.toast_failure_message)
    val warningMessage = stringResource(R.string.toast_warning_message)

    LaunchedEffect(isLoading) {
        if (isLoading) {
            delay(2_000)
            isLoading = false
        }
    }

    Scaffold(
        snackbarHost = { ToastHost(toastHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.back))
                }
                Text(text = stringResource(R.string.ui_components_title))
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(onClick = { isLoading = true }) {
                    Text(text = stringResource(R.string.show_loading))
                }
                Button(onClick = { toastHostState.showSuccess(successMessage) }) {
                    Text(text = stringResource(R.string.show_success_toast))
                }
                Button(onClick = {
                    toastHostState.showFailure(failureMessage, ToastDuration.LONG)
                }) {
                    Text(text = stringResource(R.string.show_failure_toast))
                }
                Button(onClick = { toastHostState.showWarning(warningMessage) }) {
                    Text(text = stringResource(R.string.show_warning_toast))
                }
            }
        }
    }

    LoadingDialog(
        visible = isLoading,
        message = stringResource(R.string.loading_message),
        dismissOnBackPress = false,
        dismissOnClickOutside = true,
        onDismissRequest = { isLoading = false },
    )
}

@Preview(showBackground = true)
@Composable
private fun UiComponentsScreenPreview() {
    AndroidMVIBaseTheme {
        UiComponentsScreen(onBack = {})
    }
}
