package com.ttxz.base.ui.toast

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ttxz.base.ui.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 消息展示时长；基础时长对齐系统 Toast：短 2 秒、长 3.5 秒，并按无障碍建议调整。 */
enum class ToastDuration { SHORT, LONG }

/**
 * 页面范围内的消息状态，新的消息会替换正在展示的消息。
 *
 * 使用 [rememberToastHostState] 创建，并在同一页面放置 [ToastHost]。
 * 显示函数立即返回，超时与动画由 Host 在 Composition 生命周期内管理。
 * 不要将此状态保存到 ViewModel 或全局单例中。
 */
@Stable
class ToastHostState internal constructor() {
    internal val snackbarHostState = SnackbarHostState()
    internal val messages = Channel<ToastVisuals>(Channel.CONFLATED)

    /** 展示成功消息。 */
    fun showSuccess(message: String, duration: ToastDuration = ToastDuration.SHORT) {
        show(message, ToastKind.SUCCESS, duration)
    }

    /** 展示失败消息。 */
    fun showFailure(message: String, duration: ToastDuration = ToastDuration.SHORT) {
        show(message, ToastKind.FAILURE, duration)
    }

    /** 展示警告消息。 */
    fun showWarning(message: String, duration: ToastDuration = ToastDuration.SHORT) {
        show(message, ToastKind.WARNING, duration)
    }

    private fun show(message: String, kind: ToastKind, duration: ToastDuration) {
        messages.trySend(
            ToastVisuals(
                message = message,
                kind = kind,
                toastDuration = duration,
            ),
        )
    }
}

/** 创建跟随当前 Composition 生命周期的消息状态。 */
@Composable
fun rememberToastHostState(): ToastHostState = remember { ToastHostState() }

/**
 * 在 Scaffold 的 snackbarHost 槽位展示消息；每个状态只应对应一个 Host。
 * 使用方在页面事件中直接调用 [ToastHostState.showSuccess] 等方法。
 */
@Composable
fun ToastHost(state: ToastHostState, modifier: Modifier = Modifier) {
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(state, accessibilityManager) {
        state.messages.receiveAsFlow().collectLatest { visuals ->
            val baseTimeout = when (visuals.toastDuration) {
                ToastDuration.SHORT -> 2_000L
                ToastDuration.LONG -> 3_500L
            }
            val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = baseTimeout,
                containsIcons = true,
                containsText = true,
                containsControls = false,
            ) ?: baseTimeout
            withTimeoutOrNull(timeout) {
                state.snackbarHostState.showSnackbar(visuals)
            }
        }
    }

    AnimatedContent(
        targetState = state.snackbarHostState.currentSnackbarData,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val enterDelay = if (initialState != null) 180 else 0
            (slideInVertically(animationSpec = tween(240, delayMillis = enterDelay)) { it } +
                fadeIn(animationSpec = tween(240, delayMillis = enterDelay))) togetherWith
                (slideOutVertically(animationSpec = tween(180)) { it } +
                    fadeOut(animationSpec = tween(180))) using SizeTransform(clip = false)
        },
        label = "Toast message",
    ) { data ->
        if (data != null) {
            ToastCard(data.visuals as ToastVisuals)
        }
    }
}

@Composable
private fun ToastCard(visuals: ToastVisuals) {
    val iconColor = when (visuals.kind) {
        ToastKind.SUCCESS -> Color(0xFF47C985)
        ToastKind.FAILURE -> Color(0xFFEF6B6B)
        ToastKind.WARNING -> Color(0xFFFFC857)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF171717),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusIcon(kind = visuals.kind, color = iconColor)
            Text(
                text = visuals.message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

internal enum class ToastKind { SUCCESS, FAILURE, WARNING }

internal data class ToastVisuals(
    override val message: String,
    val kind: ToastKind,
    val toastDuration: ToastDuration,
) : SnackbarVisuals {
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
}

@Composable
private fun StatusIcon(kind: ToastKind, color: Color) {
    val label = when (kind) {
        ToastKind.SUCCESS -> stringResource(R.string.ui_toast_status_success)
        ToastKind.FAILURE -> stringResource(R.string.ui_toast_status_failure)
        ToastKind.WARNING -> stringResource(R.string.ui_toast_status_warning)
    }
    Canvas(modifier = Modifier.size(28.dp).semantics { contentDescription = label }) {
        val lineWidth = 2.2.dp.toPx()
        val stroke = Stroke(width = lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            ToastKind.SUCCESS, ToastKind.FAILURE -> {
                drawCircle(color = color, radius = size.minDimension * 0.41f, style = stroke)
                if (kind == ToastKind.SUCCESS) {
                    val check = Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.67f)
                        lineTo(size.width * 0.74f, size.height * 0.35f)
                    }
                    drawPath(check, color = color, style = stroke)
                } else {
                    drawLine(
                        color, Offset(size.width * 0.36f, size.height * 0.36f),
                        Offset(size.width * 0.64f, size.height * 0.64f), lineWidth, StrokeCap.Round,
                    )
                    drawLine(
                        color, Offset(size.width * 0.64f, size.height * 0.36f),
                        Offset(size.width * 0.36f, size.height * 0.64f), lineWidth, StrokeCap.Round,
                    )
                }
            }

            ToastKind.WARNING -> {
                val triangle = Path().apply {
                    moveTo(size.width * 0.5f, size.height * 0.12f)
                    lineTo(size.width * 0.9f, size.height * 0.82f)
                    lineTo(size.width * 0.1f, size.height * 0.82f)
                    close()
                }
                drawPath(triangle, color = color, style = stroke)
                drawLine(
                    color, Offset(size.width * 0.5f, size.height * 0.36f),
                    Offset(size.width * 0.5f, size.height * 0.58f), lineWidth, StrokeCap.Round,
                )
                drawCircle(
                    color, radius = lineWidth * 0.55f,
                    center = Offset(size.width * 0.5f, size.height * 0.69f),
                )
            }
        }
    }
}
