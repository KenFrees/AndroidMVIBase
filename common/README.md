# common：应用基础能力、图片加载与轻量 MVI

## 职责与边界

作为应用公共底座，提供 Application 基类、统一图片加载、公共结果类型，以及轻量 MVI 的状态容器和临时效果流。不处理业务 API 请求、导航或 Toast，不实现通用图片展示组件。

这是 Android Library，命名空间 `com.ttxz.base.common`，minSdk 26。通过 `api` 导出 ViewModel、Coroutines Core 和 Coil Compose；图片网络组件作为运行时实现依赖提供。所有依赖 common 的消费者都会带入图片与 Compose 相关依赖，这是统一底座的明确取舍。common 不反向依赖 app、network 或 ui；Hilt、Compose 生命周期收集和宿主的 Compose 构建插件仍由宿主配置。结果模型源码本身不使用 Android 或协程 API。

## 能力导航

从 [common 能力目录](capabilities/README.md) 按名称、用途或关键词查找：应用基类、图片加载与缓存、结果回调、轻量 MVI。目录列出关键用法与约束、源码链接，有独立说明的能力可打开详情；下文保留接入和详细契约。

## 当前能力

应用与图片的支持范围、最小调用、接入和常见用法统一见独立的 [图片加载说明](capabilities/image-loading.md)。宿主默认继承 BaseApplication 即可，不需要重复实现 Coil 工厂或声明 Coil Compose 依赖。

结果模型入口：[AppResult.kt](src/main/java/com/ttxz/base/common/result/AppResult.kt)。

MVI 入口：[MviContract.kt](src/main/java/com/ttxz/base/common/mvi/MviContract.kt)、[BaseViewModel.kt](src/main/java/com/ttxz/base/common/mvi/BaseViewModel.kt)。真实调用方见 [文章 ViewModel](../app/src/main/java/com/ttxz/base/demo/post/PostDemoViewModel.kt) 和 [文章页面](../app/src/main/java/com/ttxz/base/demo/post/PostDemoScreen.kt)。

| 类型 / 方法 | 内容 | 使用注意 |
| --- | --- | --- |
| `AppResult.Success<T>` | `data: T` | 保存本次成功结果 |
| `AppResult.Error` | `message`、可空 `throwable`、可空 `code` | code 的语义由产生错误的一方决定；当前网络层填的是 HTTP 状态码 |
| `AppResult.Loading` | 无附加数据的单例 | 只是一个值，不会自行启动请求或产生状态流 |
| `onSuccess` | 成功时调用传入函数，返回原结果 | Error / Loading 时不调用 |
| `onError` | 失败时调用传入函数，返回原结果 | Success / Loading 时不调用 |

扩展回调同步执行；回调抛出的异常会继续向外传播，不会自动变成 Error。

## 怎么使用

消费者模块增加：

```kotlin
dependencies {
    implementation(project(":common"))
}
```

最小示例（用于说明 API，不是已有业务文件）：

```kotlin
import com.ttxz.base.common.result.AppResult
import com.ttxz.base.common.result.onError
import com.ttxz.base.common.result.onSuccess

val result: AppResult<String> = AppResult.Success("示例内容")

val message = when (result) {
    is AppResult.Success -> result.data
    is AppResult.Error -> result.message
    AppResult.Loading -> "正在加载"
}

result
    .onSuccess { println(it) }
    .onError { println(it.message) }
```

需要完整处理所有结果时使用 `when`；仅附加成功/失败处理时可使用扩展函数。它们不会切线程、创建协程或替调用方维护加载状态。

网络层通过该类型将调用结果交给消费者，具体异常转换见 [network](../network/README.md)。页面拥有自己的 UiState：列表、刷新状态、选中项等不能仅靠 AppResult 完整表达。

## 轻量 MVI 的使用与边界

`BaseViewModel<Action : UiAction, State : UiState, Effect : UiEffect>` 创建私有可变 Flow，公开只读 `uiState: StateFlow<State>` 和 `effects: SharedFlow<Effect>`。三个接口是角色标记，不自动保证不可变性或完整 MVI 架构。

最小示例（用于说明 API，不是已有业务文件）：

```kotlin
import androidx.lifecycle.viewModelScope
import com.ttxz.base.common.mvi.BaseViewModel
import com.ttxz.base.common.mvi.UiAction
import com.ttxz.base.common.mvi.UiEffect
import com.ttxz.base.common.mvi.UiState
import kotlinx.coroutines.launch

sealed interface CounterAction : UiAction {
    data object Increment : CounterAction
}
data class CounterState(val count: Int = 0) : UiState
sealed interface CounterEffect : UiEffect {
    data object Incremented : CounterEffect
}

class CounterViewModel : BaseViewModel<CounterAction, CounterState, CounterEffect>(CounterState()) {
    // 本示例的 Action 由 UI 主线程调用。
    override fun onAction(action: CounterAction) {
        when (action) {
            CounterAction.Increment -> {
                updateUiState { copy(count = count + 1) }
                viewModelScope.launch { sendEffect(CounterEffect.Incremented) }
            }
        }
    }
}
```

