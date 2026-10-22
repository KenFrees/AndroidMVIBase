# common 能力目录

按能力名称、用途或关键词查找；用法列有独立说明时提供链接，其余直接列出关键约束。只登记已实现能力，通用接入与详细说明见 [common README](../README.md)。

| 能力 | 适用场景与检索词 | 用法与约束 | 源码 |
| --- | --- | --- | --- |
| `BaseApplication` 应用基类 | Application、应用入口、共享加载器、默认配置、配置覆盖 | 宿主继承并在 Manifest 注册；Hilt 注解与日志初始化留在宿主，配置覆盖见图片加载说明 | [BaseApplication.kt](../src/main/java/com/ttxz/base/common/application/BaseApplication.kt) |
| Coil 图片加载 | 网络图、本地 Uri、内存缓存、磁盘缓存、占位图、圆形头像、`AsyncImage` | [图片加载说明](image-loading.md) | [ImageLoading.kt](../src/main/java/com/ttxz/base/common/image/ImageLoading.kt) |
| `AppResult` 与结果回调 | 成功、失败、Loading、泛型、`onSuccess`、`onError` | Loading 只是值，回调不切线程；结果不等于页面状态，用法见模块 README | [AppResult.kt](../src/main/java/com/ttxz/base/common/result/AppResult.kt) |
| 轻量 MVI | `BaseViewModel`、Action、State、Effect、StateFlow、SharedFlow、原子更新 | Effect 不重放，无订阅者会丢弃；不提供请求队列或进程恢复，用法见模块 README | [BaseViewModel.kt](../src/main/java/com/ttxz/base/common/mvi/BaseViewModel.kt)、[MviContract.kt](../src/main/java/com/ttxz/base/common/mvi/MviContract.kt) |

新增或调整公共能力时，同步本表、对应使用说明和真实示例入口。当前没有分页、可靠消息确认、进程状态恢复等通用封装，不能由已引入的第三方依赖推断这些能力已经存在。
