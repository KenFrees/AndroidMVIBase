package com.ttxz.base.demo.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.ttxz.base.R
import com.ttxz.base.data.remote.ProductDto
import com.ttxz.base.ui.list.PagingList
import kotlinx.coroutines.launch

/** DummyJSON 商品列表示例；页面只消费状态与分页数据，不创建网络对象。 */
@Composable
fun ProductListScreen(
    onBack: () -> Unit,
    viewModel: ProductListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val products = viewModel.products.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val slowLabel = stringResource(R.string.product_slow_loading)

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                    Text(stringResource(R.string.product_list_title), style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slowLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = uiState.slowLoading,
                        onCheckedChange = { enabled ->
                            viewModel.onAction(ProductListAction.SetSlowLoading(enabled))
                            // 仅在用户切换模式时回顶；重建页面时保留已恢复的滚动位置。
                            scope.launch { listState.scrollToItem(0) }
                        },
                        modifier = Modifier.semantics { contentDescription = slowLabel },
                    )
                }
                Text(
                    stringResource(R.string.product_list_hint, products.itemSnapshotList.items.size),
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    ) { padding ->
        PagingList(
            items = products,
            modifier = Modifier.fillMaxSize().padding(padding),
            listState = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            itemKey = { it.id },
        ) { product -> ProductCard(product) }
    }
}

@Composable
private fun ProductCard(product: ProductDto) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val placeholder = painterResource(R.drawable.product_placeholder)
            AsyncImage(
                model = product.thumbnail?.takeIf { it.isNotBlank() },
                contentDescription = null,
                placeholder = placeholder,
                error = placeholder,
                fallback = placeholder,
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(product.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.product_price, String.format(locale, "%.2f", product.price)),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.product_id, product.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
