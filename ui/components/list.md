# 列表、下拉刷新与自动分页

`ui.list` 统一列表状态、刷新交互与加载尾部。使用官方 Material 3 下拉刷新和 Paging 3.5.3 自动追加，没有引入第三方刷新库。返回 [UI 组件目录](README.md)。

接入顺序：[最小分页调用](#最小分页调用) → [分页协议与数据入口](#分页协议与数据入口) → [自定义样式示例](#自定义样式示例)。无分页需求看 [普通列表用法](#普通列表用法)，单独使用状态组件看 [独立状态与尾部示例](#独立状态与尾部示例)。

## 支持范围

| 能力 | 当前支持与默认行为 |
| --- | --- |
| 普通纵向列表 | RefreshList 保留原生 LazyListScope，可设置 items、稳定 key、contentType、头尾内容、间距、内边距与 LazyListState |
| 首屏状态 | ListStateLayout 提供加载中、成功空集、失败重试及正常内容；默认中文，英文资源已提供 |
| 下拉刷新 | Material 3 手势与指示器；空数据及首屏错误时也能下拉；首屏加载和刷新中忽略重复手势回调 |
| 自动追加 | PagingList 访问可见条目时交给 Paging 按需加载；数据层 PagingConfig 决定 pageSize、initialLoadSize 与 prefetchDistance |
| 慢网与追加失败 | 底部显示转圈和“正在加载”；失败后保留已有条目，提供重试按钮 |
| 刷新失败 | 有数据时保留列表，在列表上方固定显示刷新失败及重试；无数据时显示整页错误 |
| 分页结束 | 使用 Paging 的 endOfPaginationReached；默认显示“没有更多了”，可关闭 |
| 样式替换 | 首屏加载、空状态、错误页、刷新指示器、刷新失败提示、追加尾部、null 占位条目均提供槽位 |
| 滚动控制 | 调用方可以传 LazyListState；组件不自动回到顶部，不主动清除滚动位置 |

首版面向从第一页向后追加的纵向列表。没有封装网格、双向分页、严格“上拉松手才加载”、离线数据库、条目选择、删除、业务认证或分页接口。页码/cursor、业务失败码及何时结束分页由数据层决定。自动预取可能使用户看不到加载动画；下一页仍在请求时，滑到底部会看到加载尾部。

## 依赖与接入

同一 Gradle 工程声明：

```kotlin
dependencies {
    implementation(project(":ui"))
}
```

由于公开 API 暴露 LazyListScope/LazyListState、Material PullToRefreshState 和 LazyPagingItems，ui 通过 api 导出 Compose BOM、Foundation、Material 3 和 Paging Compose。消费者不需要重复声明 Paging Compose；宿主仍需启用 Compose 编译插件，数据层的 ViewModel/Repository 依赖由宿主提供。

## 最小分页调用

以下是可参考的通用接入写法，不是具体业务文件。真实 app 接入见 [DummyJSON 商品列表示例](../../app/product-list.md) 和 [ProductListScreen.kt](../../app/src/main/java/com/ttxz/base/demo/product/ProductListScreen.kt)。数据流由宿主提供，库不预设服务地址或 DTO。

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.ttxz.base.ui.list.PagingList
import kotlinx.coroutines.flow.Flow

@Composable
fun <T : Any> PagedListExample(
    data: Flow<PagingData<T>>,
    itemKey: (T) -> Any,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val items = data.collectAsLazyPagingItems()
    PagingList(
        items = items,
        modifier = Modifier.fillMaxSize(),
        itemKey = itemKey,
        itemContent = itemContent,
    )
}
```

调用方提供条目布局和点击行为；组件没有 onItemClick 代理，条目中直接使用自己的点击回调。itemKey 应稳定且唯一，例如业务 ID，不用列表下标代替可变化数据的身份。

宿主在 ViewModel/Repository 持有稳定的 Pager 数据流，按需使用 `cachedIn(viewModelScope)`，不要在每次重组时新建 Pager。筛选条件变化应由宿主管理新数据流和旧请求取消；UiAction/筛选条件可沿用现有 MVI，分页展示状态直接来自 Paging，不额外复制一份列表和分页标志。

## 普通列表用法

无分页需求时不需要创建 Pager。以下为参考类型和页面函数：

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ttxz.base.ui.list.ListContentState
import com.ttxz.base.ui.list.RefreshList

data class ListRow(val id: Long, val title: String)

@Composable
fun SimpleListExample(
    rows: List<ListRow>,
    contentState: ListContentState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    RefreshList(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        contentState = contentState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows, key = { it.id }) { row -> Text(row.title) }
    }
}
```

contentState 由普通列表宿主提供：首次请求时用 Loading，成功空集用 Empty，无数据失败用 Error，有数据用 Content。不要仅凭 rows.isEmpty() 把“还未加载”或“请求失败”当成空数据。宿主收到 onRefresh 后及时设置刷新状态，请求结束、失败或取消时结束对应状态。

RefreshList 自身不触发加载下一页；自动分页使用 PagingList。单独使用 ListStateLayout 和 LoadMoreFooter 也不会请求数据，只在用户点击时调用提供的重试回调。

## 分页协议与数据入口

下表区分 Paging 官方 API、本库组件和宿主代码的职责。业务继承官方 PagingSource 实现自己的分页源；公共 ui 不读取 HTTP 参数或响应字段：

| 部分 | 来源 | 负责什么 |
| --- | --- | --- |
| Repository | 宿主自行实现的类，如 ProductRepository | 对外提供稳定的分页流，配置 Pager 并创建每代新的 PagingSource |
| PagingSource.load | 官方定义契约，宿主重写实现 | 取得一批数据，解释业务错误、计算下一次 key，并返回 Page 或 Error |
| Pager / Paging | Paging 官方库 | 调度加载、组织已加载的页；下一次 load 的 params.key 来自上一次的 nextKey |
| collectAsLazyPagingItems | Paging 官方 Compose 扩展 | 在 Composition 中收集分页流，提供条目、加载状态、refresh 和 retry |
| PagingList | 本库 ui 模块封装的 Composable | 按下标访问条目、委托预取，显示加载状态及调用者的条目布局 |

Repository 是数据层入口；具体网络调用可以委托 PagingSource，当前商品示例正是这种方式。普通单次请求也可以直接在 Repository 中调用 API。两种方式都不让页面或 ViewModel 直接持有 API。

追加时，单次接口返回当前这一批，Paging 将它接到已加载数据后面，列表访问的是连续下标。新的一批通常不会让外层 Flow 发出全新的 PagingData；分页事件在当前一代 PagingData 内传递。refresh 会建立新一代。已加载数据的保留数量由 PagingConfig.maxSize 决定，当前商品示例沿用默认的不丢页配置。

分页 key 与 itemKey 不同：前者定位下一批，后者标识某个条目。接口参数叫什么，由业务 Retrofit 的 @Query / @Body 声明；例如 `@Query("pageNum") pageNo: Int` 会把 Kotlin 参数 pageNo 发送为 pageNum。

### 偏移量分页

真实实现见 app 的 [ProductPagingSource](../../app/src/main/java/com/ttxz/base/demo/product/ProductPagingSource.kt)，规则是：

```kotlin
val offset = params.key ?: 0
// 用 offset 和 params.loadSize 请求当前批次。
// 成功后：nextOffset = offset + 本次实际返回条数。
// 返回空批或达到 total 时 nextKey = null，否则 nextKey = nextOffset。
```

因此首次请求 30 条、后续请求 10 条时，偏移量仍按实际收到的数量推进。DummyJSON 的响应 limit 是实际返回条数，合法空批会是 0；不要把它与请求 limit 混为一谈。

### 页码与游标分页参考

下面是接入方可参考的完整分页源写法，沿用前面的 ListRow。NumberPage、CursorPage 仅为文档定义的响应契约，不是公共库 DTO，也不是 DummyJSON 接口。loadPage 回调由宿主接上自己的 API；也可以像真实商品示例那样直接注入 API。

```kotlin
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

data class NumberPage(val rows: List<ListRow>, val hasMore: Boolean)
data class CursorPage(val rows: List<ListRow>, val nextCursor: String?)

class NumberRowsSource(
    private val loadPage: suspend (pageNo: Int, pageSize: Int) -> NumberPage,
) : PagingSource<Int, ListRow>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ListRow> = try {
        val pageNo = params.key ?: 1
        // 页码协议固定每页 10 条，保持 pageNo 的含义一致。
        val page = loadPage(pageNo, 10)
        LoadResult.Page(
            data = page.rows,
            prevKey = null,
            nextKey = if (page.hasMore) pageNo + 1 else null,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<Int, ListRow>): Int? = null
}

class CursorRowsSource(
    private val loadPage: suspend (cursor: String?, size: Int) -> CursorPage,
) : PagingSource<String, ListRow>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, ListRow> = try {
        val page = loadPage(params.key, params.loadSize)
        LoadResult.Page(
            data = page.rows,
            prevKey = null,
            nextKey = page.nextCursor,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<String, ListRow>): String? = null
}
```

这些参考契约约定：页码从 1 开始；hasMore=false 或 nextCursor=null 表示结束；空的首次响应也应声明结束。若后端允许首批为空但仍有后续页，数据层需继续取得首批可展示数据或明确的结束结果，再交给列表。不要直接套用“少于 pageSize 就是最后一页”；业务失败码也要在 loadPage 中检查并转为可处理的失败。

页码协议尤其要注意：初始请求 30 条、之后每页 10 条时，直接把页码加一会重复或遗漏。上例固定请求 10 条，即使 params.loadSize 不同也保持这个大小；Paging 的 loadSize 是期望数量，分页源可以返回较少的数据。游标是否允许改变批次大小，则按服务端契约决定。

### Repository 与 ViewModel 接入

继续使用上面的页码参考类型。RowsApi 由接入方实现或映射成 Retrofit 接口；参数名和响应字段不由 ui 规定：

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow

interface RowsApi {
    suspend fun rows(pageNo: Int, pageSize: Int): NumberPage
}

class RowsRepository(private val api: RowsApi) {
    fun rows(): Flow<PagingData<ListRow>> = Pager(
        config = PagingConfig(
            pageSize = 10,
            initialLoadSize = 10,
            prefetchDistance = 2,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { NumberRowsSource { page, size -> api.rows(page, size) } },
    ).flow
}

class RowsViewModel(repository: RowsRepository) : ViewModel() {
    val rows = repository.rows().cachedIn(viewModelScope)
}
```

页面将 ViewModel 的 rows 传入前面的 PagedListExample 即可；ViewModel 的实例创建或 Hilt 注入按宿主配置。游标接口将工厂替换为 `CursorRowsSource { cursor, size -> /* 调用宿主游标 API */ }`，并由 API 返回 CursorPage。每次工厂调用都创建新实例，不能复用已经失效的 PagingSource。

## 样式配置与状态约定

PagingList 的常用可选参数：

| 参数 | 用途 |
| --- | --- |
| loadingContent / emptyContent | 自定义首屏加载与空数据布局 |
| errorContent | 首屏失败布局，接收 Throwable 与重试回调 |
| refreshErrorContent | 有数据时刷新失败的固定提示，位于滚动列表上方，接收 Throwable 与重试回调 |
| footerContent | 接收 LoadMoreState 与重试回调，替换整个追加尾部 |
| indicator | 接收 isRefreshing 与 PullToRefreshState，替换顶部刷新样式 |
| showEndOfList | false 时隐藏分页结束尾部，不影响加载和重试 |
| header | 正常内容区域的 LazyListScope，可添加多个头部条目 |
| listState / contentPadding / verticalArrangement | 滚动位置、列表边距和条目排列 |
| itemKey / itemContentType | 稳定身份与多类型条目复用 |
| placeholderContent | Paging 返回 null 占位条目的布局，默认 56dp 高；建议按真实条目尺寸替换 |

RefreshList 具有相同的首屏和刷新槽位，另可配置 horizontalAlignment。自定义 indicator 时可通过 state.distanceFraction 观察下拉进度；需要遵循 Material 的指示器定位与进度约定。默认已提供可用样式，无需调用方实现手势。

默认错误文案不直接输出异常 message；业务可在 errorContent / refreshErrorContent / footerContent 中映射用户文案。库不自动弹 Toast、导航、打印异常或处理登录失效。

PagingList 首屏失败及尾部重试调用 items.retry()，下拉刷新调用 items.refresh()。已有数据的刷新失败保留旧条目；刷新期间隐藏旧的追加尾部。End 由数据源确认，组件不按条数推断是否最后一页。

布局需要有界高度，例如填满 Scaffold 内容区或明确设置高度；不要放进同方向、无高度约束的 verticalScroll。header 的自定义 key 应与数据条目的 key 保持唯一。列表不添加二次滚动监听或自行预取，避免与 Paging 的请求管理重复。

网络列表建议 `enablePlaceholders = false`；若启用占位，主体判断使用实际加载条目数，null 条目使用 placeholderContent。库识别 source/mediator 的刷新加载及失败信号，但 Room/RemoteMediator 的完整离线流程不属于本轮验收范围。

collectAsLazyPagingItems 的收集跟随 Composition，组件不声明后台时停止所有请求，也不接管 ViewModel 的协程作用域。页面离开、切换条件、进程重建和滚动恢复策略仍需宿主明确。没有新增 BaseListViewModel 或通用分页 DTO。

## 自定义样式示例

下面沿用 ListRow，自定义空状态、首屏错误、刷新指示器颜色和追加尾部。此处字符串为演示，正式接入使用资源。条目点击直接交给宿主：

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.ttxz.base.ui.list.LoadMoreFooter
import com.ttxz.base.ui.list.PagingList
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyledRows(
    data: Flow<PagingData<ListRow>>,
    onOpen: (ListRow) -> Unit,
) {
    val items = data.collectAsLazyPagingItems()
    PagingList(
        items = items,
        modifier = Modifier.fillMaxSize(),
        itemKey = { it.id },
        emptyContent = { Text("暂无符合条件的内容") },
        errorContent = { _, retry ->
            TextButton(onClick = retry) { Text("加载失败，点此重试") }
        },
        indicator = { refreshing, state ->
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        footerContent = { state, retry ->
            LoadMoreFooter(
                state = state,
                onRetry = retry,
                loadingContent = { Text("正在获取更多内容…") },
                errorContent = { _, retryPage ->
                    TextButton(onClick = retryPage) { Text("下一批加载失败，重试") }
                },
                endContent = { Text("全部内容已展示") },
            )
        },
    ) { row ->
        Text(row.title, modifier = Modifier.clickable { onOpen(row) })
    }
}
```

indicator 必须使用组件传入的 state，与下拉容器保持一致；不在槽位中另外创建刷新状态或发起请求。footerContent 中的 retry 已由 PagingList 绑定到 Paging，直接交给按钮即可。若要对错误做业务文案映射，可使用槽位的 Throwable 参数，但不要直接把异常 message 显示给用户。

## 独立状态与尾部示例

没有刷新需求的内容区，可以只使用 ListStateLayout；下面的 state 和 onRetry 都由宿主提供，Content 分支显示调用者的内容：

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ttxz.base.ui.list.ListContentState
import com.ttxz.base.ui.list.ListStateLayout

@Composable
fun DetailContent(state: ListContentState, onRetry: () -> Unit) {
    ListStateLayout(
        state = state,
        onRetry = onRetry,
        modifier = Modifier.fillMaxSize(),
        emptyContent = { Text("暂无详情") },
    ) {
        Text("这里放已加载的详情内容")
    }
}
```

手动管理分页状态的宿主可以独立使用 LoadMoreFooter，例如放在自己的 LazyColumn 的 `item { ... }` 中：

```kotlin
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ttxz.base.ui.list.LoadMoreFooter
import com.ttxz.base.ui.list.LoadMoreState

@Composable
fun ManualFooter(state: LoadMoreState, onRetry: () -> Unit) {
    LoadMoreFooter(
        state = state,
        onRetry = onRetry,
        endContent = { Text("已经到底了") },
    )
}
```

Idle 隐藏、Loading 显示进度、Error 显示重试、End 显示结束提示。这个组件不做滚动监听，也不维护已有数据；宿主负责何时加载、更新状态和避免重复请求。使用 PagingList 时它已包含默认尾部，无需再添加一个。

## 源码与验证

- [RefreshList.kt](../src/main/java/com/ttxz/base/ui/list/RefreshList.kt)：普通列表、首屏占位和下拉刷新。
- [PagingList.kt](../src/main/java/com/ttxz/base/ui/list/PagingList.kt)：Paging 数据到统一展示的适配。
- [ListStateLayout.kt](../src/main/java/com/ttxz/base/ui/list/ListStateLayout.kt)、[LoadMoreFooter.kt](../src/main/java/com/ttxz/base/ui/list/LoadMoreFooter.kt)：独立可用的主体和尾部。
- [ListStates.kt](../src/main/java/com/ttxz/base/ui/list/ListStates.kt)：展示类型及内部纯状态推导。
- [状态单测](../src/test/java/com/ttxz/base/ui/list/PagingListPresentationTest.kt)：首屏、空集、慢追加、失败、刷新保留旧数据及 source/mediator 信号。
- [Compose 测试](../src/androidTest/java/com/ttxz/base/ui/list/PagingListTest.kt)：可控内存分页源，无真实服务依赖；不作为 app 接口示例。

构建与静态检查：`:ui:assembleDebug :ui:assembleRelease :ui:testDebugUnitTest :ui:lintDebug :ui:assembleDebugAndroidTest`。连接可用 Android 设备后执行 `:ui:connectedDebugAndroidTest`。

2026-10-22 验证：ui 的 Debug/Release AAR 构建、11 项 JVM 状态单测、仪器测试 APK 编译通过；ui lint 为 0 错误、0 警告。DAYU_A6（API 35）通过 4 项 Compose 仪器测试：慢追加显示加载尾部、追加失败保留条目并重试、首屏失败重试到空页面、空列表下拉及重复手势保护、刷新失败保留旧数据并重试新一代数据。多个断言场景组合在同一测试中。

上述测试使用库内可控分页源，不访问真实接口。未验收其他 Android 版本、真实弱网/服务端、完整前后台及进程恢复、双向或 Room 分页、自定义指示器和真实设备的大数据滚动性能。Release 验证为库 AAR 构建，不代表宿主 R8 安装运行通过。

app 已提供 [DummyJSON 商品列表示例](../../app/product-list.md)，包含网络缩略图、每批 10 条、下拉刷新和服务端慢速模式。真实接口与宿主的验证记录见 app 说明，与上面的库内内存源测试分开记录。

2026-10-23 文档补充：分页协议、Repository/ViewModel、自定义刷新与尾部、独立状态组件的参考代码与原有最小用法合计 7 个完整代码块，临时提取到构建目录后使用当前项目依赖和 Compose 编译器验证通过。页码、游标协议为文档假定的契约，不是已接入真实服务；这些示例的自定义样式未做设备外观验收。

练习：解释为什么“首屏失败”和“第二页失败”显示位置不同，再将 showEndOfList 改为 false，判断是否会影响下一页加载。

官方参考：[Compose 列表](https://developer.android.com/develop/ui/compose/lists)、[下拉刷新](https://developer.android.com/develop/ui/compose/components/pull-to-refresh)、[Paging 加载状态](https://developer.android.com/topic/libraries/architecture/paging/load-state)、[Paging 版本](https://developer.android.com/jetpack/androidx/releases/paging)。
