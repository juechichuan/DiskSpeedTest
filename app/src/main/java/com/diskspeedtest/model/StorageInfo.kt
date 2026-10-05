package com.diskspeedtest.model

import android.net.Uri

/**
 * 存储设备信息
 */
data class StorageInfo(
    /** 设备显示名称 */
    val name: String,
    /** 设备根路径（文件路径，可能为空） */
    val path: String,
    /** 总容量（字节） */
    val totalSpace: Long,
    /** 可用空间（字节） */
    val freeSpace: Long,
    /** 已用空间（字节） */
    val usedSpace: Long,
    /** 是否为可移动存储（SD卡/U盘） */
    val isRemovable: Boolean,
    /** 是否为模拟存储（内部存储） */
    val isEmulated: Boolean,
    /** 文件系统类型 */
    val fileSystem: String,
    /** SAF 的 Uri（通过系统文件选择器获得，用于访问外接U盘/SD卡） */
    var uri: Uri? = null,
    /** 设备唯一标识 */
    val uuid: String = ""
) {
    /** 可读容量 */
    val readableTotal: String get() = formatSize(totalSpace)
    val readableFree: String get() = formatSize(freeSpace)
    val readableUsed: String get() = formatSize(usedSpace)

    /** 已用百分比 */
    val usedPercent: Int
        get() = if (totalSpace > 0) ((usedSpace * 100) / totalSpace).toInt() else 0

    /** 设备类型描述 */
    val typeDescription: String
        get() = when {
            isRemovable && isEmulated -> "模拟可移动存储"
            isRemovable -> "可移动存储 (SD卡/U盘)"
            isEmulated -> "内部存储"
            else -> "外部存储"
        }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
            val digit = (Math.log10(bytes.toDouble()) / 3).toInt()
            val index = digit.coerceAtMost(units.size - 1)
            val value = bytes / Math.pow(1024.0, index.toDouble())
            return String.format("%.2f %s", value, units[index])
        }
    }
}
