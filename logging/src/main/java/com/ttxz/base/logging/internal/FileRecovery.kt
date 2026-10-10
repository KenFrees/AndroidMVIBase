package com.ttxz.base.logging.internal

/** 单消费者的磁盘重试退避；仅实际文件刷新成功才清除连续失败状态。 */
internal class FileRecovery(private val nanoTime: () -> Long = System::nanoTime) {
    private var waiting = false
    private var retryAt = 0L
    private var nextDelayMillis = 1000L

    fun isWaiting(): Boolean = waiting && nanoTime() - retryAt < 0

    fun failed() {
        retryAt = nanoTime() + nextDelayMillis * 1_000_000
        nextDelayMillis = minOf(nextDelayMillis * 2, 60_000)
        waiting = true
    }

    fun recovered() {
        waiting = false
        nextDelayMillis = 1000L
    }
}
