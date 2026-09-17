package com.example.bluewifi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bluewifi.databinding.ActivityMainBinding
import com.example.bluewifi.hid.BluetoothHidManager
import com.example.bluewifi.hid.HidConsts
import com.example.bluewifi.hid.HidDeviceListener
import com.example.bluewifi.ui.TouchPadView
import com.example.bluewifi.ui.WifiListAdapter
import com.example.bluewifi.wifi.WifiScanManager
import com.google.android.material.snackbar.Snackbar

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), HidDeviceListener, TouchPadView.TouchPadListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var wifiScanManager: WifiScanManager
    private lateinit var wifiAdapter: WifiListAdapter

    // 权限请求 launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupServices()
        } else {
            Toast.makeText(this, "需要授予蓝牙与定位权限以支持键鼠模拟及WLAN扫描", Toast.LENGTH_LONG).show()
            // 依然尝试初始化可用服务
            setupServices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hidManager = BluetoothHidManager(this)
        hidManager.listener = this

        wifiScanManager = WifiScanManager(this)

        initViews()
        checkAndRequestPermissions()
    }

    private fun initViews() {
        // 初始化 WLAN 列表
        wifiAdapter = WifiListAdapter { wifiItem ->
            Toast.makeText(this, "选中 WiFi: ${wifiItem.ssid}", Toast.LENGTH_SHORT).show()
        }
        binding.rvWifiList.layoutManager = LinearLayoutManager(this)
        binding.rvWifiList.adapter = wifiAdapter

        // 触摸板事件监听
        binding.touchPadView.listener = this

        // 按钮交互
        binding.btnMakeDiscoverable.setOnClickListener {
            makeDiscoverable()
        }

        binding.btnLeftClick.setOnClickListener {
            hidManager.clickLeftMouse()
        }
        binding.btnRightClick.setOnClickListener {
            hidManager.clickRightMouse()
        }

        // 键盘按键
        binding.btnKeyEnter.setOnClickListener {
            hidManager.tapKey(HidConsts.KEY_ENTER)
        }
        binding.btnKeyBack.setOnClickListener {
            hidManager.tapKey(HidConsts.KEY_ESCAPE)
        }
        binding.btnKeyHome.setOnClickListener {
            hidManager.tapConsumerKey(HidConsts.CONSUMER_HOME)
        }
        binding.btnKeyVolUp.setOnClickListener {
            hidManager.tapConsumerKey(HidConsts.CONSUMER_VOLUME_UP)
        }
        binding.btnKeyVolDown.setOnClickListener {
            hidManager.tapConsumerKey(HidConsts.CONSUMER_VOLUME_DOWN)
        }

        // WLAN 扫描与设置按钮
        binding.btnScanWifi.setOnClickListener {
            performWifiScan()
        }
        binding.btnOpenWifiSettings.setOnClickListener {
            wifiScanManager.openWifiSettings()
        }

        // 监听 WiFi 扫描结果
        wifiScanManager.onScanCompletedListener = { list ->
            binding.pbWifiScanning.visibility = View.GONE
            wifiAdapter.submitList(list)
            binding.tvWifiEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            updateCurrentConnectedWifi()
        }

        wifiScanManager.onScanFailedListener = { msg ->
            binding.pbWifiScanning.visibility = View.GONE
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
        } else {
            setupServices()
        }
    }

    private fun setupServices() {
        hidManager.initialize()
        updateCurrentConnectedWifi()
        // 初始触发一次 WLAN 列表扫描展示
        performWifiScan()
    }

    /**
     * 开启当前手机的蓝牙可发现模式，以便另一台手机搜索并连接
     */
    private fun makeDiscoverable() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        try {
            startActivity(discoverableIntent)
            Toast.makeText(this, "蓝牙已设置为 300 秒内可被其他手机发现", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法开启可发现模式: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performWifiScan() {
        binding.pbWifiScanning.visibility = View.VISIBLE
        binding.tvWifiEmpty.visibility = View.GONE
        wifiScanManager.startScan()
    }

    private fun updateCurrentConnectedWifi() {
        val ssid = wifiScanManager.getCurrentConnectedSsid()
        binding.tvCurrentWifi.text = getString(R.string.wifi_current_connected, ssid)
    }

    // === HidDeviceListener 回调处理 ===

    override fun onAppRegistered(registered: Boolean) {
        if (registered) {
            binding.tvBtStatus.text = getString(R.string.bt_status_ready)
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_connecting)
        } else {
            binding.tvBtStatus.text = getString(R.string.bt_status_uninitialized)
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
        }
    }

    override fun onDeviceStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                val deviceName = device.name ?: "未知设备"
                binding.tvBtStatus.text = getString(R.string.bt_status_connected, deviceName)
                binding.tvConnectedDevice.text = "设备地址: ${device.address}"
                binding.viewStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_connected)

                // 核心功能点：连接成功后自动刷新终端 WLAN 列表
                Snackbar.make(binding.root, R.string.auto_refresh_notice, Snackbar.LENGTH_LONG)
                    .setAction("打开系统WLAN") {
                        wifiScanManager.openWifiSettings()
                    }
                    .show()

                // 触发刷新终端 WLAN 列表
                performWifiScan()
            }

            BluetoothProfile.STATE_CONNECTING -> {
                binding.tvBtStatus.text = getString(R.string.bt_status_connecting)
                binding.viewStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_connecting)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                binding.tvBtStatus.text = getString(R.string.bt_status_disconnected)
                binding.tvConnectedDevice.text = "未连接目标手机"
                binding.viewStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_disconnected)
            }
        }
    }

    override fun onError(message: String) {
        binding.tvBtStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // === TouchPadListener 触摸板鼠标事件上报 ===

    override fun onMouseMove(dx: Byte, dy: Byte) {
        hidManager.sendMouseReport(dx, dy, leftBtn = false, rightBtn = false)
    }

    override fun onLeftClick() {
        hidManager.clickLeftMouse()
    }

    override fun onRightClick() {
        hidManager.clickRightMouse()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiScanManager.unregisterReceiver()
        hidManager.release()
    }
}
