# ToastHost

展示成功、失败、警告三类短消息。视觉参考为深色圆角提示条、左侧彩色标记和白色文字；使用 Material 3 `SnackbarHostState` 保存当前消息，由 `ToastHost` 控制时长和 Compose 动画，不使用系统 `android.widget.Toast`。源码：[ToastHost.kt](../src/main/java/com/ttxz/base/ui/toast/ToastHost.kt)。

## 最小调用

在页面的 `Scaffold` 中放一个 Host，状态与页面一起创建：

```kotlin
val toastState = rememberToastHostState()
Scaffold(snackbarHost = { ToastHost(toastState) }) { padding ->
    Button(
        modifier = Modifier.padding(padding),
        onClick = { toastState.showSuccess("保存成功") },
    ) { Text("保存") }
}
```

三类消息分别调用 `showSuccess`、`showFailure`、`showWarning`。默认 `ToastDuration.SHORT`；传 `ToastDuration.LONG` 可延长显示：

```kotlin
toastState.showFailure("提交失败，请稍后重试", ToastDuration.LONG)
```

真实演示位于 [app 的 UI 组件演示页](../../app/src/main/java/com/ttxz/base/demo/ui/UiComponentsScreen.kt)，有成功短、失败长、警告短三个按钮；从首页的“UI 组件”入口进入。

## 状态与生命周期

- `show…` 立即返回。新消息替换当前消息；连续快速发送时只保留最新的一条，不积压队列。
- 消息从底部上移并淡入；超时或被新消息替换时向下移动并淡出，然后新消息进入。后续 MVI 的一次性消息事件应在页面生命周期作用域中收集，再调用相应方法。当前仓库尚未实现 MVI Effect API。
- `ToastHostState` 跟随创建它的 Composition，不保存 `Activity` 或 `Context`，不要放进 ViewModel、单例或无生命周期的协程作用域。
- `SHORT` 和 `LONG` 基础时长分别为 2 秒和 3.5 秒，参照系统 Toast 的常见时长；实际时间可能按无障碍设置延长。Host 离开 Composition 后取消计时与展示，消息不跨页保留。
- 文案由调用方提供；颜色和形状由 `ui` 模块统一管理。Host 放在 `Scaffold` 的 `snackbarHost` 槽位，默认位于页面底部。

## 验证与限制

执行 `:ui:assembleDebug :app:assembleDebug` 验证编译；颜色、动画、不同字号与无障碍朗读需要设备验证。当前不支持操作按钮或持久消息。

练习：连续点击三个按钮，观察旧消息的退出动画和最新消息的进入动画；分别等待短消息与长消息自动消失。
