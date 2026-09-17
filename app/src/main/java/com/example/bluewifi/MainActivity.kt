package com.example.bluewifi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    companion object {
        private const val PREFS_NAME = "blue_wifi_prefs"
        private const val KEY_BOUND_MAC = "bound_host_mac"
        private const val KEY_BOUND_NAME = "bound_host_name"
        private const val KEY_AUTO_CONNECT = "auto_connect_on_start"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var wifiScanManager: WifiScanManager
    private lateinit var wifiAdapter: WifiListAdapter
    private var isBtReceiverRegistered = false

    // 蓝牙状态广播接收器
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    binding.tvBtStatus.text = "检测到蓝牙已开启，正在请求外设服务..."
                    binding.btnRetryBt.visibility = View.GONE
                    hidManager.initialize()
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    binding.tvBtStatus.text = "系统蓝牙已关闭，请开启蓝牙"
                    binding.btnRetryBt.visibility = View.VISIBLE
                    binding.viewStatusDot.backgroundTintList =
                        ContextCompat.getColorStateList(this@MainActivity, R.color.status_disconnected)
                }
            }
        }
    }

    // 引导开启蓝牙 launcher
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            setupServices()
        } else {
            Toast.makeText(this, "蓝牙未开启，无法使用键鼠模拟与自动化功能", Toast.LENGTH_LONG).show()
        }
    }

    // 权限请求 launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupServices()
        } else {
            Toast.makeText(this, "需要授予蓝牙与定位权限以支持键鼠模拟及WLAN扫描", Toast.LENGTH_LONG).show()
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

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
        isBtReceiverRegistered = true

        initViews()
        loadPreferences()
        checkAndRequestPermissions()
    }

    private fun loadPreferences() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val boundMac = sp.getString(KEY_BOUND_MAC, null)
        val boundName = sp.getString(KEY_BOUND_NAME, null)
        val autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, false)

        binding.switchAutoConnect.isChecked = autoConnect
        if (!boundMac.isNullOrEmpty()) {
            binding.tvBoundHost.text = getString(R.string.title_bound_host, "${boundName ?: "未知设备"} ($boundMac)")
        } else {
            binding.tvBoundHost.text = getString(R.string.status_unbound_host)
        }

        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(KEY_AUTO_CONNECT, isChecked).apply()
        }
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

        // 重新初始化按钮
        binding.btnRetryBt.setOnClickListener {
            binding.btnRetryBt.visibility = View.GONE
            binding.tvBtStatus.text = "正在重新连接蓝牙服务..."
            hidManager.reinitialize()
        }

        // 核心：一键回连目标热点手机
        binding.btnReconnectHost.setOnClickListener {
            connectToBoundHost()
        }

        // 绑定/选择已配对的热点手机
        binding.btnBindHost.setOnClickListener {
            showBindHostDialog()
        }

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
            // 在 Android 手机上，鼠标右键默认就是全局“返回”操作
            hidManager.clickRightMouse()
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

    /**
     * 弹出对话框选择已配对的蓝牙手机作为热点主机
     */
    private fun showBindHostDialog() {
        val bondedDevices = hidManager.getBondedDevices().toList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, "系统未找到已配对的蓝牙设备，请先开启可发现模式并与SIM卡手机配对", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bondedDevices.map { "${it.name ?: "未知设备"} (${it.address})" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要绑定的SIM卡热点手机")
            .setItems(deviceNames) { _, which ->
                val selected = bondedDevices[which]
                val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                sp.edit()
                    .putString(KEY_BOUND_MAC, selected.address)
                    .putString(KEY_BOUND_NAME, selected.name ?: "未知设备")
                    .apply()

                binding.tvBoundHost.text = getString(R.string.title_bound_host, "${selected.name} (${selected.address})")
                Toast.makeText(this, "已绑定热点机: ${selected.name}，可随时点击一键重连", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 主动发起连接绑定的热点手机
     */
    private fun connectToBoundHost() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mac = sp.getString(KEY_BOUND_MAC, null)
        val name = sp.getString(KEY_BOUND_NAME, "热点手机")

        if (mac.isNullOrEmpty()) {
            Toast.makeText(this, "尚未绑定热点机，请先点击【绑定/选择热点机】", Toast.LENGTH_LONG).show()
            showBindHostDialog()
            return
        }

        val adapter = hidManager.bluetoothAdapter
        if (adapter != null && !adapter.isEnabled) {
            Toast.makeText(this, "系统蓝牙未开启，正在请求开启...", Toast.LENGTH_SHORT).show()
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (!hidManager.isReady) {
            Toast.makeText(this, "蓝牙外设服务正在初始化，请稍等片刻或点击【重新初始化】", Toast.LENGTH_SHORT).show()
            hidManager.initialize()
            return
        }

        Toast.makeText(this, getString(R.string.msg_connecting_host, name), Toast.LENGTH_SHORT).show()
        val started = hidManager.connect(mac)
        if (!started) {
            Toast.makeText(this, "发起连接失败，请确认该设备已配对并在附近", Toast.LENGTH_SHORT).show()
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
        val adapter = hidManager.bluetoothAdapter
        if (adapter != null && !adapter.isEnabled) {
            binding.tvBtStatus.text = "系统蓝牙未开启，正在请求开启..."
            binding.btnRetryBt.visibility = View.VISIBLE
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_disconnected)
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        hidManager.initialize()
        updateCurrentConnectedWifi()
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

    override fun onStatusMessage(message: String) {
        binding.tvBtStatus.text = message
    }

    override fun onAppRegistered(registered: Boolean) {
        if (registered) {
            binding.tvBtStatus.text = getString(R.string.bt_status_ready)
            binding.btnRetryBt.visibility = View.GONE
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_connecting)

            // 检查是否开启了“启动时自动重连热点机”
            val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, false)
            val boundMac = sp.getString(KEY_BOUND_MAC, null)
            if (autoConnect && !boundMac.isNullOrEmpty()) {
                binding.root.postDelayed({
                    connectToBoundHost()
                }, 600)
            }
        } else {
            binding.tvBtStatus.text = getString(R.string.bt_status_uninitialized)
            binding.btnRetryBt.visibility = View.VISIBLE
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
                binding.btnRetryBt.visibility = View.GONE
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
        binding.btnRetryBt.visibility = View.VISIBLE
        binding.viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.status_disconnected)
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
        if (isBtReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                // ignore
            }
            isBtReceiverRegistered = false
        }
        wifiScanManager.unregisterReceiver()
        hidManager.release()
    }
}