- 基类负责容器、只读暴露、原子状态变换和临时效果发送；宿主负责状态内容、请求、错误映射、Action 并发和页面生命周期。
- State 应使用不可变数据；基类不冻结对象。`updateUiState(newState)` 替换整个状态，并发替换以最后写入为准；`currentState` 只是瞬时快照。
- 需要基于现有字段计算时使用 `updateUiState { copy(...) }`，内部通过 `MutableStateFlow.update` 原子更新。Lambda 可能重试，只计算新状态，不请求网络、发送效果或修改旧对象。原子更新不解决请求过期和响应乱序。
- StateFlow 保存内存中的最新状态，按相等性抑制重复值，慢订阅者可能跳过中间状态；不自动提供 SavedStateHandle、磁盘保存或进程恢复。
- `onAction` 直接交给子类处理，不自动切线程、串行排队或去重。示例中的请求门控属于业务 ViewModel。
- `sendEffect` 是挂起函数，在调用者协程中 `emit`；不创建隐藏后台协程。有订阅者时保留一个额外缓冲位，缓冲满时等待；等待可取消，取消继续传播。发送返回不表示 UI 已执行。
- Effect 不重放：无订阅者时丢弃，`extraBufferCapacity` 不会给未来订阅者存消息；多个订阅者各自收到广播。订阅取消也可能中断处理，不能用于保证业务结果交付。
- UI 状态使用 `collectAsStateWithLifecycle`；Effect 在 `LaunchedEffect` 内结合 `repeatOnLifecycle(STARTED)` 收集，一个页面通常只设置一个效果收集者。停止收集期间的提示允许遗漏，恢复后读取最新 UiState。
- 必须保留的业务结果写入 UiState；确有消息确认需求时另行设计标识和确认机制。本步不增加通用事件队列或确认系统。

迁移本次草稿：导入路径由 `common.base` 改为 `common.mvi`，契约文件为 `MviContract.kt`；`sendEffect` 改为挂起函数，已有请求协程内可直接调用，非协程入口需使用有归属的作用域。状态和效果的业务类型仍留在功能包。

语义参考：[StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)、[原子更新](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/update.html)、[SharedFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)、[Android UI 事件](https://developer.android.com/topic/architecture/ui-layer/events)。

## 这一步需要学习什么

1. 阅读 `sealed interface` 与三个实现：为什么调用方可以完整列出所有分支？
2. 阅读 `out T`、`Nothing`：为什么 Error / Loading 可以作为不同数据类型的结果返回？
3. 跟踪 `onSuccess` / `onError`：为什么能链式调用，Loading 时会发生什么？
4. 区分“请求结果”与“整个页面状态”，再阅读 MviContract → BaseViewModel → 文章 ViewModel → 文章页面。

练习：把示例分别改成 `Error("请求失败")` 和 `Loading`，先推测两个回调各会不会执行，再验证。

本步通过标准：能解释三个分支、可空 code 和回调行为，不把 Loading 理解成自动加载机制。

MVI 练习：推测两个线程同时递增状态字段，以及页面停止收集后发送效果的结果，再阅读 [BaseViewModelTest](src/test/java/com/ttxz/base/common/mvi/BaseViewModelTest.kt) 核对。通过标准是能解释原子更新、纯 Lambda、无订阅者丢弃和取消传播，并沿文章页追踪一次请求。

## 验证与未完成项

可用 `:common:testDebugUnitTest :app:assembleDebug :app:lintDebug` 验证 MVI 行为和示例集成。MVI 单测覆盖初始/替换状态、并发更新、无订阅者不重放、慢订阅者背压、挂起发送取消及多订阅者广播。测试使用现有 Coroutines Core 和 JUnit，不增加协程测试库。

当前没有结果模型专项单测。尚未定义统一业务错误分类、错误文案本地化、结果转换工具；确有使用场景后再逐步添加，避免提前扩展公共 API。

MVI 基类不包含通用请求、分页、恢复、事件确认或串行 Store。单测不等于设备/服务验证；文章页的前后台、旋转和真实请求仍需设备验收。

2026-10-13：`:common:testDebugUnitTest` 通过（MVI 6 项，0 失败），`:app:assembleDebug` 和 `:app:lintDebug` 通过；Markdown 本地文件链接及 `git diff --check` 通过。本次未执行设备或服务端验证。

修改本模块时，同步检查 [network](../network/README.md) 及所有 AppResult 调用者，并更新本说明。

2026-10-22 应用基类与图片能力迁移：`:app:assembleDebug :app:assembleRelease testDebugUnitTest lintDebug` 通过，82 项单测无失败，lint 无错误。宿主从 BaseApp 改名为 AppApplication 后，使用 `:app:clean` 清理旧 Hilt 生成记录。DAYU_A6 启动及图片显示正常，首次返回 DISK，再次点击返回 MEMORY_CACHE；本地 Uri 未重新验收。

本步阅读顺序：BaseApplication → ImageLoadingConfig/createImageLoader → app 的 AppApplication。练习：指出默认配置从哪里产生、宿主应覆盖哪个方法，以及为什么页面不能直接调用 newImageLoader。
