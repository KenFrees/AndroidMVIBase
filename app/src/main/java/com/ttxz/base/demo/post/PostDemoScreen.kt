package com.ttxz.base.demo.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ttxz.base.R
import com.ttxz.base.ui.loading.LoadingDialog
import com.ttxz.base.ui.toast.ToastDuration
import com.ttxz.base.ui.toast.ToastHost
import com.ttxz.base.ui.toast.rememberToastHostState

/** 演示 Retrofit → Repository → ViewModel → Compose 的请求和错误展示。 */
@Composable
fun PostDemoScreen(onBack: () -> Unit, viewModel: PostDemoViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading = uiState == PostDemoUiState.Loading
    val toastHostState = rememberToastHostState()
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel, toastHostState, resources, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    PostDemoEffect.Success -> toastHostState.showSuccess(
                        resources.getString(R.string.network_demo_success_toast),
                    )
                    is PostDemoEffect.Failure -> toastHostState.showFailure(
                        message = when {
                            effect.httpCode != null -> resources.getString(
                                R.string.network_demo_http_error, effect.httpCode,
                            )
                            effect.isCertificateError -> resources.getString(
                                R.string.network_demo_certificate_error,
                            )
                            else -> resources.getString(R.string.network_demo_connection_error)
                        },
                        duration = ToastDuration.LONG,
                    )
                }
            }
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
                Text(text = stringResource(R.string.network_demo_title))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(onClick = { viewModel.onAction(PostDemoAction.LoadPost(1)) }, enabled = !isLoading) {
                Text(text = stringResource(R.string.network_demo_request))
            }
            Button(onClick = { viewModel.onAction(PostDemoAction.LoadPost(999_999)) }, enabled = !isLoading) {
                Text(text = stringResource(R.string.network_demo_request_missing))
            }
            when (val state = uiState) {
                PostDemoUiState.Idle -> Text(text = stringResource(R.string.network_demo_hint))
                PostDemoUiState.Loading -> Unit
                is PostDemoUiState.Content -> {
                    Text(text = state.title)
                    Text(text = state.body)
                }
                is PostDemoUiState.Error -> Text(
                    text = if (state.httpCode != null) {
                        stringResource(R.string.network_demo_http_error, state.httpCode)
                    } else if (state.isCertificateError) {
                        stringResource(R.string.network_demo_certificate_error)
                    } else {
                        stringResource(R.string.network_demo_connection_error)
                    },
                )
            }
        }
    }

    LoadingDialog(
        visible = isLoading,
        message = stringResource(R.string.network_demo_loading),
    )
}
