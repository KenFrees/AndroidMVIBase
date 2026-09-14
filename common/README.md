# common：公共结果模型

## 职责与边界

保存不属于某个业务功能的公共类型。当前仅提供结果模型，没有工具类大全、MVI 基类或公共 UI。

这是 Android Library，命名空间 `com.ttxz.base.common`，minSdk 26。当前依赖 Coroutines Core；结果模型源码本身没有使用 Android 或协程 API。其他模块依赖 common，common 不反向依赖 app、network。

## 当前能力

入口：[AppResult.kt](src/main/java/com/ttxz/base/common/result/AppResult.kt)。

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

网络层通过该类型将调用结果交给消费者，具体异常转换见 [network](../network/README.md)。后续页面还会拥有自己的 UiState：列表、刷新状态、选中项等不能仅靠 AppResult 完整表达。

## 这一步需要学习什么

1. 阅读 `sealed interface` 与三个实现：为什么调用方可以完整列出所有分支？
2. 阅读 `out T`、`Nothing`：为什么 Error / Loading 可以作为不同数据类型的结果返回？
3. 跟踪 `onSuccess` / `onError`：为什么能链式调用，Loading 时会发生什么？
4. 区分“请求结果”与“整个页面状态”，后续再设计 MVI。

练习：把示例分别改成 `Error("请求失败")` 和 `Loading`，先推测两个回调各会不会执行，再验证。

本步通过标准：能解释三个分支、可空 code 和回调行为，不把 Loading 理解成自动加载机制。

## 验证与未完成项

可用 `:common:assembleDebug` 检查模块构建；学习并修改结果模型时，可针对分支处理、扩展回调行为编写单测。

当前没有结果模型专项单测。尚未定义统一业务错误分类、错误文案本地化、结果转换工具；确有使用场景后再逐步添加，避免提前扩展公共 API。

修改本模块时，同步检查 [network](../network/README.md) 及所有 AppResult 调用者，并更新本说明。
