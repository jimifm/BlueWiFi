package com.example.bluewifi.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * WLAN 自动开启助手。
 *
 * 目标蓝牙连接成功后，若 WLAN 处于关闭状态则自动开启，避免后续热点扫描直接失败。
 *
 * 兼容性说明：
 * - Android 9 及以下：可通过 [WifiManager.setWifiEnabled] 真正自动开启 WLAN。
 * - Android 10 及以上：系统禁止普通应用直接开关 WLAN，[tryEnableWifi] 会返回 false，
 *   此时需由调用方引导用户通过 [buildWifiPanelIntent] 一键开启（开启完成后通过
 *   [onWifiEnabledListener] 回调继续后续流程）。
 */
class WifiAutoEnabler(context: Context) {

    companion object {
        private const val TAG = "WifiAutoEnabler"
    }

    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var isStateReceiverRegistered = false

    /** WLAN 由关闭变为开启时的回调 */
    var onWifiEnabledListener: (() -> Unit)? = null

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(
                WifiManager.EXTRA_WIFI_STATE,
                WifiManager.WIFI_STATE_UNKNOWN
            )
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                Log.d(TAG, "WLAN enabled")
                onWifiEnabledListener?.invoke()
            }
        }
    }

    /** 当前 WLAN 是否处于开启状态 */
    fun isWifiEnabled(): Boolean = try {
        wifiManager.isWifiEnabled
    } catch (e: Exception) {
        Log.w(TAG, "isWifiEnabled failed: ${e.message}")
        false
    }

    /**
     * 尝试开启 WLAN。
     *
     * @return true 表示系统已接受开启请求（Android 9 及以下，或未做限制的定制系统）；
     *         false 表示被系统策略拦截（Android 10+ 普通应用常见），需引导用户手动开启。
     */
    @Suppress("DEPRECATION")
    fun tryEnableWifi(): Boolean {
        if (isWifiEnabled()) return true
        return try {
            val accepted = wifiManager.setWifiEnabled(true)
            Log.i(TAG, "setWifiEnabled(true) accepted=$accepted")
            accepted
        } catch (e: Exception) {
            Log.w(TAG, "setWifiEnabled failed: ${e.message}")
            false
        }
    }

    /** 注册 WLAN 开关状态广播（用于等待自动开启完成） */
    fun registerStateReceiver() {
        if (isStateReceiverRegistered) return
        try {
            appContext.registerReceiver(
                wifiStateReceiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            )
            isStateReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "register wifi state receiver failed: ${e.message}")
        }
    }

    /** 反注册 WLAN 开关状态广播 */
    fun unregisterStateReceiver() {
        if (!isStateReceiverRegistered) return
        try {
            appContext.unregisterReceiver(wifiStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister wifi state receiver failed: ${e.message}")
        }
        isStateReceiverRegistered = false
    }

    /**
     * 构造引导用户开启 WLAN 的跳转 Intent。
     * Android 10+ 优先使用系统 WLAN 悬浮设置面板，低版本回退到 WLAN 设置页。
     */
    fun buildWifiPanelIntent(): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
