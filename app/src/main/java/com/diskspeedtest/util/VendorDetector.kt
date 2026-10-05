package com.diskspeedtest.util

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageVolume
import com.diskspeedtest.model.AuthenticityResult
import com.diskspeedtest.model.StorageInfo
import com.diskspeedtest.model.VendorInfo
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 存储设备厂商检测与真伪验证工具
 *
 * 通过读取 /sys/block/ 下的设备信息、SD 卡 CID 等方式获取厂商信息，
 * 并综合容量、速度、厂商等维度判断存储卡真伪。
 */
object VendorDetector {

    /** 已知的 SD 卡厂商 ID 映射（CID 第 1 字节） */
    private val SD_VENDOR_IDS = mapOf(
        0x01 to "Panasonic",
        0x02 to "Toshiba",
        0x03 to "SanDisk",
        0x06 to "Spectek",
        0x09 to "SanDisk",
        0x1B to "Samsung",
        0x1D to "Corsair",
        0x27 to "Phison",
        0x28 to "Lexar",
        0x30 to "Samsung",
        0x31 to "Silicon Power",
        0x33 to "STMicroelectronics",
        0x41 to "Kingston",
        0x74 to "Transcend",
        0x76 to "Patriot",
        0x82 to "Sony",
        0xAD to "SK Hynix"
    )

    /** 常见的山寨/可疑厂商关键字 */
    private val SUSPICIOUS_VENDORS = listOf(
        "generic", "unknown", "chipsbnk", "udisk", "usbest",
        "alcor", "安国", "芯邦", "迈科微", "一芯"
    )

    /**
     * 检测存储设备的厂商信息
     */
    fun detectVendor(context: Context, device: StorageInfo): VendorInfo {
        // 根据路径判断设备类型
        val deviceType = detectDeviceType(device.path)

        // 尝试从 sysfs 获取信息
        val sysfsInfo = readSysfsInfo(device.path, deviceType)

        // 尝试从 StorageVolume 获取描述
        val volumeDesc = getVolumeDescription(context, device.path)

        // 尝试读取 SD 卡 CID
        val cid = if (deviceType == "sd") readSdCid(device.path) else ""
        val cidVendor = parseCidVendor(cid)

        // 组装结果
        return VendorInfo(
            vendor = sysfsInfo.vendor.ifEmpty { cidVendor }.ifEmpty { "未知" },
            model = sysfsInfo.model.ifEmpty { volumeDesc }.ifEmpty { "未知" },
            serial = sysfsInfo.serial.ifEmpty { device.uuid }.ifEmpty { "未知" },
            revision = sysfsInfo.revision.ifEmpty { "未知" },
            cid = cid,
            deviceType = deviceType,
            speedClass = detectSpeedClass(sysfsInfo, cid),
            source = buildString {
                if (sysfsInfo.isNotEmpty()) append("sysfs; ")
                if (cid.isNotEmpty()) append("CID; ")
                if (volumeDesc.isNotEmpty()) append("StorageVolume")
            }.ifEmpty { "无" }
        )
    }

    /**
     * 根据路径判断设备类型
     */
    private fun detectDeviceType(path: String): String {
        return when {
            path.contains("/storage/emulated") -> "internal"
            path.contains("/storage/") && path.matches(Regex("/storage/[0-9A-F]{4}-[0-9A-F]{4}.*")) -> "sd"
            path.contains("/mnt/media_rw") || path.contains("/mnt/usb") -> "usb"
            else -> {
                // 通过检查 sysfs 块设备类型判断
                val blockDev = findBlockDevice(path)
                when {
                    blockDev?.startsWith("mmcblk") == true -> "sd"
                    blockDev?.startsWith("sd") == true -> "usb"
                    else -> "unknown"
                }
            }
        }
    }

    /**
     * 从 sysfs 读取块设备的厂商/型号/序列号信息
     */
    private fun readSysfsInfo(path: String, deviceType: String): SysfsInfo {
        val blockDev = findBlockDevice(path) ?: return SysfsInfo()

        val baseDir = File("/sys/block/$blockDev/device")
        if (!baseDir.exists()) return SysfsInfo()

        val vendor = readFileTrim(File(baseDir, "vendor"))
        val model = readFileTrim(File(baseDir, "model"))
        val serial = readFileTrim(File(baseDir, "serial"))
        val revision = readFileTrim(File(baseDir, "rev"))

        return SysfsInfo(vendor, model, serial, revision)
    }

