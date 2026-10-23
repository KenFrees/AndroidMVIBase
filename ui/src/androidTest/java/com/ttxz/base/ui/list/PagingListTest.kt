package com.ttxz.base.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.ttxz.base.ui.R
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 仅使用可控内存分页源，不访问业务接口，也不向 app 增加演示页面。 */
class PagingListTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var pagingItems: LazyPagingItems<Int>

    @Test
    fun slowAppendFailureAndRetryKeepExistingRows() {
        val source = ControlledSource()
        source.initial.trySend(page(0, 1))
        showPagingList(source)
        compose.waitUntil(10_000) { pagingItems.itemCount == 5 }
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.waitUntil(10_000) { source.appendCalls.get() == 1 }
        // 预取触发后尾部才加入列表，继续滚到底部再核对实际可见状态。
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.onNodeWithText(text(R.string.ui_list_loading)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(5, pagingItems.itemCount) }

        source.append.trySend(PagingSource.LoadResult.Error(IllegalStateException("test failure")))
        compose.waitUntil(10_000) { pagingItems.loadState.append is androidx.paging.LoadState.Error }
        compose.onNodeWithText(text(R.string.ui_list_load_failed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(5, pagingItems.itemCount) }

        source.append.trySend(page(5, null))
        compose.onNodeWithText(text(R.string.ui_list_retry)).performClick()
        compose.waitUntil(10_000) { pagingItems.itemCount == 10 }
        compose.onNode(hasScrollAction()).performScrollToIndex(10)
        compose.onNodeWithText(text(R.string.ui_list_no_more)).assertIsDisplayed()
        assertEquals(2, source.appendCalls.get())
    }

    @Test
    fun initialFailureCanRetryIntoSuccessfulEmptyPage() {
        val source = ControlledSource()
        showPagingList(source)
        compose.onNodeWithText(text(R.string.ui_list_loading)).assertIsDisplayed()
        source.initial.trySend(PagingSource.LoadResult.Error(IllegalStateException("test failure")))
        compose.waitUntil(10_000) { pagingItems.loadState.refresh is androidx.paging.LoadState.Error }
        compose.onNodeWithText(text(R.string.ui_list_load_failed)).assertIsDisplayed()
        source.initial.trySend(PagingSource.LoadResult.Page(emptyList(), null, null))
        compose.onNodeWithText(text(R.string.ui_list_retry)).performClick()
        compose.waitUntil(10_000) { source.initialCalls.get() == 2 &&
            pagingItems.loadState.refresh is androidx.paging.LoadState.NotLoading }
        compose.onNodeWithText(text(R.string.ui_list_empty)).assertIsDisplayed()
    }

    @Test
    fun emptyListSupportsPullToRefreshAndGuardsRepeatedCallbacks() {
        var refreshing by mutableStateOf(false)
        val refreshCalls = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                RefreshList(
                    isRefreshing = refreshing,
                    onRefresh = { refreshCalls.incrementAndGet(); refreshing = true },
                    contentState = ListContentState.Empty,
                    modifier = Modifier.height(400.dp).testTag("refresh"),
                ) {}
            }
        }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { refreshCalls.get() == 1 }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshCalls.get()) }
    }

    @Test
    fun refreshFailureKeepsPreviousRowsAndRetriesNewGeneration() {
        val first = ControlledSource().apply { initial.trySend(page(0, null)) }
        val refreshed = ControlledSource().apply {
            initial.trySend(PagingSource.LoadResult.Error(IllegalStateException("refresh failure")))
        }
        val generations = AtomicInteger()
        showPagingList { if (generations.getAndIncrement() == 0) first else refreshed }
        compose.waitUntil(10_000) { pagingItems.itemCount == 5 }
        compose.runOnIdle { pagingItems.refresh() }
        compose.waitUntil(10_000) { pagingItems.loadState.refresh is androidx.paging.LoadState.Error }
        compose.runOnIdle { assertEquals((0 until 5).toList(), pagingItems.itemSnapshotList.items) }
        compose.onNodeWithText(text(R.string.ui_list_refresh_failed)).assertIsDisplayed()
        refreshed.initial.trySend(page(20, null))
        compose.onNodeWithText(text(R.string.ui_list_retry)).performClick()
        compose.waitUntil(10_000) { pagingItems.itemSnapshotList.items.firstOrNull() == 20 }
    }

    private fun showPagingList(source: ControlledSource) {
        showPagingList { source }
    }

    private fun showPagingList(sourceFactory: () -> ControlledSource) {
        val flow = Pager(
            PagingConfig(pageSize = 5, initialLoadSize = 5, prefetchDistance = 1, enablePlaceholders = false),
        ) { sourceFactory() }.flow
        compose.setContent {
            pagingItems = flow.collectAsLazyPagingItems()
            MaterialTheme {
                PagingList(
                    items = pagingItems,
                    itemKey = { it },
                    modifier = Modifier.height(240.dp),
                ) { value ->
                    Box(Modifier.fillMaxWidth().height(72.dp)) { Text("Item $value") }
                }
            }
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun page(start: Int, next: Int?): PagingSource.LoadResult.Page<Int, Int> =
        PagingSource.LoadResult.Page((start until start + 5).toList(), null, next)

    private class ControlledSource : PagingSource<Int, Int>() {
        val initial = Channel<LoadResult<Int, Int>>(Channel.UNLIMITED)
        val append = Channel<LoadResult<Int, Int>>(Channel.UNLIMITED)
        val initialCalls = AtomicInteger()
        val appendCalls = AtomicInteger()

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> =
            if (params.key == null) {
                initialCalls.incrementAndGet()
                initial.receive()
            } else {
                appendCalls.incrementAndGet()
                append.receive()
            }

        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
    }
}
