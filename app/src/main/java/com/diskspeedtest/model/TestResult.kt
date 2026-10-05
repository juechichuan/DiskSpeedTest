package com.diskspeedtest.model

/**
 * 读写速度测试结果
 */
data class TestResult(
    /** 写入速度 (MB/s) */
    val writeSpeedMBps: Double,
    /** 读取速度 (MB/s) */
    val readSpeedMBps: Double,
    /** 写入耗时 (毫秒) */
    val writeTimeMs: Long,
    /** 读取耗时 (毫秒) */
    val readTimeMs: Long,
    /** 测试数据大小 (字节) */
    val testDataSize: Long,
    /** 测试是否成功 */
    val success: Boolean,
    /** 错误信息 */
    val errorMessage: String = "",
    /** 测试时间戳 */
    val timestamp: Long = System.currentTimeMillis()
) {
    val readableWriteSpeed: String get() = String.format("%.2f MB/s", writeSpeedMBps)
    val readableReadSpeed: String get() = String.format("%.2f MB/s", readSpeedMBps)
    val readableDataSize: String get() = StorageInfo.formatSize(testDataSize)
}
