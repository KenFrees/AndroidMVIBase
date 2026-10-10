package com.ttxz.base.demo.logging

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ttxz.base.R
import com.ttxz.base.logging.LogLevel
import com.ttxz.base.logging.Logging
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** 独立日志演示页；按固定 TAG 写入，挂起刷新和文件查询跟随页面生命周期。 */
@Composable
fun LogDemoScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    val logger = remember { Logging.getLogger("LogDemo") }
    val scope = rememberCoroutineScope()
    // 快速 IO 操作仍禁止重复点击，但不让全页按钮瞬间变灰再恢复。
    val actionColors = ButtonDefaults.buttonColors(
        disabledContainerColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
    )
    var message by rememberSaveable { mutableStateOf("日志写入测试：operationId=demo") }
    var queryDate by rememberSaveable { mutableStateOf("") }
    var feedback by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(Logging.status()) }

    fun write(level: LogLevel) {
        val accepted = when (level) {
            LogLevel.DEBUG -> logger.debug(message)
            LogLevel.INFO -> logger.info(message)
            LogLevel.WARN -> logger.warn(message)
            LogLevel.ERROR -> logger.error(message, IllegalStateException("演示异常，无业务影响"))
        }
        feedback = resources.getString(when {
            !logger.isEnabled(level) -> R.string.log_demo_filtered
            accepted -> R.string.log_demo_accepted
            else -> R.string.log_demo_rejected
        })
        status = Logging.status()
    }

    Scaffold(topBar = {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.open_log_demo))
        }
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.log_demo_directory, Logging.initialization?.directory ?: stringResource(R.string.log_demo_directory_unavailable)))
            Text(stringResource(R.string.log_demo_hint))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(stringResource(R.string.log_demo_content)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(colors = actionColors, enabled = !busy, onClick = { write(LogLevel.DEBUG) }) { Text("DEBUG") }
                Button(colors = actionColors, enabled = !busy, onClick = { write(LogLevel.INFO) }) { Text("INFO") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(colors = actionColors, enabled = !busy, onClick = { write(LogLevel.WARN) }) { Text("WARN") }
                Button(colors = actionColors, enabled = !busy, onClick = { write(LogLevel.ERROR) }) {
                    Text(stringResource(R.string.log_demo_error))
                }
            }
            Button(colors = actionColors, enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = Logging.flush()
                        status = Logging.status()
                        feedback = resources.getString(if (result.isSuccessful) R.string.log_demo_flushed else R.string.log_demo_flush_failed)
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.log_demo_flush)) }
            OutlinedTextField(
                value = queryDate,
                onValueChange = { queryDate = it },
                label = { Text(stringResource(R.string.log_demo_date)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            fun queryFiles(preview: Boolean) {
                val date = try {
                    queryDate.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                } catch (_: java.time.format.DateTimeParseException) {
                    feedback = resources.getString(R.string.log_demo_date_invalid)
                    return
                }
                busy = true
                scope.launch {
                    try {
                        val files = Logging.logFiles(date).getOrThrow()
                        feedback = when {
                            files.isEmpty() -> resources.getString(R.string.log_demo_files_empty)
                            preview -> previewLogFile(files.first())
                            else -> files.joinToString("\n") { it.absolutePath }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        feedback = resources.getString(R.string.log_demo_files_failed, error.javaClass.simpleName)
                    } finally { busy = false; status = Logging.status() }
                }
            }
            Button(colors = actionColors, enabled = !busy, onClick = { queryFiles(false) }) {
                Text(stringResource(R.string.log_demo_files))
            }
            Button(colors = actionColors, enabled = !busy, onClick = { queryFiles(true) }) {
                Text(stringResource(R.string.log_demo_preview))
            }
            Button(colors = actionColors, enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { feedback = checkLogIntegration() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        feedback = resources.getString(R.string.log_demo_files_failed, error.javaClass.simpleName)
                    } finally { busy = false; status = Logging.status() }
                }
            }) { Text(stringResource(R.string.log_demo_check)) }
            Button(colors = actionColors, enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { feedback = checkLogLifecycle() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        feedback = resources.getString(R.string.log_demo_lifecycle_failed, error.message ?: error.javaClass.simpleName)
                    } finally { busy = false; status = Logging.status() }
                }
            }) { Text(stringResource(R.string.log_demo_lifecycle)) }
            Text(if (busy) stringResource(R.string.log_demo_busy) else " ")
            if (feedback.isNotEmpty()) Text(feedback)
            Text(stringResource(R.string.log_demo_status, status.accepted, status.written, status.dropped, status.failed))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UIShowPrevier(){
    AndroidMVIBaseTheme {
        LogDemoScreen(onBack = {})
    }
}