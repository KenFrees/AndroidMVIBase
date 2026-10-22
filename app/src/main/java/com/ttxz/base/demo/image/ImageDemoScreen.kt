package com.ttxz.base.demo.image

import android.util.Log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ttxz.base.R

/** 演示共享加载器及页面自选占位图，不保存用户选择的图片或持久 Uri 权限。 */
@Composable
fun ImageDemoScreen(onBack: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf("network") }
    var localUri by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            localUri = uri.toString()
            mode = "local"
            requestId++
        }
    }
    val model = when (mode) {
        "local" -> localUri
        "error" -> "https://coil-image-demo.invalid/missing.jpg"
        "empty" -> null
        else -> "https://raw.githubusercontent.com/github/explore/main/topics/android/android.png"
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.image_demo_hint))
        Button(onClick = { mode = "network"; requestId++ }) {
            Text(stringResource(R.string.image_demo_network))
        }
        Button(onClick = { picker.launch("image/*") }) {
            Text(stringResource(R.string.image_demo_local))
        }
        Button(onClick = { mode = "error"; requestId++ }) {
            Text(stringResource(R.string.image_demo_error))
        }
        Button(onClick = { mode = "empty"; requestId++ }) {
            Text(stringResource(R.string.image_demo_empty))
        }
        // 相同 URL 也能显式重试；仅重建请求，不改变 URL 或缓存键。
        key(requestId, model) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.image_demo_description),
                placeholder = painterResource(R.drawable.image_demo_placeholder),
                error = painterResource(R.drawable.image_demo_error),
                fallback = painterResource(R.drawable.image_demo_empty),
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentScale = ContentScale.Fit,
                onLoading = { result = "LOADING" },
                onSuccess = { result = it.result.dataSource.name },
                onError = {
                    val error = it.result.throwable
                    result = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    Log.e("ImageDemo", "Image request failed", error)
                },
            )
        }
        Text(stringResource(R.string.image_demo_source, result))
    }
}
