# LoadingDialog

使用 Material 3 `CircularProgressIndicator` 显示阻塞式加载弹窗，并展示调用方提供的文案。源码：[LoadingDialog.kt](../src/main/java/com/ttxz/base/ui/loading/LoadingDialog.kt)。

## 最小调用

调用方依赖 `:ui`，在 Compose 主题内通过状态控制：

```kotlin
LoadingDialog(
    visible = uiState.isLoading,
    message = stringResource(R.string.loading_message),
)
```

上面是拟议的 MVI 页面用法；本仓库尚无 `uiState` 实现。真实演示见 [app 首页](../../app/src/main/java/com/ttxz/base/MainActivity.kt)：点击按钮显示弹窗，两秒后关闭，未发起网络请求。

需要允许用户主动关闭时，分别打开所需方式，并在回调中更新显示状态。例如只允许返回键关闭：

```kotlin
LoadingDialog(
    visible = isLoading,
    message = stringResource(R.string.loading_message),
    dismissOnBackPress = true,
) {
    isLoading = false
}
```

## 状态与交互约束

- `visible` 由调用方维护。操作成功、失败或取消时都要设回 `false`。
- 默认不响应返回键或点击弹窗外部。`dismissOnBackPress` 和 `dismissOnClickOutside` 可分别开启；开启任一方式时必须提供 `onDismissRequest`，否则会抛出参数异常。
- Compose 只会调用 `onDismissRequest`，不会自动把 `visible` 改成 `false`。`message` 应由调用方提供本地化文案。
- 关闭弹窗只改变界面显示，不会自动取消正在执行的请求；需要取消时由调用方在回调中处理。
- 多个并发操作由调用方决定何时结束显示，先完成的操作不能提前隐藏另一个操作的加载状态。
- 组件不会自动订阅 `AppResult.Loading`，不会启动请求或创建应用级单例。

## 验证与限制

可执行 `:ui:assembleDebug :app:assembleDebug` 检查编译；弹窗外观和交互需要设备验证。当前只有不确定进度的阻塞式弹窗，不提供线性进度或跨页面状态管理。

练习：两个操作同时开始，一个先完成时，页面如何保持正确的 `visible` 值？
