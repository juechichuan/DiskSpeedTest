package com.diskspeedtest.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.diskspeedtest.model.StorageInfo
import com.diskspeedtest.model.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext


/**
 * 存储设备读写速度测试管理器
 *
 * 支持两种访问方式：
 * 1. 文件路径（File）：适用于应用私有目录、内部存储等
 * 2. SAF URI：适用于通过系统文件选择器授权的外接U盘/SD卡
 */
object SpeedTestManager {

    /** 默认缓冲区大小：256KB */
    private const val BUFFER_SIZE = 256 * 1024

    /** 测试数据大小配置（字节） */
    enum class TestSize(val bytes: Long, val label: String) {
        SMALL(4L * 1024 * 1024, "4 MB（快速）"),
        MEDIUM(16L * 1024 * 1024, "16 MB（标准）"),
        LARGE(64L * 1024 * 1024, "64 MB（精确）"),
        XLARGE(256L * 1024 * 1024, "256 MB（深度）")
    }

    /** 测试进度回调 */
    fun interface ProgressCallback {
        fun onProgress(percent: Int, message: String)
    }

    /**
     * 对指定文件路径执行读写速度测试
     *
     * @param context 上下文
     * @param dirPath 测试目录的绝对路径
     * @param testSize 测试数据大小
     * @param callback 进度回调
     * @return 测试结果
     */
    suspend fun testByPath(
        context: Context,
        dirPath: String,
        testSize: TestSize = TestSize.MEDIUM,
        callback: ProgressCallback? = null
    ): TestResult = withContext(Dispatchers.IO) {
        val tempFile = File(dirPath, ".disk_speed_test_${System.currentTimeMillis()}.tmp")
        try {
            runTest(tempFile, testSize, callback, useUri = false, context = context, uri = null)
        } finally {
            // 清理临时文件
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 对指定 SAF URI 执行读写速度测试（用于外接U盘/SD卡）
     *
     * @param context 上下文
     * @param treeUri 通过 SAF 获取的目录 Uri
     * @param testSize 测试数据大小
     * @param callback 进度回调
     * @return 测试结果
     */
    suspend fun testByUri(
        context: Context,
        treeUri: Uri,
        testSize: TestSize = TestSize.MEDIUM,
        callback: ProgressCallback? = null
    ): TestResult = withContext(Dispatchers.IO) {
        // 在授权目录下创建一个用于测试的文档 Uri
        val docUri = createDocumentUri(context, treeUri, "speed_test_${System.currentTimeMillis()}.tmp")
        try {
            runTest(
                tempFile = null,
                testSize = testSize,
                callback = callback,
                useUri = true,
                context = context,
                uri = docUri
            )
        } finally {
            // 清理测试文件
            try {
                if (docUri != null) {
                    DocumentsContract.deleteDocument(context.contentResolver, docUri)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 执行实际的读写测试
     */
    private suspend fun runTest(
        tempFile: File?,
        testSize: TestSize,
        callback: ProgressCallback?,
        useUri: Boolean,
        context: Context,
        uri: Uri?
    ): TestResult {
        val totalBytes = testSize.bytes
        val buffer = ByteArray(BUFFER_SIZE)
        // 填充随机数据，避免压缩或缓存优化影响结果
        java.util.Random().nextBytes(buffer)

        // ========== 写入测试 ==========
        callback?.onProgress(5, "准备写入测试数据 (${testSize.label})...")

        var writeSpeed = 0.0
        var writeTimeMs = 0L

        try {
            val outputStream: OutputStream = if (useUri && uri != null) {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw Exception("无法打开输出流")
            } else {
                FileOutputStream(tempFile)
            }

            outputStream.use { out ->
                val buffered = out.buffered(BUFFER_SIZE)
                val startTime = System.nanoTime()

                var written = 0L
                while (written < totalBytes) {
                    coroutineContext.ensureActive()
                    val toWrite = minOf(buffer.size.toLong(), totalBytes - written).toInt()
                    buffered.write(buffer, 0, toWrite)
                    written += toWrite

                    val percent = 10 + ((written * 40) / totalBytes).toInt()
                    callback?.onProgress(percent.toInt(), "写入中... ${StorageInfo.formatSize(written)} / ${testSize.label}")
                }
                buffered.flush()
                // 强制刷到底层存储，确保测量包含实际落盘时间
                out.flush()

                writeTimeMs = (System.nanoTime() - startTime) / 1_000_000
                writeSpeed = if (writeTimeMs > 0) {
                    (totalBytes.toDouble() / (1024.0 * 1024.0)) / (writeTimeMs / 1000.0)
                } else 0.0
            }
        } catch (e: Exception) {
            return TestResult(
                writeSpeedMBps = 0.0,
                readSpeedMBps = 0.0,
                writeTimeMs = 0,
                readTimeMs = 0,
                testDataSize = totalBytes,
                success = false,
                errorMessage = "写入失败: ${e.message}"
            )
        }

        // ========== 读取测试 ==========
        callback?.onProgress(55, "准备读取测试数据...")

        var readSpeed = 0.0
        var readTimeMs = 0L

        try {
            val inputStream: InputStream = if (useUri && uri != null) {
                context.contentResolver.openInputStream(uri)
                    ?: throw Exception("无法打开输入流")
            } else {
                FileInputStream(tempFile)
            }

            inputStream.use { input ->
                val buffered = input.buffered(BUFFER_SIZE)
                val startTime = System.nanoTime()

                var read = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = buffered.read(buffer)
                    if (n <= 0) break
                    read += n

                    val percent = 60 + ((read * 35) / totalBytes).toInt()
                    callback?.onProgress(percent.toInt(), "读取中... ${StorageInfo.formatSize(read)} / ${testSize.label}")
                }

                readTimeMs = (System.nanoTime() - startTime) / 1_000_000
                readSpeed = if (readTimeMs > 0) {
                    (totalBytes.toDouble() / (1024.0 * 1024.0)) / (readTimeMs / 1000.0)
                } else 0.0
            }
        } catch (e: Exception) {
            return TestResult(
                writeSpeedMBps = writeSpeed,
                readSpeedMBps = 0.0,
                writeTimeMs = writeTimeMs,
                readTimeMs = 0,
                testDataSize = totalBytes,
                success = false,
                errorMessage = "读取失败: ${e.message}"
            )
        }

        callback?.onProgress(100, "测试完成")

        return TestResult(
            writeSpeedMBps = writeSpeed,
            readSpeedMBps = readSpeed,
            writeTimeMs = writeTimeMs,
            readTimeMs = readTimeMs,
            testDataSize = totalBytes,
            success = true
        )
    }

    /**
     * 在 SAF 目录下创建一个文档 Uri
     */
    private fun createDocumentUri(context: Context, treeUri: Uri, displayName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            DocumentsContract.createDocument(resolver, rootUri, "application/octet-stream", displayName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据速度给出评级
     */
    fun getSpeedRating(mbps: Double): String {
        return when {
            mbps >= 200 -> "极快 (USB 3.0+ 级别)"
            mbps >= 100 -> "很快 (USB 3.0 级别)"
            mbps >= 50 -> "较快 (USB 2.0 高速)"
            mbps >= 20 -> "中等 (USB 2.0 全速)"
            mbps >= 10 -> "一般"
            mbps > 0 -> "较慢"
            else -> "未知"
        }
    }
}
