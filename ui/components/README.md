# UI 组件目录

按组件名称、用途或关键字查找；打开对应说明后再看源码。此目录只登记 `ui` 模块中已实现的公共组件。

| 组件 | 适用场景与检索词 | 用法与约束 | 源码 |
| --- | --- | --- | --- |
| `LoadingDialog` | 加载、等待、阻塞式弹窗、进度指示 | [LoadingDialog 说明](loading-dialog.md) | [LoadingDialog.kt](../src/main/java/com/ttxz/base/ui/loading/LoadingDialog.kt) |
| `ToastHost` | 成功、失败、警告、短消息、长短时长 | [ToastHost 说明](toast.md) | [ToastHost.kt](../src/main/java/com/ttxz/base/ui/toast/ToastHost.kt) |
| `RefreshList` / `PagingList` | 列表、下拉刷新、自动加载更多、慢网、分页、样式替换 | [列表与分页说明](list.md) | [RefreshList.kt](../src/main/java/com/ttxz/base/ui/list/RefreshList.kt)、[PagingList.kt](../src/main/java/com/ttxz/base/ui/list/PagingList.kt) |
| `ListStateLayout` / `LoadMoreFooter` | 首屏加载、空数据、错误重试、加载尾部、没有更多 | [列表状态与用法](list.md) | [ListStateLayout.kt](../src/main/java/com/ttxz/base/ui/list/ListStateLayout.kt)、[LoadMoreFooter.kt](../src/main/java/com/ttxz/base/ui/list/LoadMoreFooter.kt) |

新增组件时，在本表增加一行，并为组件写独立说明：最小调用示例、状态与回调归属、交互/取消行为、已知限制和真实示例入口。组件实现按职责放入 `ui/src/main/java/com/ttxz/base/ui/` 下的对应包；只记录已经落地的 API。
