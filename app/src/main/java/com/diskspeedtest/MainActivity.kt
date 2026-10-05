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
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.diskspeedtest.databinding.ActivityMainBinding
import com.diskspeedtest.model.StorageInfo
import com.diskspeedtest.model.TestResult
import com.diskspeedtest.ui.DeviceAdapter
import com.diskspeedtest.util.SpeedTestManager
import com.diskspeedtest.util.StorageHelper
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
        binding.btnStopTest.setOnClickListener { stopTest() }

        // 测试数据大小选择
        binding.rgTestSize.setOnCheckedChangeListener { _, _ ->
            // 无需额外处理，开始测试时读取
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
    }

    /** 获取用户选择的测试数据大小 */
    private fun getSelectedTestSize(): SpeedTestManager.TestSize {
        val checkedId = binding.rgTestSize.checkedRadioButtonId
        return when (checkedId) {
            R.id.rbSmall -> SpeedTestManager.TestSize.SMALL
            R.id.rbLarge -> SpeedTestManager.TestSize.LARGE
            R.id.rbXlarge -> SpeedTestManager.TestSize.XLARGE
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

    /** 停止测试 */
    private fun stopTest() {
        testJob?.cancel()
        testJob = null
        binding.btnStartTest.isEnabled = true
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
            binding.tvWriteSpeed.text = result.readableWriteSpeed
            binding.tvReadSpeed.text = result.readableReadSpeed
            binding.tvWriteRating.text = SpeedTestManager.getSpeedRating(result.writeSpeedMBps)
            binding.tvReadRating.text = SpeedTestManager.getSpeedRating(result.readSpeedMBps)
            binding.tvWriteTime.text = "${result.writeTimeMs} ms"
            binding.tvReadTime.text = "${result.readTimeMs} ms"
            binding.tvDataSize.text = result.readableDataSize
        } else {
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
