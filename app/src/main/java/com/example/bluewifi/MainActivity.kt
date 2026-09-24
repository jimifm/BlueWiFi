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
import android.os.PowerManager
import com.example.bluewifi.databinding.ActivityMainBinding
import com.example.bluewifi.hid.BluetoothHidManager
import com.example.bluewifi.hid.HidConsts
import com.example.bluewifi.hid.HidDeviceListener
import com.example.bluewifi.service.HotspotWakeService
import com.example.bluewifi.ui.TouchPadView
import com.example.bluewifi.ui.WifiListAdapter
import com.example.bluewifi.wifi.WifiItem
import com.example.bluewifi.wifi.WifiScanManager
import com.google.android.material.snackbar.Snackbar

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), HidDeviceListener, TouchPadView.TouchPadListener {

    companion object {
        private const val PREFS_NAME = "blue_wifi_prefs"
        private const val KEY_BOUND_MAC = "bound_host_mac"
        private const val KEY_BOUND_NAME = "bound_host_name"
        private const val KEY_AUTO_CONNECT = "auto_connect_on_start"
        private const val KEY_AUTO_RECONNECT_ON_DISCONNECT = "auto_reconnect_on_disconnect"
        private const val KEY_TARGET_WLAN_SSID = "target_wlan_ssid"
        private const val KEY_TARGET_WLAN_PASSWORD = "target_wlan_password"
        private const val KEY_WLAN_SCAN_DELAY_SEC = "wlan_scan_delay_sec"
        private const val DEFAULT_WLAN_SCAN_DELAY_SEC = 5
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var wifiScanManager: WifiScanManager
    private lateinit var wifiAdapter: WifiListAdapter
    private var isBtReceiverRegistered = false
    private var pendingScanRunnable: Runnable? = null

    // 蓝牙状态广播接收器
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    binding.tvBtStatus.text = "检测到蓝牙已开启，正在请求外设服务..."
                    binding.btnRetryBt.visibility = View.GONE
                    HotspotWakeService.startService(this@MainActivity)
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

        hidManager = BluetoothHidManager.getInstance(applicationContext)
        hidManager.addListener(this)

        wifiScanManager = WifiScanManager(this)

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
        isBtReceiverRegistered = true

        initViews()
        loadPreferences()
        syncHidState()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        syncHidState()
        updateCurrentConnectedWifi()
        checkBatteryOptimizations(forcePrompt = false)
    }

    /**
     * 同步当前全局 BluetoothHidManager 与服务连接状态到 UI
     */
    private fun syncHidState() {
        val dev = hidManager.connectedDevice
        if (dev != null && hidManager.lastDeviceState == BluetoothProfile.STATE_CONNECTED) {
            val deviceName = dev.name ?: "未知设备"
            binding.tvBtStatus.text = getString(R.string.bt_status_connected, deviceName)
            binding.tvConnectedDevice.text = "设备地址: ${dev.address}"
            binding.btnRetryBt.visibility = View.GONE
            binding.viewStatusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.status_connected)
            updateControlButtons(BluetoothProfile.STATE_CONNECTED)
        } else if (hidManager.isReady) {
            binding.btnRetryBt.visibility = View.GONE
            if (hidManager.lastStatusMessage.isNotEmpty()) {
                binding.tvBtStatus.text = hidManager.lastStatusMessage
            }
            updateControlButtons(hidManager.lastDeviceState)
        } else {
            updateControlButtons(hidManager.lastDeviceState)
        }
    }

    private fun loadPreferences() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val boundMac = sp.getString(KEY_BOUND_MAC, null)
        val boundName = sp.getString(KEY_BOUND_NAME, null)
        val autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, false)
        val autoReconnectDisconnect = sp.getBoolean(KEY_AUTO_RECONNECT_ON_DISCONNECT, true)
        val scanDelaySec = sp.getInt(KEY_WLAN_SCAN_DELAY_SEC, DEFAULT_WLAN_SCAN_DELAY_SEC)
        val targetSsid = sp.getString(KEY_TARGET_WLAN_SSID, null)

        binding.switchAutoConnect.isChecked = autoConnect
        binding.switchAutoReconnectDisconnect.isChecked = autoReconnectDisconnect
        updateScanDelayText(scanDelaySec)
        updateBoundHostUi(boundMac, boundName)

        if (!targetSsid.isNullOrEmpty()) {
            binding.tvTargetWlan.text = "目标自动连热点: $targetSsid"
            binding.btnClearTargetWlan.visibility = View.VISIBLE
            wifiAdapter.targetSsid = targetSsid
        } else {
            binding.tvTargetWlan.text = "目标自动连热点: 未设置 (点击下方列表绑定)"
            binding.btnClearTargetWlan.visibility = View.GONE
            wifiAdapter.targetSsid = null
        }

        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(KEY_AUTO_CONNECT, isChecked).apply()
        }

        binding.switchAutoReconnectDisconnect.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean(KEY_AUTO_RECONNECT_ON_DISCONNECT, isChecked).apply()
            if (isChecked) {
                // 用户重新开启自动重连：立即清除主动断开拦截标记，并恢复外设就绪状态
                hidManager.resetUserDisconnecting()
                if (!hidManager.isAppRegistered) {
                    hidManager.initialize()
                }
                Toast.makeText(this, "已开启断开自动重连与自愈恢复", Toast.LENGTH_SHORT).show()
            } else {
                // 用户关闭自动重连：若当前未处于连接中，立即注销外设广播休眠，彻底阻止对端私自连入
                if (hidManager.lastDeviceState != BluetoothProfile.STATE_CONNECTED) {
                    hidManager.unregisterHidApp()
                }
                Toast.makeText(this, "已关闭自动重连，并阻止对端私自连入", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 更新绑定热点机信息与自动化策略开关的可用性
     */
    private fun updateBoundHostUi(boundMac: String?, boundName: String?) {
        val hasBound = !boundMac.isNullOrEmpty()
        if (hasBound) {
            binding.tvBoundHost.text = getString(R.string.title_bound_host, "${boundName ?: "未知设备"} ($boundMac)")
            binding.switchAutoConnect.isEnabled = true
            binding.switchAutoReconnectDisconnect.isEnabled = true
            binding.layoutPolicyGroup.alpha = 1.0f
            binding.tvPolicyHint.text = "针对已绑定设备生效"
            binding.tvPolicyHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvBoundHost.text = getString(R.string.status_unbound_host)
            binding.switchAutoConnect.isEnabled = false
            binding.switchAutoReconnectDisconnect.isEnabled = false
            binding.layoutPolicyGroup.alpha = 0.6f
            binding.tvPolicyHint.text = "需先在上方绑定热点机"
            binding.tvPolicyHint.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
        }
        updateControlButtons(hidManager.lastDeviceState)
    }

    /**
     * 根据当前蓝牙连接状态和绑定设备情况，动态更新控制按钮可用性与视觉提示
     */
    private fun updateControlButtons(state: Int) {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasBound = !sp.getString(KEY_BOUND_MAC, null).isNullOrEmpty()

        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                binding.btnReconnectHost.isEnabled = false
                binding.btnReconnectHost.alpha = 0.45f
                binding.btnDisconnectHost.isEnabled = true
                binding.btnDisconnectHost.alpha = 1.0f
            }
            BluetoothProfile.STATE_CONNECTING -> {
                binding.btnReconnectHost.isEnabled = false
                binding.btnReconnectHost.alpha = 0.45f
                binding.btnDisconnectHost.isEnabled = false
                binding.btnDisconnectHost.alpha = 0.45f
            }
            else -> { // STATE_DISCONNECTED 等
                binding.btnReconnectHost.isEnabled = hasBound
                binding.btnReconnectHost.alpha = if (hasBound) 1.0f else 0.45f
                binding.btnDisconnectHost.isEnabled = false
                binding.btnDisconnectHost.alpha = 0.4f
            }
        }
    }

    private fun initViews() {
        // 初始化 WLAN 列表 (点击列表项可设为自动连接的目标热点)
        wifiAdapter = WifiListAdapter { wifiItem ->
            showSetTargetWlanDialog(wifiItem)
        }
        binding.rvWifiList.layoutManager = LinearLayoutManager(this)
        binding.rvWifiList.adapter = wifiAdapter

        // 清除目标热点按钮
        binding.btnClearTargetWlan.setOnClickListener {
            clearTargetWlan()
        }

        // 延时刷新 WLAN 设置按钮
        binding.btnSetScanDelay.setOnClickListener {
            showScanDelayDialog()
        }
        binding.tvScanDelaySetting.setOnClickListener {
            showScanDelayDialog()
        }

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

        // 主动断开与目标热点手机的连接
        binding.btnDisconnectHost.setOnClickListener {
            disconnectFromHost()
        }

        // 绑定/选择已配对的热点手机
        binding.btnBindHost.setOnClickListener {
            showBindHostDialog()
        }

        // 电池优化白名单设置按钮
        binding.btnBatteryOptimization.setOnClickListener {
            checkBatteryOptimizations(forcePrompt = true)
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

            // 核心自动化联动：如果设置了目标热点，且在扫描列表中发现了它，自动发起网络连接！
            val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val targetSsid = sp.getString(KEY_TARGET_WLAN_SSID, null)
            val targetPassword = sp.getString(KEY_TARGET_WLAN_PASSWORD, null)

            if (!targetSsid.isNullOrEmpty()) {
                val currentSsid = wifiScanManager.getCurrentConnectedSsid()
                if (currentSsid.equals(targetSsid, ignoreCase = true)) {
                    android.util.Log.d("MainActivity", "Already connected to target WLAN: $targetSsid")
                } else {
                    val hasTargetInScan = list.any { it.ssid.equals(targetSsid, ignoreCase = true) }
                    if (hasTargetInScan) {
                        Snackbar.make(binding.root, "已扫描到目标热点【$targetSsid】，正在自动接入...", Snackbar.LENGTH_LONG).show()
                        wifiScanManager.connectToWifi(targetSsid, targetPassword) { success, msg ->
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            binding.root.postDelayed({ updateCurrentConnectedWifi() }, 2500)
                        }
                    }
                }
            }
        }

        wifiScanManager.onScanFailedListener = { msg ->
            binding.pbWifiScanning.visibility = View.GONE
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 弹出对话框将选中的 Wi-Fi 设为目标自动连接热点
     */
    private fun showSetTargetWlanDialog(wifiItem: WifiItem) {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTarget = sp.getString(KEY_TARGET_WLAN_SSID, null)
        val currentPwd = sp.getString(KEY_TARGET_WLAN_PASSWORD, "")

        val inputEditText = android.widget.EditText(this).apply {
            hint = "热点密码 (若系统已保存过此密码可留空)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (currentTarget == wifiItem.ssid && !currentPwd.isNullOrEmpty()) {
                setText(currentPwd)
            }
        }

        val container = android.widget.FrameLayout(this).apply {
            setPadding(60, 20, 60, 10)
            addView(inputEditText)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设为自动连接目标热点")
            .setMessage("确定将【${wifiItem.ssid}】设为自动连接的目标热点吗？\n蓝牙连接成功后将自动接入此 Wi-Fi。")
            .setView(container)
            .setPositiveButton("保存并设为目标") { _, _ ->
                val pwd = inputEditText.text?.toString()?.trim()
                sp.edit()
                    .putString(KEY_TARGET_WLAN_SSID, wifiItem.ssid)
                    .putString(KEY_TARGET_WLAN_PASSWORD, pwd)
                    .apply()

                binding.tvTargetWlan.text = "目标自动连热点: ${wifiItem.ssid}"
                binding.btnClearTargetWlan.visibility = View.VISIBLE
                wifiAdapter.targetSsid = wifiItem.ssid
                Toast.makeText(this, "已设置目标热点: ${wifiItem.ssid}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 清除绑定的目标热点
     */
    private fun clearTargetWlan() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sp.edit()
            .remove(KEY_TARGET_WLAN_SSID)
            .remove(KEY_TARGET_WLAN_PASSWORD)
            .apply()

        binding.tvTargetWlan.text = "目标自动连热点: 未设置 (点击下方列表绑定)"
        binding.btnClearTargetWlan.visibility = View.GONE
        wifiAdapter.targetSsid = null
        Toast.makeText(this, "已清除目标热点设置", Toast.LENGTH_SHORT).show()
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

                updateBoundHostUi(selected.address, selected.name)
                Toast.makeText(this, "已绑定热点机: ${selected.name}，可随时点击一键重连", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateScanDelayText(delaySec: Int) {
        binding.tvScanDelaySetting.text = getString(R.string.wifi_scan_delay_setting, delaySec)
    }

    /**
     * 弹出对话框设置连接成功后 WLAN 自动刷新的延时秒数
     */
    private fun showScanDelayDialog() {
        val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentDelay = sp.getInt(KEY_WLAN_SCAN_DELAY_SEC, DEFAULT_WLAN_SCAN_DELAY_SEC)
        val options = arrayOf("1 秒 (极速就绪)", "2 秒", "3 秒", "5 秒 (默认/推荐)", "8 秒", "10 秒 (慢速广播设备)")
        val values = intArrayOf(1, 2, 3, 5, 8, 10)
        var selectedIndex = values.indexOf(currentDelay)
        if (selectedIndex == -1) selectedIndex = 3

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_scan_delay)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val newDelay = values[which]
                sp.edit().putInt(KEY_WLAN_SCAN_DELAY_SEC, newDelay).apply()
                updateScanDelayText(newDelay)
                Toast.makeText(this, "已设置连接后 ${newDelay} 秒自动刷新 WLAN", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
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

        updateControlButtons(BluetoothProfile.STATE_CONNECTING)
        Toast.makeText(this, getString(R.string.msg_connecting_host, name), Toast.LENGTH_SHORT).show()
        HotspotWakeService.connect(this, mac)
    }

    /**
     * 主动断开当前蓝牙连接并阻止对端自动回连
     */
    private fun disconnectFromHost() {
        Toast.makeText(this, getString(R.string.msg_host_disconnected), Toast.LENGTH_SHORT).show()
        HotspotWakeService.disconnect(this)
        hidManager.unregisterHidApp()
        updateControlButtons(BluetoothProfile.STATE_DISCONNECTED)
        binding.tvBtStatus.text = "已主动断开蓝牙连接 (外设已休眠)"
        binding.tvConnectedDevice.text = "未连接目标手机"
        binding.viewStatusDot.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.status_disconnected)
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
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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

        // 启动后台前台保活服务，确保切后台和锁屏不被系统杀掉或断开蓝牙
        HotspotWakeService.startService(this)

        hidManager.initialize()
        updateCurrentConnectedWifi()
        performWifiScan()
        checkBatteryOptimizations()
    }

    /**
     * 引导用户忽略电池优化，确保切到后台与锁屏后系统不杀进程、不断蓝牙
     */
    private fun checkBatteryOptimizations(forcePrompt: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

            if (isIgnoring) {
                binding.tvBatteryTip.text = "🛡️ 电池白名单已配置 (后台稳定保活)"
                binding.btnBatteryOptimization.text = "已配置"
                binding.btnBatteryOptimization.alpha = 0.6f
            } else {
                binding.tvBatteryTip.text = "⚠️ 未忽略电池优化 (切后台可能受限)"
                binding.btnBatteryOptimization.text = "去加白名单"
                binding.btnBatteryOptimization.alpha = 1.0f

                if (forcePrompt) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(this, "请在系统设置 -> 电池优化中，将本应用设为无限制后台耗电", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Snackbar.make(
                        binding.root,
                        "为了保证切到后台与锁屏后蓝牙不掉线，建议将本 App 设为【无限制/忽略电池优化】",
                        Snackbar.LENGTH_LONG
                    ).setAction("去设置") {
                        checkBatteryOptimizations(forcePrompt = true)
                    }.show()
                }
            }
        }
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
                updateControlButtons(BluetoothProfile.STATE_CONNECTED)

                // 核心功能点：蓝牙连接成功后延时触发 WLAN 扫描刷新 (等待主机热点无线广播就绪)
                val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val delaySec = sp.getInt(KEY_WLAN_SCAN_DELAY_SEC, DEFAULT_WLAN_SCAN_DELAY_SEC)
                val delayMs = delaySec * 1000L

                pendingScanRunnable?.let { binding.root.removeCallbacks(it) }

                Snackbar.make(binding.root, "已连接热点机！等待主机热点开启，${delaySec}秒后自动刷新...", Snackbar.LENGTH_SHORT)
                    .setAction("立即刷新") {
                        pendingScanRunnable?.let { binding.root.removeCallbacks(it) }
                        performWifiScan()
                    }
                    .show()

                val scanRunnable = Runnable {
                    if (isDestroyed || isFinishing) return@Runnable
                    performWifiScan()
                    Toast.makeText(this@MainActivity, "已自动为您刷新 WLAN 列表", Toast.LENGTH_SHORT).show()
                }
                pendingScanRunnable = scanRunnable
                binding.root.postDelayed(scanRunnable, delayMs)
            }

            BluetoothProfile.STATE_CONNECTING -> {
                binding.tvBtStatus.text = getString(R.string.bt_status_connecting)
                binding.viewStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_connecting)
                updateControlButtons(BluetoothProfile.STATE_CONNECTING)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                pendingScanRunnable?.let { binding.root.removeCallbacks(it) }
                binding.tvBtStatus.text = getString(R.string.bt_status_disconnected)
                binding.tvConnectedDevice.text = "未连接目标手机"
                binding.viewStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_disconnected)
                updateControlButtons(BluetoothProfile.STATE_DISCONNECTED)
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
        pendingScanRunnable?.let { binding.root.removeCallbacks(it) }
        if (isBtReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                // ignore
            }
            isBtReceiverRegistered = false
        }
        wifiScanManager.unregisterReceiver()
        // 关键改动：MainActivity 销毁时只移除当前 UI 的监听器，切勿调用 release()，
        // 蓝牙连接与外设注册由 HotspotWakeService 前台服务在后台持续守护
        hidManager.removeListener(this)
    }
}
