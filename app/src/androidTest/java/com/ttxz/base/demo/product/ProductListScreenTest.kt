package com.ttxz.base.demo.product

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttxz.base.data.remote.ProductApi
import com.ttxz.base.data.remote.ProductDto
import com.ttxz.base.data.remote.ProductPageDto
import com.ttxz.base.ui.theme.AndroidMVIBaseTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用内存接口验证组合重建的位置恢复及用户切换模式回顶，不依赖外网或旋转设置。 */
@RunWith(AndroidJUnit4::class)
class ProductListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val store = ViewModelStore()

    @After
    fun clearViewModel() {
        compose.runOnIdle { store.clear() }
    }

    @Test
    fun savedScrollPositionSurvivesRecompositionRestoreButModeSwitchReturnsToTop() {
        val api = InMemoryApi()
        val viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProductListViewModel(ProductRepository(api)) as T
        })[ProductListViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AndroidMVIBaseTheme { ProductListScreen(onBack = {}, viewModel = viewModel) }
        }
        compose.onNodeWithText("Product 1").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(5)
        compose.waitForIdle()
        val before = scrollPosition()
        assertTrue(before >= 5f)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(before, scrollPosition(), 0.01f)

        compose.onNode(isToggleable()).performClick()
        compose.waitUntil(10_000) { 1500 in api.delays }
        compose.waitForIdle()
        assertEquals(0f, scrollPosition(), 0.01f)
        compose.onNodeWithText("Product 1").assertExists()
    }

    private fun scrollPosition(): Float = compose.onNode(hasScrollAction())
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private class InMemoryApi : ProductApi {
        val delays = CopyOnWriteArrayList<Int>()

        override suspend fun getProducts(limit: Int, skip: Int, delayMillis: Int, fields: String): ProductPageDto {
            delays += delayMillis
            val rows = (skip until minOf(skip + limit, 50)).map {
                ProductDto(id = it + 1, title = "Product ${it + 1}", price = 9.99)
            }
            return ProductPageDto(products = rows, total = 50, skip = skip, limit = rows.size)
        }
    }
}
