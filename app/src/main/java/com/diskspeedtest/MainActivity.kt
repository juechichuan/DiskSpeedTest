package com.diskspeedtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.diskspeedtest.databinding.ActivityMainBinding
import com.diskspeedtest.model.AuthenticityResult
import com.diskspeedtest.model.CapacityTestResult
import com.diskspeedtest.model.DataVerificationResult
import com.diskspeedtest.model.StorageInfo
import com.diskspeedtest.model.TestResult
import com.diskspeedtest.model.VendorInfo
import com.diskspeedtest.ui.DeviceAdapter
import com.diskspeedtest.util.SpeedTestManager
import com.diskspeedtest.util.StorageHelper
import com.diskspeedtest.util.VendorDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 主界面：检测存储设备并测试读写速度
 *
 * 支持的存储类型：
 * - 内部存储（应用私有目录，无需权限）
 * - SD 卡（优先使用应用私有目录，必要时通过 SAF 授权）
 * - 外接 U 盘（必须通过 SAF 授权访问）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter

    /** 当前选中的存储设备 */
    private var selectedDevice: StorageInfo? = null

    /** 通过 SAF 选择的 U 盘 / SD 卡目录 Uri */
    private var selectedTreeUri: Uri? = null

    /** 当前正在运行的测试任务 */
    private var testJob: Job? = null

    /** 上一次速度测试的写入速度 (MB/s)，用于真伪验证 */
    private var lastWriteSpeed: Double? = null

    /** 上一次速度测试的读取速度 (MB/s)，用于真伪验证 */
    private var lastReadSpeed: Double? = null

    /** 上一次容量检测的真实容量（字节），用于真伪验证 */
    private var lastRealCapacity: Long? = null

    /** SAF 目录选择器 */
    private val openDocumentTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化授权，下次启动仍可访问
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
            }
            selectedTreeUri = uri
            addSafDevice(uri)
        }
    }

    /** 存储权限请求 */
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            refreshDevices()
        } else {
            Toast.makeText(this, R.string.msg_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupListeners()
        checkPermissions()
        refreshDevices()
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            selectedDevice = device
            showDeviceInfo(device)
            // 选中了列表中的设备，清除 SAF 选择
            selectedTreeUri = null
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener { refreshDevices() }
        binding.btnSelectUsb.setOnClickListener { openDocumentTree.launch(null) }
        binding.btnStartTest.setOnClickListener { startTest() }
        binding.btnCheckCapacity.setOnClickListener { startCapacityCheck() }
        binding.btnVerifyAuthenticity.setOnClickListener { startAuthenticityCheck() }
        binding.btnStopTest.setOnClickListener { stopTest() }

        // 测试数据大小选择（Spinner）
        binding.spinnerTestSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 无需额外处理，开始测试时读取
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 检查并请求存储权限 */
    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestStoragePermission.launch(permissionsToRequest.toTypedArray())
        }
    }

    /** 刷新存储设备列表 */
    private fun refreshDevices() {
        lifecycleScope.launch {
            binding.tvEmptyHint.visibility = View.GONE
            val devices = StorageHelper.getStorageDevices(this@MainActivity)
            if (devices.isEmpty()) {
                binding.tvEmptyHint.visibility = View.VISIBLE
            }
            deviceAdapter.submitList(devices)
            deviceAdapter.clearSelection()
            selectedDevice = null
            selectedTreeUri = null
            binding.deviceInfoCard.visibility = View.GONE
        }
    }

    /**
     * 将通过 SAF 选择的目录添加为一个存储设备
     */
    private fun addSafDevice(uri: Uri) {
        lifecycleScope.launch {
            val name = queryDisplayName(uri) ?: "外接存储 (SAF)"
            val statFs = try {
                // 尝试通过路径获取容量信息
                val path = getPathFromUri(uri)
                if (path != null) android.os.StatFs(path) else null
            } catch (e: Exception) {
                null
            }

            val total = statFs?.totalBytes ?: 0L
            val free = statFs?.availableBytes ?: 0L

            val device = StorageInfo(
                name = name,
                path = getPathFromUri(uri) ?: uri.toString(),
                totalSpace = total,
                freeSpace = free,
                usedSpace = total - free,
                isRemovable = true,
                isEmulated = false,
                fileSystem = "unknown",
                uri = uri
            )

            val currentList = deviceAdapter.currentList.toMutableList()
            currentList.add(0, device)
            deviceAdapter.submitList(currentList)
            selectedDevice = device
            selectedTreeUri = uri
            showDeviceInfo(device)
            Toast.makeText(this@MainActivity, "已选择: $name", Toast.LENGTH_SHORT).show()
        }
    }

    /** 查询 SAF Uri 的显示名称 */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                    if (nameIndex != -1) it.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 尝试从 SAF Uri 解析出文件路径 */
    private fun getPathFromUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // docId 格式通常为 "primary" 或 "1234-5678:..."
            if (docId.contains(":")) {
                val volumeId = docId.substringBefore(":")
                val relPath = docId.substringAfter(":")
                if (volumeId == "primary") {
                    "/storage/emulated/0/$relPath"
                } else {
                    "/storage/$volumeId/$relPath"
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** 显示设备详细信息 */
    private fun showDeviceInfo(device: StorageInfo) {
        binding.deviceInfoCard.visibility = View.VISIBLE
        binding.tvTotalSpace.text = device.readableTotal
        binding.tvUsedSpace.text = "${device.readableUsed} (${device.usedPercent}%)"
        binding.tvFreeSpace.text = device.readableFree
        binding.tvFileSystem.text = device.fileSystem.uppercase()
        binding.tvDevicePath.text = device.path
        binding.pbUsage.progress = device.usedPercent

        // 厂商信息在后台线程获取（读取 sysfs）
        lifecycleScope.launch {
            val vendorInfo = VendorDetector.detectVendor(this@MainActivity, device)
            runOnUiThread {
                binding.tvVendor.text = vendorInfo.vendor
                binding.tvModel.text = vendorInfo.model
                binding.tvSerial.text = vendorInfo.serial
                binding.tvDeviceType.text = when (vendorInfo.deviceType) {
                    "sd" -> "SD 卡"
                    "usb" -> "U盘"
                    "internal" -> "内部存储"
                    else -> vendorInfo.deviceType
                }
            }
        }
    }

    /** 获取用户选择的测试数据大小 */
    private fun getSelectedTestSize(): SpeedTestManager.TestSize {
        return when (binding.spinnerTestSize.selectedItemPosition) {
            0 -> SpeedTestManager.TestSize.SMALL
            1 -> SpeedTestManager.TestSize.MEDIUM
            2 -> SpeedTestManager.TestSize.LARGE
            3 -> SpeedTestManager.TestSize.XLARGE
            4 -> SpeedTestManager.TestSize.XXLARGE
            5 -> SpeedTestManager.TestSize.HUGE
            else -> SpeedTestManager.TestSize.MEDIUM
        }
    }

    /** 开始速度测试 */
    private fun startTest() {
        val device = selectedDevice
        val uri = selectedTreeUri

        if (device == null && uri == null) {
            Toast.makeText(this, R.string.msg_select_device, Toast.LENGTH_SHORT).show()
            return
        }

        // 检查剩余空间是否足够
        val testSize = getSelectedTestSize()
        if (device != null && device.freeSpace < testSize.bytes && device.freeSpace > 0) {
            Toast.makeText(this, "可用空间不足，无法进行测试", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceToTest = selectedDevice
        val uriToTest = selectedTreeUri

        binding.resultCard.visibility = View.GONE
        binding.progressCard.visibility = View.VISIBLE
        binding.btnStartTest.isEnabled = false
        binding.btnStopTest.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgressText.text = getString(R.string.status_testing)

        testJob = lifecycleScope.launch {
            try {
                val result: TestResult = if (uriToTest != null) {
                    // 通过 SAF 测试（外接 U 盘 / SD 卡）
                    SpeedTestManager.testByUri(
                        context = this@MainActivity,
                        treeUri = uriToTest,
                        testSize = testSize,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else if (deviceToTest != null) {
                    // 通过文件路径测试
                    // 优先使用应用在该设备上的私有目录（无需权限）
                    val privateDir = StorageHelper.getAppPrivateDir(this@MainActivity, deviceToTest.path)
                    val testPath = privateDir ?: deviceToTest.path

                    if (privateDir == null) {
                        // 如果没有私有目录，提示用户使用 SAF
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.msg_usb_via_saf,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    SpeedTestManager.testByPath(
                        context = this@MainActivity,
                        dirPath = testPath,
                        testSize = testSize,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else {
                    TestResult(0.0, 0.0, 0, 0, 0, false, "未选择设备")
                }

                runOnUiThread {
                    showResult(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showResult(
                        TestResult(
                            0.0, 0.0, 0, 0, 0, false,
                            "测试异常: ${e.message}"
                        )
                    )
                }
            } finally {
                runOnUiThread {
                    binding.btnStartTest.isEnabled = true
                    binding.btnStopTest.visibility = View.GONE
                    binding.tvProgressText.text = getString(R.string.status_idle)
                }
                testJob = null
            }
        }
    }

    /** 开始真实容量检测 */
    private fun startCapacityCheck() {
        val device = selectedDevice
        val uri = selectedTreeUri

        if (device == null && uri == null) {
            Toast.makeText(this, R.string.msg_select_device, Toast.LENGTH_SHORT).show()
            return
        }

        // 标称容量
        val advertisedCapacity = device?.totalSpace ?: 0L
        if (advertisedCapacity <= 0) {
            Toast.makeText(this, "无法获取设备标称容量", Toast.LENGTH_SHORT).show()
            return
        }

        // 提示用户
        Toast.makeText(this, R.string.msg_capacity_warning, Toast.LENGTH_LONG).show()

        val deviceToTest = selectedDevice
        val uriToTest = selectedTreeUri

        binding.capacityResultCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.progressCard.visibility = View.VISIBLE
        binding.btnStartTest.isEnabled = false
        binding.btnCheckCapacity.isEnabled = false
        binding.btnStopTest.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgressText.text = "正在检测真实容量..."

        testJob = lifecycleScope.launch {
            try {
                val result: CapacityTestResult = if (uriToTest != null) {
                    SpeedTestManager.checkRealCapacityByUri(
                        context = this@MainActivity,
                        treeUri = uriToTest,
                        advertisedCapacity = advertisedCapacity,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else if (deviceToTest != null) {
                    val privateDir = StorageHelper.getAppPrivateDir(this@MainActivity, deviceToTest.path)
                    val testPath = privateDir ?: deviceToTest.path
                    SpeedTestManager.checkRealCapacityByPath(
                        context = this@MainActivity,
                        dirPath = testPath,
                        advertisedCapacity = advertisedCapacity,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else {
                    CapacityTestResult(0, 0, 0, false, 0, false, "未选择设备")
                }

                runOnUiThread {
                    showCapacityResult(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showCapacityResult(
                        CapacityTestResult(
                            advertisedCapacity = advertisedCapacity,
                            realCapacity = 0,
                            realFreeSpace = 0,
                            isFakeCapacity = false,
                            shrinkPercent = 0,
                            success = false,
                            errorMessage = "检测异常: ${e.message}"
                        )
                    )
                }
            } finally {
                runOnUiThread {
                    binding.btnStartTest.isEnabled = true
                    binding.btnCheckCapacity.isEnabled = true
                    binding.btnStopTest.visibility = View.GONE
                    binding.tvProgressText.text = getString(R.string.status_idle)
                }
                testJob = null
            }
        }
    }

    /** 显示容量检测结果 */
    private fun showCapacityResult(result: CapacityTestResult) {
        binding.progressCard.visibility = View.GONE
        binding.capacityResultCard.visibility = View.VISIBLE

        if (result.success) {
            lastRealCapacity = result.realCapacity
            binding.tvAdvertisedCapacity.text = result.readableAdvertised
            binding.tvRealCapacity.text = result.readableReal
            binding.tvShrinkPercent.text = "${result.shrinkPercent}%"
            binding.tvCapacityConclusion.text = result.conclusion
            // 根据结论设置颜色
            binding.tvCapacityConclusion.setTextColor(
                if (result.isFakeCapacity) getColor(R.color.error)
                else if (result.shrinkPercent > 5) getColor(R.color.warning)
                else getColor(R.color.success)
            )
        } else {
            lastRealCapacity = null
            binding.tvAdvertisedCapacity.text = result.readableAdvertised
            binding.tvRealCapacity.text = "—"
            binding.tvShrinkPercent.text = "—"
            binding.tvCapacityConclusion.text = result.errorMessage
            binding.tvCapacityConclusion.setTextColor(getColor(R.color.error))
            Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    /** 开始真伪检测（厂商识别 + 数据完整性哈希验证 + 综合评分） */
    private fun startAuthenticityCheck() {
        val device = selectedDevice
        val uri = selectedTreeUri

        if (device == null && uri == null) {
            Toast.makeText(this, R.string.msg_select_device, Toast.LENGTH_SHORT).show()
            return
        }

        val testSize = getSelectedTestSize()
        val deviceToTest = selectedDevice
        val uriToTest = selectedTreeUri

        Toast.makeText(this, R.string.msg_verify_warning, Toast.LENGTH_LONG).show()

        binding.dataVerifyResultCard.visibility = View.GONE
        binding.authenticityResultCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.capacityResultCard.visibility = View.GONE
        binding.progressCard.visibility = View.VISIBLE
        binding.btnStartTest.isEnabled = false
        binding.btnCheckCapacity.isEnabled = false
        binding.btnVerifyAuthenticity.isEnabled = false
        binding.btnStopTest.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgressText.text = "正在检测真伪..."

        testJob = lifecycleScope.launch {
            try {
                // 1. 厂商信息检测
                var vendorInfo: VendorInfo? = null
                if (deviceToTest != null) {
                    vendorInfo = VendorDetector.detectVendor(this@MainActivity, deviceToTest)
                }

                // 2. 数据完整性验证（哈希比较，参考 SD Card Test Pro 方法）
                val dataResult: DataVerificationResult = if (uriToTest != null) {
                    SpeedTestManager.verifyDataByUri(
                        context = this@MainActivity,
                        treeUri = uriToTest,
                        testSize = testSize,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else if (deviceToTest != null) {
                    val privateDir = StorageHelper.getAppPrivateDir(this@MainActivity, deviceToTest.path)
                    val testPath = privateDir ?: deviceToTest.path
                    SpeedTestManager.verifyDataByPath(
                        context = this@MainActivity,
                        dirPath = testPath,
                        testSize = testSize,
                        callback = { percent, message ->
                            runOnUiThread {
                                binding.progressBar.progress = percent
                                binding.tvProgressText.text = message
                            }
                        }
                    )
                } else {
                    DataVerificationResult(
                        isVerified = false, testDataSize = 0, writeHash = "", readHash = "",
                        writeSpeedMBps = 0.0, readSpeedMBps = 0.0, elapsedMs = 0,
                        success = false, errorMessage = "未选择设备"
                    )
                }

                runOnUiThread { showDataVerificationResult(dataResult) }

                // 3. 综合真伪评分
                val authenticityResult: AuthenticityResult? = if (deviceToTest != null && vendorInfo != null) {
                    VendorDetector.verifyAuthenticity(
                        device = deviceToTest,
                        vendorInfo = vendorInfo,
                        realCapacity = lastRealCapacity,
                        writeSpeedMBps = lastWriteSpeed ?: dataResult.writeSpeedMBps,
                        readSpeedMBps = lastReadSpeed ?: dataResult.readSpeedMBps
                    )
                } else null

                runOnUiThread {
                    if (authenticityResult != null) {
                        showAuthenticityResult(authenticityResult)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showDataVerificationResult(
                        DataVerificationResult(
                            isVerified = false, testDataSize = 0, writeHash = "", readHash = "",
                            writeSpeedMBps = 0.0, readSpeedMBps = 0.0, elapsedMs = 0,
                            success = false, errorMessage = "检测异常: ${e.message}"
                        )
                    )
                }
            } finally {
                runOnUiThread {
                    binding.btnStartTest.isEnabled = true
                    binding.btnCheckCapacity.isEnabled = true
                    binding.btnVerifyAuthenticity.isEnabled = true
                    binding.btnStopTest.visibility = View.GONE
                    binding.tvProgressText.text = getString(R.string.status_idle)
                }
                testJob = null
            }
        }
    }

    /** 显示数据完整性验证结果（哈希比较） */
    private fun showDataVerificationResult(result: DataVerificationResult) {
        binding.progressCard.visibility = View.GONE
        binding.dataVerifyResultCard.visibility = View.VISIBLE

        binding.tvVerifyDataSize.text = result.readableDataSize
        binding.tvWriteHash.text = result.writeHash.ifEmpty { "—" }
        binding.tvReadHash.text = result.readHash.ifEmpty { "—" }
        binding.tvVerifyWriteSpeed.text = String.format("%.1f MB/s", result.writeSpeedMBps)
        binding.tvVerifyReadSpeed.text = String.format("%.1f MB/s", result.readSpeedMBps)

        if (result.success) {
            if (result.isVerified) {
                binding.tvVerifyStatus.text = getString(R.string.verify_passed)
                binding.tvVerifyStatus.setTextColor(getColor(R.color.success))
                binding.tvVerifyConclusion.setTextColor(getColor(R.color.success))
            } else {
                binding.tvVerifyStatus.text = getString(R.string.verify_failed)
                binding.tvVerifyStatus.setTextColor(getColor(R.color.error))
                binding.tvVerifyConclusion.setTextColor(getColor(R.color.error))
            }
            binding.tvVerifyConclusion.text = result.conclusion
        } else {
            binding.tvVerifyStatus.text = getString(R.string.verify_failed)
            binding.tvVerifyStatus.setTextColor(getColor(R.color.error))
            binding.tvVerifyConclusion.setTextColor(getColor(R.color.error))
            binding.tvVerifyConclusion.text = result.conclusion
            Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    /** 显示真伪验证综合结果 */
    private fun showAuthenticityResult(result: AuthenticityResult) {
        binding.authenticityResultCard.visibility = View.VISIBLE

        binding.tvAuthScore.text = result.score.toString()
        binding.tvAuthConclusion.text = result.conclusion
        binding.tvAuthSuggestion.text = result.suggestion

        val scoreColor = when {
            result.score >= 75 -> getColor(R.color.success)
            result.score >= 60 -> getColor(R.color.warning)
            else -> getColor(R.color.error)
        }
        binding.tvAuthScore.setTextColor(scoreColor)
        binding.tvAuthConclusion.setTextColor(scoreColor)

        // 动态生成检测项列表
        val container = binding.authChecksContainer
        container.removeAllViews()
        for (check in result.checks) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val title = android.widget.TextView(this).apply {
                text = "${if (check.passed) "✓" else "✗"} ${check.name}"
                setTextColor(if (check.passed) getColor(R.color.success) else getColor(R.color.error))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val detail = android.widget.TextView(this).apply {
                text = check.detail
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 2, 0, 0)
            }
            row.addView(title)
            row.addView(detail)
            container.addView(row)
        }
    }

    /** 停止测试 */
    private fun stopTest() {
        testJob?.cancel()
        testJob = null
        binding.btnStartTest.isEnabled = true
        binding.btnCheckCapacity.isEnabled = true
        binding.btnVerifyAuthenticity.isEnabled = true
        binding.btnStopTest.visibility = View.GONE
        binding.tvProgressText.text = getString(R.string.status_idle)
        binding.progressBar.progress = 0
        Toast.makeText(this, "测试已停止", Toast.LENGTH_SHORT).show()
    }

    /** 显示测试结果 */
    private fun showResult(result: TestResult) {
        binding.progressCard.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE

        if (result.success) {
            lastWriteSpeed = result.writeSpeedMBps
            lastReadSpeed = result.readSpeedMBps
            binding.tvWriteSpeed.text = result.readableWriteSpeed
            binding.tvReadSpeed.text = result.readableReadSpeed
            binding.tvWriteRating.text = SpeedTestManager.getSpeedRating(result.writeSpeedMBps)
            binding.tvReadRating.text = SpeedTestManager.getSpeedRating(result.readSpeedMBps)
            binding.tvWriteTime.text = "${result.writeTimeMs} ms"
            binding.tvReadTime.text = "${result.readTimeMs} ms"
            binding.tvDataSize.text = result.readableDataSize
        } else {
            lastWriteSpeed = null
            lastReadSpeed = null
            binding.tvWriteSpeed.text = "—"
            binding.tvReadSpeed.text = "—"
            binding.tvWriteRating.text = result.errorMessage
            binding.tvReadRating.text = ""
            binding.tvWriteTime.text = "—"
            binding.tvReadTime.text = "—"
            binding.tvDataSize.text = result.readableDataSize
            Toast.makeText(this, result.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refreshDevices()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
    }
}
