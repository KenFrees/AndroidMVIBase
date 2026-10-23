package com.ttxz.base.demo.product

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.ttxz.base.data.remote.ProductApi
import com.ttxz.base.data.remote.ProductDto
import com.ttxz.base.data.remote.ProductPageDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProductPagingSourceTest {
    @Test
    fun firstLoadUsesRequestedSizeAndReturnsActualNextOffset() = runBlocking {
        val api = FakeApi { limit, skip, delay ->
            assertEquals(30, limit)
            assertEquals(0, skip)
            assertEquals(1500, delay)
            page(skip = skip, count = limit)
        }
        val result = ProductPagingSource(api, 1500).load(refresh(size = 30)) as PagingSource.LoadResult.Page
        assertEquals(30, result.data.size)
        assertEquals(30, result.nextKey)
        assertNull(result.prevKey)
        assertEquals("id,title,price,thumbnail", api.selectedFields)
    }

    @Test
    fun appendKeyIsOffsetRatherThanPageNumber() = runBlocking {
        val api = FakeApi { limit, skip, _ ->
            assertEquals(30, skip)
            page(skip, count = limit)
        }
        val result = ProductPagingSource(api).load(
            PagingSource.LoadParams.Append(key = 30, loadSize = 10, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(40, result.nextKey)
        assertEquals(31, result.data.first().id)
    }

    @Test
    fun partialPageAdvancesByReceivedRowsRatherThanRequestedSize() = runBlocking {
        val api = FakeApi { _, skip, _ -> page(skip, count = 4) }
        val result = ProductPagingSource(api).load(refresh(size = 10)) as PagingSource.LoadResult.Page
        assertEquals(4, result.nextKey)
    }

    @Test
    fun lastPageUsesServerTotalAndDoesNotRequestBeyondEnd() = runBlocking {
        val api = FakeApi { _, skip, _ -> page(skip, count = 4) }
        val result = ProductPagingSource(api).load(
            PagingSource.LoadParams.Append(key = 190, loadSize = 10, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(4, result.data.size)
        assertNull(result.nextKey)
    }

    @Test
    fun emptyResponseEndsPaginationEvenWhenTotalChanged() = runBlocking {
        val api = FakeApi { _, skip, _ -> page(skip, 0).copy(total = 180) }
        val result = ProductPagingSource(api).load(
            PagingSource.LoadParams.Append(key = 190, loadSize = 10, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertTrue(result.data.isEmpty())
        assertNull(result.nextKey)
    }

    @Test
    fun initiallyEmptyDatasetWithZeroLimitEndsPagination() = runBlocking {
        val result = ProductPagingSource(FakeApi { _, skip, _ -> page(skip, 0).copy(total = 0) })
            .load(refresh()) as PagingSource.LoadResult.Page
        assertTrue(result.data.isEmpty())
        assertNull(result.nextKey)
    }

    @Test
    fun nonEmptyResponseWithZeroLimitIsRejected() = runBlocking {
        val result = ProductPagingSource(FakeApi { _, skip, _ -> page(skip, 1).copy(limit = 0) })
            .load(refresh()) as PagingSource.LoadResult.Error
        assertTrue(result.throwable is IllegalStateException)
    }

    @Test
    fun negativeLimitIsRejectedForEmptyAndNonEmptyResponses() = runBlocking {
        for (count in listOf(0, 1)) {
            val result = ProductPagingSource(FakeApi { _, skip, _ -> page(skip, count).copy(limit = -1) })
                .load(refresh()) as PagingSource.LoadResult.Error
            assertTrue(result.throwable is IllegalStateException)
        }
    }

    @Test
    fun networkFailureBecomesPagingErrorWithOriginalCause() = runBlocking {
        val error = IOException("test network failure")
        val result = ProductPagingSource(FakeApi { _, _, _ -> throw error })
            .load(refresh()) as PagingSource.LoadResult.Error
        assertSame(error, result.throwable)
    }

    @Test
    fun cancellationIsNotConvertedToRetryError() = runBlocking {
        val cancelled = CancellationException("page left")
        try {
            ProductPagingSource(FakeApi { _, _, _ -> throw cancelled }).load(refresh())
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test
    fun unexpectedOffsetIsRejectedInsteadOfAppendingWrongRows() = runBlocking {
        val result = ProductPagingSource(FakeApi { _, _, _ -> page(skip = 99, count = 10) })
            .load(refresh()) as PagingSource.LoadResult.Error
        assertTrue(result.throwable is IllegalStateException)
    }

    @Test
    fun refreshAlwaysStartsAtFirstPageRatherThanOldAnchor() {
        val source = ProductPagingSource(FakeApi { _, skip, _ -> page(skip, 10) })
        val previous = PagingSource.LoadResult.Page<Int, ProductDto>(page(20, 10).products, null, 30)
        assertNull(source.getRefreshKey(PagingState(listOf(previous), 25, PagingConfig(10), 0)))
    }

    private fun refresh(size: Int = 10) = PagingSource.LoadParams.Refresh<Int>(
        key = null,
        loadSize = size,
        placeholdersEnabled = false,
    )

    private fun page(skip: Int, count: Int) = ProductPageDto(
        products = (skip until skip + count).map { ProductDto(it + 1, "Product ${it + 1}", 9.99) },
        total = 194,
        skip = skip,
        limit = count,
    )

    private class FakeApi(
        val response: suspend (limit: Int, skip: Int, delay: Int) -> ProductPageDto,
    ) : ProductApi {
        var selectedFields: String? = null

        override suspend fun getProducts(limit: Int, skip: Int, delayMillis: Int, fields: String): ProductPageDto {
            selectedFields = fields
            return response(limit, skip, delayMillis)
        }
    }
}
