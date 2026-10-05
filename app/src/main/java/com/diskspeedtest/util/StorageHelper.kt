package com.diskspeedtest.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.diskspeedtest.model.StorageInfo
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 存储设备检测工具
 * 负责列出所有可用的存储设备（内部存储、SD卡、外接U盘等）
 */
object StorageHelper {

    /**
     * 获取所有可用的存储设备列表
     */
    fun getStorageDevices(context: Context): List<StorageInfo> {
        val devices = mutableListOf<StorageInfo>()

        // 方式一：通过 StorageManager 获取存储卷（推荐，Android 7.0+）
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val volumes: List<StorageVolume> = storageManager.storageVolumes

        for (volume in volumes) {
            // getDirectory 在 Android 7.0+ 可用，但需要权限或返回 null
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory
            } else {
                try {
                    @Suppress("DEPRECATION")
                    volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
                } catch (e: Exception) {
                    null
                }
            }

            val path = directory?.absolutePath ?: getVolumePath(volume)
            val state = volume.state

            // 只列出已挂载的设备
            if (state != Environment.MEDIA_MOUNTED) continue

            val statFs = try {
                StatFs(path)
            } catch (e: Exception) {
                continue
            }

            val totalSpace = statFs.totalBytes
            val freeSpace = statFs.availableBytes
            val usedSpace = totalSpace - freeSpace

            val isRemovable = volume.isRemovable
            val isEmulated = volume.isEmulated

            val name = buildString {
                append(volume.getDescription(context))
                if (isRemovable && !isEmulated) append(" (可移动)")
            }

            val fileSystem = getFileSystemType(path)

            val uuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                volume.uuid ?: ""
            } else {
                ""
            }

            devices.add(
                StorageInfo(
                    name = name,
                    path = path,
                    totalSpace = totalSpace,
                    freeSpace = freeSpace,
                    usedSpace = usedSpace,
                    isRemovable = isRemovable,
                    isEmulated = isEmulated,
                    fileSystem = fileSystem,
                    uuid = uuid
                )
            )
        }

        // 方式二：通过 Environment 的外部文件目录补充检测（兜底方案）
        addExternalDirs(context, devices)

        // 去重（按路径）
        val unique = devices.distinctBy { it.path }
        return unique
    }

    /**
     * 从 StorageVolume 获取路径（兼容旧版本）
     */
    private fun getVolumePath(volume: StorageVolume): String {
        return try {
            val field = volume.javaClass.getDeclaredField("mPath")
            field.isAccessible = true
            field.get(volume) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 通过应用外部目录补充检测存储设备
     */
    private fun addExternalDirs(context: Context, devices: MutableList<StorageInfo>) {
        val externalDirs = context.getExternalFilesDirs(null)
        val existingPaths = devices.map { it.path }.toSet()

        for (dir in externalDirs) {
            if (dir == null) continue
            // 获取设备根路径
            val rootPath = getStorageRoot(dir.absolutePath) ?: continue
            if (rootPath in existingPaths) continue

            val statFs = try {
                StatFs(rootPath)
            } catch (e: Exception) {
                continue
            }

            val totalSpace = statFs.totalBytes
            val freeSpace = statFs.availableBytes
            val usedSpace = totalSpace - freeSpace

            val isExternal = Environment.isExternalStorageRemovable(dir)
            val isEmulated = Environment.isExternalStorageEmulated(dir)

            val name = if (isExternal && !isEmulated) "SD 卡 / 外接存储" else "内部存储"

            devices.add(
                StorageInfo(
                    name = name,
                    path = rootPath,
                    totalSpace = totalSpace,
                    freeSpace = freeSpace,
                    usedSpace = usedSpace,
                    isRemovable = isExternal,
                    isEmulated = isEmulated,
                    fileSystem = getFileSystemType(rootPath)
                )
            )
        }
    }

    /**
     * 根据应用外部文件目录推导存储设备根路径
     * 例如 /storage/emulated/0/Android/data/com.xxx/files -> /storage/emulated/0
     *      /storage/1234-5678/Android/data/com.xxx/files   -> /storage/1234-5678
     */
    private fun getStorageRoot(appDirPath: String): String? {
        val marker = "/Android/data/"
        val idx = appDirPath.indexOf(marker)
        return if (idx > 0) appDirPath.substring(0, idx) else appDirPath
    }

    /**
     * 获取指定路径的文件系统类型
     * 通过读取 /proc/mounts 获取挂载信息
     */
    fun getFileSystemType(path: String): String {
        return try {
            val reader = BufferedReader(FileReader("/proc/mounts"))
            var bestFs = "unknown"
            var bestMatchLen = -1
            reader.useLines { lines ->
                for (line in lines) {
                    val parts = line.split(" ")
                    if (parts.size >= 3) {
                        val mountPoint = parts[1]
                        val fsType = parts[2]
                        // 找到挂载点是目标路径前缀的最长匹配
                        if (path.startsWith(mountPoint) && mountPoint.length > bestMatchLen) {
                            bestMatchLen = mountPoint.length
                            bestFs = fsType
                        }
                    }
                }
            }
            bestFs
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取应用在指定存储设备上的私有目录路径（无需权限）
     * @param storageRoot 存储设备根路径
     * @return 应用私有目录路径，如果不可用则返回 null
     */
    fun getAppPrivateDir(context: Context, storageRoot: String): String? {
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            if (dir == null) continue
            if (dir.absolutePath.startsWith(storageRoot)) {
                return dir.absolutePath
            }
        }
        return null
    }

    /**
     * 检查存储设备是否可写
     */
    fun isWritable(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查存储设备是否可读
     */
    fun isReadable(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.canRead()
        } catch (e: Exception) {
            false
        }
    }
}
