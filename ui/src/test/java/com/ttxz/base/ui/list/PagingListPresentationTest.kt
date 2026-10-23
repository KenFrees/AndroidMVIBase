package com.ttxz.base.ui.list

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingListPresentationTest {
    @Test
    fun initialRequestShowsFullScreenLoadingWithoutFooter() {
        val value = resolvePagingListPresentation(0, states(refresh = LoadState.Loading))
        assertEquals(ListContentState.Loading, value.content)
        assertFalse(value.isRefreshing)
        assertEquals(LoadMoreState.Idle, value.append)
    }

    @Test
    fun initialFailureIsNotAnEmptyResult() {
        val error = IllegalStateException("first page")
        val value = resolvePagingListPresentation(0, states(refresh = LoadState.Error(error)))
        assertEquals(ListContentState.Error(error), value.content)
        assertNull(value.refreshError)
    }

    @Test
    fun successfulEmptyPageDoesNotShowNoMoreFooter() {
        val value = resolvePagingListPresentation(0, states(append = LoadState.NotLoading(true)))
        assertEquals(ListContentState.Empty, value.content)
        assertEquals(LoadMoreState.Idle, value.append)
    }

    @Test
    fun refreshPreservesRowsAndHidesPreviousGenerationAppendError() {
        val value = resolvePagingListPresentation(
            10,
            states(refresh = LoadState.Loading, append = LoadState.Error(Exception("old page"))),
        )
        assertEquals(ListContentState.Content, value.content)
        assertTrue(value.isRefreshing)
        assertEquals(LoadMoreState.Idle, value.append)
    }

    @Test
    fun refreshFailurePreservesRowsAndHasSeparateRetryFeedback() {
        val error = Exception("refresh")
        val value = resolvePagingListPresentation(10, states(refresh = LoadState.Error(error)))
        assertEquals(ListContentState.Content, value.content)
        assertFalse(value.isRefreshing)
        assertSame(error, value.refreshError)
        assertEquals(LoadMoreState.Idle, value.append)
    }

    @Test
    fun slowAppendShowsLoadingAndKeepsContent() {
        val value = resolvePagingListPresentation(10, states(append = LoadState.Loading))
        assertEquals(ListContentState.Content, value.content)
        assertEquals(LoadMoreState.Loading, value.append)
        assertFalse(value.isRefreshing)
    }

    @Test
    fun appendFailureKeepsOriginalErrorForCustomFooter() {
        val error = Exception("next page")
        val value = resolvePagingListPresentation(10, states(append = LoadState.Error(error)))
        assertEquals(ListContentState.Content, value.content)
        assertEquals(LoadMoreState.Error(error), value.append)
        assertNull(value.refreshError)
    }

    @Test
    fun onlyDeclaredPaginationEndShowsEndFooter() {
        assertEquals(
            LoadMoreState.Idle,
            resolvePagingListPresentation(10, states()).append,
        )
        assertEquals(
            LoadMoreState.End,
            resolvePagingListPresentation(10, states(append = LoadState.NotLoading(true))).append,
        )
    }

    @Test
    fun sourceStillLoadingDoesNotFlashEmptyPage() {
        val value = resolvePagingListPresentation(
            0,
            states(source = LoadStates(LoadState.Loading, idle, idle)),
        )
        assertEquals(ListContentState.Loading, value.content)
    }

    @Test
    fun mediatorRefreshWhileDatabaseIsEmptyShowsLoading() {
        val value = resolvePagingListPresentation(
            0,
            states(mediator = LoadStates(LoadState.Loading, idle, idle)),
        )
        assertEquals(ListContentState.Loading, value.content)
    }

    @Test
    fun mediatorErrorWithCachedRowsDoesNotHideRows() {
        val error = Exception("sync")
        val value = resolvePagingListPresentation(
            10,
            states(mediator = LoadStates(LoadState.Error(error), idle, idle)),
        )
        assertEquals(ListContentState.Content, value.content)
        assertSame(error, value.refreshError)
    }

    private val idle = LoadState.NotLoading(false)

    private fun states(
        refresh: LoadState = idle,
        append: LoadState = idle,
        source: LoadStates = LoadStates(refresh, idle, append),
        mediator: LoadStates? = null,
    ) = CombinedLoadStates(refresh, idle, append, source, mediator)
}
