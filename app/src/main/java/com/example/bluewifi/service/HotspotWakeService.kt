package com.example.bluewifi.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.bluewifi.MainActivity
import com.example.bluewifi.R
import com.example.bluewifi.hid.BluetoothHidManager
import com.example.bluewifi.hid.HidDeviceListener

@SuppressLint("MissingPermission")
class HotspotWakeService : Service(), HidDeviceListener {

    companion object {
        private const val TAG = "HotspotWakeService"
        const val CHANNEL_ID = "channel_hotspot_wake_keepalive"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.bluewifi.action.START"
        const val ACTION_STOP = "com.example.bluewifi.action.STOP"
        const val ACTION_CONNECT = "com.example.bluewifi.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.bluewifi.action.DISCONNECT"
        const val EXTRA_DEVICE_MAC = "extra_device_mac"

        private const val PREFS_NAME = "blue_wifi_prefs"
        private const val KEY_BOUND_MAC = "bound_host_mac"
        private const val KEY_BOUND_NAME = "bound_host_name"
        private const val KEY_AUTO_CONNECT = "auto_connect_on_start"
        const val KEY_AUTO_RECONNECT_ON_DISCONNECT = "auto_reconnect_on_disconnect"

        private const val KEEP_ALIVE_INTERVAL_MS = 25000L // 25秒心跳一次
        private const val RECONNECT_DELAY_MS = 3500L       // 意外断开后3.5秒重试

        fun startService(context: Context) {
            val intent = Intent(context, HotspotWakeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, HotspotWakeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun connect(context: Context, mac: String? = null) {
            val intent = Intent(context, HotspotWakeService::class.java).apply {
                action = ACTION_CONNECT
                if (!mac.isNullOrEmpty()) {
                    putExtra(EXTRA_DEVICE_MAC, mac)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, HotspotWakeService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private lateinit var hidManager: BluetoothHidManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentHostName: String? = null
    private var currentHostMac: String? = null
    private var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED

    // 心跳保活任务
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Executing HID keep-alive pulse")
                hidManager.sendKeepAliveReport()
                mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }

    // 异常断开自动重连任务
    private val reconnectRunnable = Runnable {
        if (connectionState != BluetoothProfile.STATE_CONNECTED && !hidManager.isUserDisconnecting) {
            val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoReconnect = sp.getBoolean(KEY_AUTO_RECONNECT_ON_DISCONNECT, true)
            if (!autoReconnect) {
                Log.i(TAG, "Auto-reconnect on disconnect is disabled by user setting")
                return@Runnable
            }
            val boundMac = currentHostMac ?: sp.getString(KEY_BOUND_MAC, null)
            if (!boundMac.isNullOrEmpty() && hidManager.isReady) {
                Log.i(TAG, "Attempting auto-reconnect to bound host: $boundMac")
                updateNotification("正在尝试自动重连热点机...", isConnecting = true)
                hidManager.connect(boundMac)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HotspotWakeService onCreate")

        hidManager = BluetoothHidManager.getInstance(applicationContext)
        hidManager.addListener(this)

        initWakeLock()
        createNotificationChannel()

        // 立即启动前台服务，防止 ANR
        startForegroundWithNotification(buildNotification("热点唤醒后台保活已启动", "等待连接热点机"))

        // 初始化蓝牙 HID
        if (!hidManager.isReady) {
            hidManager.initialize()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand with action: $action")

        when (action) {
            ACTION_START -> {
                val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, false)
                val boundMac = sp.getString(KEY_BOUND_MAC, null)
                val boundName = sp.getString(KEY_BOUND_NAME, "热点手机")
                currentHostMac = boundMac
                currentHostName = boundName

                if (autoConnect && !boundMac.isNullOrEmpty() && hidManager.isReady) {
                    hidManager.connect(boundMac)
                }
            }

            ACTION_CONNECT -> {
                val mac = intent?.getStringExtra(EXTRA_DEVICE_MAC)
                val targetMac = if (!mac.isNullOrEmpty()) {
                    mac
                } else {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_BOUND_MAC, null)
                }

                if (!targetMac.isNullOrEmpty()) {
                    currentHostMac = targetMac
                    updateNotification("正在连接热点机...", isConnecting = true)
                    hidManager.connect(targetMac)
                }
            }

            ACTION_DISCONNECT -> {
                mainHandler.removeCallbacks(reconnectRunnable)
                hidManager.disconnect()
            }

            ACTION_STOP -> {
                mainHandler.removeCallbacks(keepAliveRunnable)
                mainHandler.removeCallbacks(reconnectRunnable)
                hidManager.disconnect()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BlueWiFi:HotspotWakeKeepAliveLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create WakeLock: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(30 * 60 * 1000L) // 单次保活最多锁定30分钟，避免无限消耗
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "热点唤醒后台连接保活",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持蓝牙 HID 连接及断线自动重连，确保热点无感连接"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }
    }

    private fun buildNotification(title: String, content: String, isConnecting: Boolean = false): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 快捷操作：重连
        val reconnectIntent = Intent(this, HotspotWakeService::class.java).apply {
            action = ACTION_CONNECT
        }
        val pReconnect = PendingIntent.getService(
            this,
            1,
            reconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 快捷操作：断开
        val disconnectIntent = Intent(this, HotspotWakeService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pDisconnect = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "一键重连", pReconnect)

        if (connectionState == BluetoothProfile.STATE_CONNECTED) {
            builder.addAction(0, "断开", pDisconnect)
        }

        return builder.build()
    }

    private fun updateNotification(content: String, title: String = "热点唤醒后台守护", isConnecting: Boolean = false) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, content, isConnecting))
    }

    // === HidDeviceListener 回调 ===

    override fun onAppRegistered(registered: Boolean) {
        if (registered) {
            val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoConnect = sp.getBoolean(KEY_AUTO_CONNECT, false)
            val boundMac = sp.getString(KEY_BOUND_MAC, null)
            val boundName = sp.getString(KEY_BOUND_NAME, "热点手机")
            if (autoConnect && !boundMac.isNullOrEmpty() && connectionState != BluetoothProfile.STATE_CONNECTED) {
                updateNotification("就绪，正在连接 $boundName...", isConnecting = true)
                mainHandler.postDelayed({
                    hidManager.connect(boundMac)
                }, 500)
            } else {
                updateNotification("蓝牙外设服务就绪，随时可回连")
            }
        } else {
            updateNotification("蓝牙外设服务已注销")
        }
    }

    override fun onDeviceStateChanged(device: BluetoothDevice, state: Int) {
        connectionState = state
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                currentHostMac = device.address
                currentHostName = device.name ?: "未知设备"
                acquireWakeLock()

                // 取消任何待处理的重连
                mainHandler.removeCallbacks(reconnectRunnable)

                // 启动心跳定时器
                mainHandler.removeCallbacks(keepAliveRunnable)
                mainHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)

                updateNotification("已稳定连接: ${device.name ?: device.address} (心跳保活中)")
            }

            BluetoothProfile.STATE_CONNECTING -> {
                updateNotification("正在连接: ${device.name ?: device.address} ...", isConnecting = true)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                // 停止心跳
                mainHandler.removeCallbacks(keepAliveRunnable)
                releaseWakeLock()

                if (!hidManager.isUserDisconnecting) {
                    val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val autoReconnect = sp.getBoolean(KEY_AUTO_RECONNECT_ON_DISCONNECT, true)
                    val boundMac = sp.getString(KEY_BOUND_MAC, null)
                    if (autoReconnect && !boundMac.isNullOrEmpty()) {
                        updateNotification("与热点机断开，${RECONNECT_DELAY_MS / 1000}秒后自动重试...")
                        mainHandler.removeCallbacks(reconnectRunnable)
                        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                    } else if (!autoReconnect) {
                        mainHandler.removeCallbacks(reconnectRunnable)
                        updateNotification("与热点机断开 (已关闭断开自动重连)")
                    } else {
                        mainHandler.removeCallbacks(reconnectRunnable)
                        updateNotification("蓝牙已断开")
                    }
                } else {
                    updateNotification("蓝牙已主动断开")
                }
            }
        }
    }

    override fun onStatusMessage(message: String) {
        // 更新通知状态信息
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            updateNotification(message)
        }
    }

    override fun onError(message: String) {
        updateNotification("服务提示: $message")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "HotspotWakeService onDestroy")
        mainHandler.removeCallbacks(keepAliveRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        releaseWakeLock()
        hidManager.removeListener(this)
    }
}
