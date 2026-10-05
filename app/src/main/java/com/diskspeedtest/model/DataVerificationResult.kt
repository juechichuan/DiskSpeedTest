package com.diskspeedtest.model

/**
 * 数据验证结果（基于哈希比较）
 *
 * 参考 SD Card Test Pro 的数据验证技术：
 * 写入随机数据时计算哈希，读取时重新计算哈希并比较，
 * 哈希不同说明数据已损坏或卡片为假冒（虚假容量）。
 */
data class DataVerificationResult(
    /** 是否通过验证（写入和读取的哈希一致） */
    val isVerified: Boolean,
    /** 测试的数据大小（字节） */
    val testDataSize: Long,
    /** 写入数据的哈希（MD5） */
    val writeHash: String,
    /** 读取数据的哈希（MD5） */
    val readHash: String,
    /** 写入速度 MB/s */
    val writeSpeedMBps: Double,
    /** 读取速度 MB/s */
    val readSpeedMBps: Double,
    /** 耗时（毫秒） */
    val elapsedMs: Long,
    /** 是否成功 */
    val success: Boolean,
    /** 错误信息 */
    val errorMessage: String = ""
) {
    val readableDataSize: String get() = StorageInfo.formatSize(testDataSize)

    /** 验证结论 */
    val conclusion: String
        get() = when {
            !success -> "验证失败：$errorMessage"
            isVerified -> "数据完整性正常，该区域可安全存储数据"
            else -> "⚠️ 数据验证失败！写入与读取的哈希不一致，卡片可能已损坏或为假冒扩容卡"
        }
}