    /**
     * 根据挂载路径找到对应的块设备名
     */
    private fun findBlockDevice(path: String): String? {
        return try {
            val reader = BufferedReader(FileReader("/proc/mounts"))
            var blockPath: String? = null
            reader.useLines { lines ->
                for (line in lines) {
                    val parts = line.split(" ")
                    if (parts.size >= 2 && path.startsWith(parts[1])) {
                        blockPath = parts[0]
                        break
                    }
                }
            }
            // 从 /dev/block/sda1 或 /dev/mmcblk1p1 中提取设备名
            val devName = blockPath?.substringAfterLast("/") ?: return null
            // 去掉分区号：sda1 -> sda, mmcblk1p1 -> mmcblk1
            return when {
                devName.matches(Regex("mmcblk\\d+p\\d+")) -> devName.substringBeforeLast("p")
                devName.matches(Regex("[a-z]+\\d+")) -> devName.replace(Regex("\\d+$"), "")
                else -> devName
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取文件内容并去除空白字符
     */
    private fun readFileTrim(file: File): String {
        return try {
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 StorageVolume 获取设备描述
     */
    private fun getVolumeDescription(context: Context, path: String): String {
        return try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as
                    android.os.storage.StorageManager
            val volumes = storageManager.storageVolumes
            for (v in volumes) {
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.directory?.absolutePath
                } else null
                if (dir != null && path.startsWith(dir)) {
                    return v.getDescription(context)
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 读取 SD 卡 CID
     */
    private fun readSdCid(path: String): String {
        // CID 通常在 /sys/block/mmcblkX/device/cid
        val blockDev = findBlockDevice(path) ?: return ""
        val cidFile = File("/sys/block/$blockDev/device/cid")
        return try {
            if (cidFile.exists()) cidFile.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 CID 解析厂商名称
     * CID 格式（SD 卡）：
     * - 字节 0: Manufacturer ID
     * - 字节 1-2: OEM/Application ID
     * - 字节 3-7: Product Name (5 ASCII chars)
     * - 字节 8: Product Revision
     * - 字节 9-12: Product Serial Number
     * - 字节 13: Manufacturing Date
     * - 字节 14-15: CRC
     */
    private fun parseCidVendor(cid: String): String {
        if (cid.length < 2) return ""
        return try {
            val manufacturerId = cid.substring(0, 2).toInt(16)
            SD_VENDOR_IDS[manufacturerId] ?: "Vendor_0x${cid.substring(0, 2).uppercase()}"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 从 CID 解析产品名称
     */
    private fun parseCidProductName(cid: String): String {
        if (cid.length < 16) return ""
        return try {
            // 产品名称在字节 3-7，即 CID 字符串的第 6-15 个字符
            val hexPart = cid.substring(6, 16)
            // 每两个十六进制字符对应一个 ASCII 字符
            hexPart.chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
                .trim()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 检测速度等级
     */
    private fun detectSpeedClass(sysfsInfo: SysfsInfo, cid: String): String {
        // SD 卡的速度等级信息通常无法直接从 sysfs 获取
        // 这里基于 CID 中的一些字段做简单推断，或者返回未知
        if (cid.isNotEmpty()) {
            // CID 中没有直接的速度等级信息，速度等级在 SCR 寄存器中
            return "需测速确认"
        }
        return "未知"
    }

    /**
     * 综合验证存储设备真伪
     *
     * @param device 设备信息
     * @param vendorInfo 厂商信息
     * @param realCapacity 真实容量检测结果（可为 null）
     * @param writeSpeedMBps 写入速度 MB/s（可为 null）
     * @param readSpeedMBps 读取速度 MB/s（可为 null）
     */
    fun verifyAuthenticity(
        device: StorageInfo,
        vendorInfo: VendorInfo,
        realCapacity: Long? = null,
        writeSpeedMBps: Double? = null,
        readSpeedMBps: Double? = null
    ): AuthenticityResult {
        val checks = mutableListOf<AuthenticityResult.CheckItem>()
        var score = 100

        // 1. 容量检测
        val advertised = device.totalSpace
        if (realCapacity != null && advertised > 0) {
            val shrinkPercent = ((advertised - realCapacity) * 100.0 / advertised).toInt()
            val passed = shrinkPercent <= 10
            checks.add(
                AuthenticityResult.CheckItem(
                    name = "容量真实性",
                    passed = passed,
                    detail = if (passed) "标称 ${StorageInfo.formatSize(advertised)}，实测 ${StorageInfo.formatSize(realCapacity)}，缩水 ${shrinkPercent}%"
                    else "⚠️ 容量缩水严重！标称 ${StorageInfo.formatSize(advertised)}，实测仅 ${StorageInfo.formatSize(realCapacity)}，缩水 ${shrinkPercent}%"
                )
            )
            if (!passed) score -= 40
        } else {
            checks.add(
                AuthenticityResult.CheckItem(
                    name = "容量真实性",
                    passed = true,
                    detail = "未检测（建议执行真实容量检测）"
                )
            )
        }

        // 2. 厂商信息检测
        val vendorName = vendorInfo.vendor.lowercase()
        val isSuspiciousVendor = SUSPICIOUS_VENDORS.any { vendorName.contains(it) }
        val hasValidVendor = vendorInfo.vendor != "未知" && !isSuspiciousVendor

        checks.add(
            AuthenticityResult.CheckItem(
                name = "厂商信息",
                passed = hasValidVendor,
                detail = if (hasValidVendor) "厂商：${vendorInfo.vendor}，型号：${vendorInfo.model}"
                else if (isSuspiciousVendor) "⚠️ 可疑厂商：${vendorInfo.vendor}"
                else "无法识别厂商信息"
            )
        )
        if (isSuspiciousVendor) score -= 25
        else if (!hasValidVendor) score -= 10

        // 3. 序列号检测
        val hasSerial = vendorInfo.serial != "未知" && vendorInfo.serial.isNotEmpty()
        checks.add(
            AuthenticityResult.CheckItem(
                name = "序列号",
                passed = hasSerial,
                detail = if (hasSerial) "序列号：${vendorInfo.serial}" else "无法获取序列号"
            )
        )
        if (!hasSerial) score -= 10

        // 4. 速度检测
        if (writeSpeedMBps != null && readSpeedMBps != null) {
            // 检查速度是否异常低（可能是假卡）
            val speedOk = writeSpeedMBps >= 2.0 && readSpeedMBps >= 4.0
            checks.add(
                AuthenticityResult.CheckItem(
                    name = "读写速度",
                    passed = speedOk,
                    detail = if (speedOk) "写入 ${String.format("%.1f", writeSpeedMBps)} MB/s，读取 ${String.format("%.1f", readSpeedMBps)} MB/s"
                    else "⚠️ 速度异常偏低：写入 ${String.format("%.1f", writeSpeedMBps)} MB/s，读取 ${String.format("%.1f", readSpeedMBps)} MB/s"
                )
            )
            if (!speedOk) score -= 20
        } else {
            checks.add(
                AuthenticityResult.CheckItem(
                    name = "读写速度",
                    passed = true,
                    detail = "未检测（建议先执行速度测试）"
                )
            )
        }

        // 5. 文件系统检测
        val validFs = device.fileSystem in listOf("vfat", "exfat", "ext4", "f2fs", "fat32", "ntfs")
        checks.add(
            AuthenticityResult.CheckItem(
                name = "文件系统",
                passed = validFs || device.fileSystem == "unknown",
                detail = "文件系统：${device.fileSystem.uppercase()}"
            )
        )
        if (!validFs && device.fileSystem != "unknown") score -= 5

        score = score.coerceIn(0, 100)
        val isGenuine = score >= 60

        val conclusion = when {
            score >= 90 -> "高度可信"
            score >= 75 -> "基本可信"
            score >= 60 -> "可能为真品"
            score >= 40 -> "疑似假冒"
            else -> "高度疑似假冒"
        }

        val suggestion = if (isGenuine) {
            "该存储设备通过基本验证，建议结合真实容量检测和速度测试综合判断。"
        } else {
            "该存储设备存在多项异常，强烈建议执行「真实容量检测」确认是否为扩容盘。"
        }

        return AuthenticityResult(
            isGenuine = isGenuine,
            score = score,
            checks = checks,
            conclusion = conclusion,
            suggestion = suggestion
        )
    }

    /** 内部数据类：sysfs 信息 */
    private data class SysfsInfo(
        val vendor: String = "",
        val model: String = "",
        val serial: String = "",
        val revision: String = ""
    ) {
        fun isNotEmpty(): Boolean = vendor.isNotEmpty() || model.isNotEmpty() || serial.isNotEmpty()
    }
}
