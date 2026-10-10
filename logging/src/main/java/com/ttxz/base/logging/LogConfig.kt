package com.ttxz.base.logging

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日志严重程度，仅用于过滤，不插入固定文件前缀。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 日志配置输入；未传参数使用默认值，构造时不因配置值非法抛异常。
 * Logging 初始化时检查：日期格式非法回退默认；其他安全约束非法停用日志并提供警告。
 * 目录由 Logging 初始化入口选择，不依赖存储权限。
 * @param directoryName getExternalFilesDir(null) 下的直接子目录名，默认 logs；允许 1–64 个英文字母、数字、下划线或短横线，不能传完整路径。
 * @param datePattern 日期时间格式，默认精确到毫秒；仅允许日期时间字段和指定分隔符，不能改变整行结构。
 * @param zoneId 格式化、按日分片和日历保留使用的时区，默认取构造配置时的系统时区，之后不自动变化。
 * @param fileMinLevel 文件输出最低等级，默认 INFO；与 Logcat 过滤独立。
 * @param logcatMinLevel Logcat 最低等级，默认 DEBUG，null 关闭；不影响文件输出。
 * @param queueCapacity 同时容纳的记录数，默认 256，必须大于 0，包含准备、排队和处理中记录；满时拒绝新记录。
 * @param discardingThreshold 剩余槽数小于阈值时拒绝 DEBUG/INFO；null 使用 queueCapacity / 5，0 禁用提前丢弃。
 * 显式值范围为 0–queueCapacity；队列满时所有等级都拒绝且不等待空间。
 * @param maxFileBytes 单文件滚动阈值，单位字节，默认 2 MiB，必须大于 0；完整记录可使文件超限，下一条记录前滚动。
 * @param maxDirectoryBytes 日志文件总字节预算，默认 20 MiB，必须大于 0 且不少于单文件；不含未知文件。
 * 活动文件受保护；单条编码后超过总预算或无法清理出空间时报告文件写入失败，不截断正文。
 * @param retentionDays 保留最近多少个日历日，默认 7，必须大于 0；按 zoneId 计算，保护当前活动文件。
 * @param flushIntervalMillis 缓冲刷新间隔，单位毫秒，默认 1000，必须大于 0；不做 fsync，骤停仍可能丢失日志。
 * @param isDebugLog 是否输出日志库自身各等级诊断到 Logcat，默认 false；独立于业务输出等级，不记录业务正文。
 * @param redact 后台正文脱敏函数，默认原样返回；正文不截断，输出分块转义和编码，不处理日期和 TAG。
 * 不得阻塞、递归管理日志或生成巨大字符串；可预期异常导致该条丢弃，不输出未脱敏正文。
 */
data class LogConfig(
    val directoryName: String = "logs",
    val datePattern: String = "yyyy-MM-dd HH:mm:ss.SSS",
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val fileMinLevel: LogLevel = LogLevel.INFO,
    val logcatMinLevel: LogLevel? = LogLevel.DEBUG,
    val queueCapacity: Int = 256,
    val discardingThreshold: Int? = null,
    val maxFileBytes: Long = 2L * 1024 * 1024,
    val maxDirectoryBytes: Long = 20L * 1024 * 1024,
    val retentionDays: Int = 7,
    val flushIntervalMillis: Long = 1000,
    val isDebugLog: Boolean = false,
    val redact: (String) -> String = { it },
) {
    /**
     * 检查配置并返回问题标识；空列表表示通过，不修改配置，也不通过 require 抛出校验异常。
     * 初始化时仅 datePattern 问题回退默认格式；存在其他问题则停用日志，避免擅自扩大资源预算。
     */
    fun validationWarnings(): List<String> = buildList {
        // 白名单限制前缀内容；实际格式化检查字段组合及重复次数是否合法。
        val validDate = datePattern.length in 1..80 && datePattern.all {
            it in "yMdHmsSXx -/:.,+"
        } && listOf('y', 'M', 'd', 'H').all { datePattern.contains(it) } && runCatching {
            DateTimeFormatter.ofPattern(datePattern, Locale.ROOT).withZone(zoneId).format(java.time.Instant.EPOCH)
        }.isSuccess
        if (!directoryName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) add("directoryName")
        if (discardingThreshold != null && discardingThreshold !in 0..queueCapacity.coerceAtLeast(0)) add("discardingThreshold")
        if (!validDate) add("datePattern")
        if (queueCapacity <= 0) add("queueCapacity")
        if (maxFileBytes <= 0) add("maxFileBytes")
        if (maxDirectoryBytes < maxFileBytes || maxDirectoryBytes <= 0) add("maxDirectoryBytes")
        if (retentionDays <= 0) add("retentionDays")
        if (flushIntervalMillis <= 0) add("flushIntervalMillis")
    }

}

/**
 * 独立读取的累计计数，非事务快照；进程重启后重新计数。
 * @param accepted 接受的记录数，尚未保证文件输出成功。
 * @param dropped 未启用、关闭、容量或准备失败等拒绝数；等级过滤不计。
 * @param truncated 已接受记录的截断数，仅涵盖 TAG 和有界异常快照，普通正文不截断，每条最多一次。
 * @param written 追加到文件缓冲成功数，不含仅 Logcat 输出；不代表刷新或 fsync。
 * @param failed 失败操作和跳过文件记录数，包含回调、磁盘、清理、退避等；非独立失败日志条数。
 * @param lastError 最近报告的错误类型，不保存异常对象或敏感原文；恢复成功不清除历史错误。
 */
data class LogStatus(
    val accepted: Long,
    val dropped: Long,
    val truncated: Long,
    val written: Long,
    val failed: Long,
    val lastError: String?,
)

/** 刷新/关闭结果；成功不代表之前丢弃或失败的记录可以恢复，也不代表 fsync。 */
data class FlushResult(val isSuccessful: Boolean, val status: LogStatus)
