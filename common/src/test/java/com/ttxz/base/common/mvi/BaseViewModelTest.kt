package com.ttxz.base.common.mvi

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseViewModelTest {
    @Test
    fun initialStateAndBusinessUpdatesAreExposed() {
        val viewModel = TestViewModel()
        assertEquals(TestState(), viewModel.uiState.value)

        viewModel.onAction(TestAction)
        assertEquals(TestState(1), viewModel.uiState.value)

        viewModel.replace(TestState(8))
        assertEquals(TestState(8), viewModel.uiState.value)
    }

    @Test
    fun concurrentReducersDoNotOverwriteEachOther() {
        val viewModel = TestViewModel()
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val updates = List(2) {
                executor.submit {
                    var firstEvaluation = true
                    viewModel.change {
                        // 测试调度钩子：让两个线程首次都读到旧状态，重试时不再等待。
                        if (firstEvaluation) {
                            firstEvaluation = false
                            barrier.await(5, TimeUnit.SECONDS)
                        }
                        copy(count = count + 1)
                    }
                }
            }
            updates.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(TestState(2), viewModel.uiState.value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun effectsWithoutSubscribersAreNotReplayed() = runBlocking {
        withTimeout(5_000) {
            val viewModel = TestViewModel()
            viewModel.publish(TestEffect(1))

            val next = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
            assertFalse(next.isCompleted)
            viewModel.publish(TestEffect(2))
            assertEquals(TestEffect(2), next.await())
        }
    }

    @Test
    fun slowSubscriberAppliesBackpressureWithoutDroppingEffects() = runBlocking {
        withTimeout(5_000) {
            val viewModel = TestViewModel()
            val received = mutableListOf<TestEffect>()
            val firstReceived = CompletableDeferred<Unit>()
            val resumeConsumer = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.effects.take(3).collect {
                    received += it
                    if (received.size == 1) {
                        firstReceived.complete(Unit)
                        resumeConsumer.await()
                    }
                }
            }

            viewModel.publish(TestEffect(1))
            firstReceived.await()
            viewModel.publish(TestEffect(2))
            val third = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.publish(TestEffect(3))
            }
            assertFalse(third.isCompleted)

            resumeConsumer.complete(Unit)
            third.await()
            collector.join()
            assertEquals(listOf(TestEffect(1), TestEffect(2), TestEffect(3)), received)
        }
    }

    @Test
    fun suspendedEffectSendPropagatesCancellation() = runBlocking {
        withTimeout(5_000) {
            val viewModel = TestViewModel()
            val received = mutableListOf<TestEffect>()
            val firstReceived = CompletableDeferred<Unit>()
            val resumeConsumer = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.effects.take(3).collect {
                    received += it
                    if (received.size == 1) {
                        firstReceived.complete(Unit)
                        resumeConsumer.await()
                    }
                }
            }

            viewModel.publish(TestEffect(1))
            firstReceived.await()
            viewModel.publish(TestEffect(2))
            var returnedNormally = false
            val cancelledSend = launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.publish(TestEffect(3))
                returnedNormally = true
            }
            assertFalse(cancelledSend.isCompleted)
            cancelledSend.cancelAndJoin()
            assertTrue(cancelledSend.isCancelled)
            assertFalse(returnedNormally)

            resumeConsumer.complete(Unit)
            viewModel.publish(TestEffect(4))
            collector.join()
            assertEquals(listOf(TestEffect(1), TestEffect(2), TestEffect(4)), received)
        }
    }

    @Test
    fun effectsAreBroadcastToEachActiveSubscriber() = runBlocking {
        withTimeout(5_000) {
            val viewModel = TestViewModel()
            val first = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { viewModel.effects.first() }

            viewModel.publish(TestEffect(1))
            assertEquals(TestEffect(1), first.await())
            assertEquals(TestEffect(1), second.await())
        }
    }

    private data object TestAction : UiAction
    private data class TestState(val count: Int = 0) : UiState
    private data class TestEffect(val number: Int) : UiEffect

    private class TestViewModel : BaseViewModel<TestAction, TestState, TestEffect>(TestState()) {
        override fun onAction(action: TestAction) {
            change { copy(count = count + 1) }
        }

        fun replace(state: TestState) = updateUiState(state)
        fun change(reducer: TestState.() -> TestState) = updateUiState(reducer)
        suspend fun publish(effect: TestEffect) = sendEffect(effect)
    }
}
