package com.example.bluewifi.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * WLAN 自动开启助手。
 *
 * 目标蓝牙连接成功后，若 WLAN 处于关闭状态则自动开启，避免后续热点扫描直接失败。
 *
 * 平台限制说明（属系统策略，普通应用无法绕过）：
 * - Android 9 (API 28) 及以下：可通过 [WifiManager.setWifiEnabled] 真正自动开启 WLAN。
 * - Android 10 (API 29) 及以上：只要应用 targetSdk >= 29，系统会让 [WifiManager.setWifiEnabled]
 *   恒返回 false 且不产生任何效果（官方行为，与权限无关）。此时 [enableIfNeeded] 会回调
 *   [EnableResult.BLOCKED]，由调用方引导用户点击系统 WLAN 悬浮面板一键开启；用户开启后
 *   仍会触发 [onWifiEnabledListener]，自动续接后续的延时扫描流程。
 */
class WifiAutoEnabler(context: Context) {

    /** WLAN 自动开启的结果 */
    enum class EnableResult {
        /** 触发时 WLAN 本来就是开启的 */
        ALREADY_ON,

        /** 已下发开启指令并确认 WLAN 真正进入开启状态 */
        ENABLED,

        /** 被系统策略拦截，或超时未确认开启，需要引导用户手动开启 */
        BLOCKED
    }

    companion object {
        private const val TAG = "WifiAutoEnabler"

        /** 下发开启指令后，轮询确认 WLAN 真正开启的最长时间 */
        private const val ENABLE_CONFIRM_TIMEOUT_MS = 10000L

        /** 开启确认轮询间隔 */
        private const val ENABLE_POLL_INTERVAL_MS = 400L
    }

    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isStateReceiverRegistered = false
    private var pendingConfirmRunnable: Runnable? = null

    /** WLAN 由关闭变为开启时的回调 (也覆盖用户稍后手动开启的情况) */
    var onWifiEnabledListener: (() -> Unit)? = null

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(
                WifiManager.EXTRA_WIFI_STATE,
                WifiManager.WIFI_STATE_UNKNOWN
            )
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                Log.d(TAG, "WLAN enabled broadcast received")
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
     * 若 WLAN 未开启则尝试自动开启，并等待系统确认开启完成后再回调，避免调用方
     * 在 WLAN 尚未就绪时就发起扫描 (必然失败)。
     *
     * 相比只依赖 WIFI_STATE_CHANGED 广播，这里额外做了轮询兜底：部分 ROM 会漏发广播，
     * 一旦漏发整条自动化链路会永久卡死。
     *
     * [onResult] 保证在主线程回调，且至多回调一次。
     */
    fun enableIfNeeded(
        timeoutMs: Long = ENABLE_CONFIRM_TIMEOUT_MS,
        onResult: (EnableResult) -> Unit
    ) {
        if (isWifiEnabled()) {
            mainHandler.post { onResult(EnableResult.ALREADY_ON) }
            return
        }

        if (!requestWifiEnable()) {
            Log.w(
                TAG,
                "system blocks wifi enable (sdk=${Build.VERSION.SDK_INT}), guidance required"
            )
            mainHandler.post { onResult(EnableResult.BLOCKED) }
            return
        }

        awaitWifiEnabled(timeoutMs) { enabled ->
            onResult(if (enabled) EnableResult.ENABLED else EnableResult.BLOCKED)
        }
    }

    /** 取消尚未完成的开启确认轮询 (页面或服务销毁时调用) */
    fun cancelPendingEnableCheck() {
        pendingConfirmRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingConfirmRunnable = null
    }

    /** 下发开启 WLAN 指令，返回系统是否受理 */
    @Suppress("DEPRECATION")
    private fun requestWifiEnable(): Boolean = try {
        val accepted = wifiManager.setWifiEnabled(true)
        Log.i(TAG, "setWifiEnabled(true) accepted=$accepted (sdk=${Build.VERSION.SDK_INT})")
        accepted
    } catch (e: Exception) {
        Log.w(TAG, "setWifiEnabled failed: ${e.message}")
        false
    }

    /**
     * 轮询等待 WLAN 真正进入开启状态，超时则判定为失败，避免调用方无限等待。
     */
    private fun awaitWifiEnabled(timeoutMs: Long, onResult: (Boolean) -> Unit) {
        cancelPendingEnableCheck()

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val poll = object : Runnable {
            override fun run() {
                pendingConfirmRunnable = null
                when {
                    isWifiEnabled() -> {
                        Log.i(TAG, "WLAN enable confirmed")
                        onResult(true)
                    }
                    SystemClock.elapsedRealtime() >= deadline -> {
                        Log.w(TAG, "WLAN enable not confirmed within ${timeoutMs}ms")
                        onResult(false)
                    }
                    else -> {
                        pendingConfirmRunnable = this
                        mainHandler.postDelayed(this, ENABLE_POLL_INTERVAL_MS)
                    }
                }
            }
        }

        pendingConfirmRunnable = poll
        mainHandler.postDelayed(poll, ENABLE_POLL_INTERVAL_MS)
    }

    /** 注册 WLAN 开关状态广播 (用于等待自动开启完成或用户手动开启) */
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
