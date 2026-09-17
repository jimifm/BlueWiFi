package com.example.bluewifi.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

@SuppressLint("MissingPermission")
class WifiScanManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiScanManager"
    }

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isReceiverRegistered = false

    var onScanCompletedListener: ((List<WifiItem>) -> Unit)? = null
    var onScanFailedListener: ((String) -> Unit)? = null

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "Scan results available. Success = $success")
                processScanResults()
            }
        }
    }

    /**
     * 注册广播接收器
     */
    fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            context.registerReceiver(wifiScanReceiver, filter)
            isReceiverRegistered = true
        }
    }

    /**
     * 反注册广播接收器
     */
    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiScanReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering wifi receiver", e)
            }
            isReceiverRegistered = false
        }
    }

    /**
     * 发起 WLAN 列表扫描刷新
     */
    fun startScan(): Boolean {
        if (!wifiManager.isWifiEnabled) {
            onScanFailedListener?.invoke("WLAN 未开启，请先开启 WLAN")
            return false
        }

        registerReceiver()
        val started = wifiManager.startScan()
        if (!started) {
            Log.w(TAG, "WifiManager.startScan() returned false (throttled by system)")
            // Android 9+ 对 startScan 有节流限制，即便返回 false，现有的 scanResults 依然可用
            processScanResults()
        }
        return started
    }

    /**
     * 读取并处理当前扫描到的 Wi-Fi 列表
     */
    fun processScanResults() {
        try {
            val results: List<ScanResult> = wifiManager.scanResults ?: emptyList()
            // 过滤空 SSID，去重并按信号强度由强到弱排序
            val wifiMap = mutableMapOf<String, WifiItem>()

            for (result in results) {
                val ssid = result.SSID?.trim()?.replace("\"", "") ?: ""
                if (ssid.isEmpty()) continue

                val level = result.level
                val percent = WifiManager.calculateSignalLevel(level, 100)

                val item = WifiItem(
                    ssid = ssid,
                    bssid = result.BSSID ?: "",
                    level = level,
                    signalPercent = percent,
                    capabilities = result.capabilities ?: "",
                    frequency = result.frequency
                )

                // 若同一 SSID 有多个 AP，保留信号更强的那个
                val existing = wifiMap[ssid]
                if (existing == null || existing.level < item.level) {
                    wifiMap[ssid] = item
                }
            }

            val sortedList = wifiMap.values.sortedByDescending { it.level }
            onScanCompletedListener?.invoke(sortedList)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission missing for scanResults", e)
            onScanFailedListener?.invoke("缺少定位或附近设备权限，无法获取 WLAN 列表")
        }
    }

    /**
     * 获取当前终端已连接的 Wi-Fi SSID
     */
    fun getCurrentConnectedSsid(): String {
        return try {
            val connectionInfo: WifiInfo? = wifiManager.connectionInfo
            val ssid = connectionInfo?.ssid?.replace("\"", "") ?: "<未知>"
            if (ssid == "<unknown ssid>" || ssid.isEmpty()) {
                "未连接到 Wi-Fi"
            } else {
                ssid
            }
        } catch (e: Exception) {
            "未连接到 Wi-Fi"
        }
    }

    /**
     * 打开系统原生 WLAN 设置界面并刷新
     */
    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open Wi-Fi settings", e)
        }
    }

    /**
     * 请求系统连接指定目标 Wi-Fi (兼容 Android 10+ 网络建议与 Android 9 配置连接)
     */
    fun connectToWifi(ssid: String, password: String? = null, onResult: ((Boolean, String) -> Unit)? = null) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10+ 官方建议网络连接机制 (系统会自动以最高优先级无缝漫游接入)
                val builder = android.net.wifi.WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsAppInteractionRequired(false)

                if (!password.isNullOrEmpty()) {
                    builder.setWpa2Passphrase(password)
                }

                val suggestion = builder.build()
                val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
                Log.d(TAG, "addNetworkSuggestions result status: $status for $ssid")

                // 促使系统重新扫描并优先接入建议网络
                wifiManager.reconnect()
                onResult?.invoke(true, "已提交热点自动连接请求，等待系统接入")
            } else {
                // Android 9 (API 28) 传统 WifiConfiguration 方式
                val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (!password.isNullOrEmpty()) {
                        preSharedKey = "\"$password\""
                    } else {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val netId = wifiManager.addNetwork(wifiConfig)
                if (netId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reconnect()
                    onResult?.invoke(true, "已下发网络配置并请求接入")
                } else {
                    wifiManager.reconnect()
                    onResult?.invoke(true, "已请求重新关联网络")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to wifi $ssid", e)
            try {
                wifiManager.reconnect()
            } catch (ex: Exception) {
                // ignore
            }
            onResult?.invoke(false, "连接请求异常: ${e.message}")
        }
    }
}
