# UI 组件目录

按组件名称、用途或关键字查找；打开对应说明后再看源码。此目录只登记 `ui` 模块中已实现的公共组件。

| 组件 | 适用场景与检索词 | 用法与约束 | 源码 |
| --- | --- | --- | --- |
| `LoadingDialog` | 加载、等待、阻塞式弹窗、进度指示 | [LoadingDialog 说明](loading-dialog.md) | [LoadingDialog.kt](../src/main/java/com/ttxz/base/ui/loading/LoadingDialog.kt) |

新增组件时，在本表增加一行，并为组件写独立说明：最小调用示例、状态与回调归属、交互/取消行为、已知限制和真实示例入口。组件实现按职责放入 `ui/src/main/java/com/ttxz/base/ui/` 下的对应包；只记录已经落地的 API。
