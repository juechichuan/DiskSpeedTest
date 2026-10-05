package com.diskspeedtest.model

/**
 * 真实容量检测结果
 *
 * 用于检测 U 盘 / SD 卡是否为扩容盘（虚假容量）
 */
data class CapacityTestResult(
    /** 标称容量（系统报告的总容量，字节） */
    val advertisedCapacity: Long,
    /** 实际可正常写入的容量（字节） */
    val realCapacity: Long,
    /** 实际可正常写入的可用空间（字节） */
    val realFreeSpace: Long,
    /** 是否为扩容盘（真实容量远小于标称容量） */
    val isFakeCapacity: Boolean,
    /** 容量缩水百分比（0~100），如 50 表示真实容量只有标称的一半 */
    val shrinkPercent: Int,
    /** 检测是否成功 */
    val success: Boolean,
    /** 错误信息 */
    val errorMessage: String = "",
    /** 检测耗时（毫秒） */
    val elapsedMs: Long = 0,
    /** 检测的数据块大小（字节） */
    val blockSize: Long = 0,
    /** 成功验证的数据块数量 */
    val verifiedBlocks: Int = 0,
    /** 检测到损坏的第一个块位置（字节偏移），-1 表示未发现损坏 */
    val firstCorruptedOffset: Long = -1
) {
    val readableAdvertised: String get() = StorageInfo.formatSize(advertisedCapacity)
    val readableReal: String get() = StorageInfo.formatSize(realCapacity)
    val readableRealFree: String get() = StorageInfo.formatSize(realFreeSpace)

    /** 检测结论描述 */
    val conclusion: String
        get() = when {
            !success -> "检测失败"
            isFakeCapacity -> "疑似扩容盘！标称 $readableAdvertised，实际仅 $readableReal"
            shrinkPercent > 5 -> "容量略有缩水（${shrinkPercent}%），可能为正常损耗"
            else -> "容量正常，与标称一致"
        }
}
