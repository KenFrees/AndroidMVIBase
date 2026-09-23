package com.ttxz.base.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class NetworkCallTest {
    @Test
    fun cancellationPropagatesToCaller() {
        val cancellation = CancellationException("screen left")

        try {
            runBlocking { safeApiCall<Int> { throw cancellation } }
            fail("CancellationException should propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
