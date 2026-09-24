package com.example.bluewifi.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val PROXY_TIMEOUT_MS = 6000L
        private const val PREFS_NAME = "blue_wifi_prefs"
        private const val KEY_AUTO_RECONNECT_ON_DISCONNECT = "auto_reconnect_on_disconnect"

        @Volatile
        private var instance: BluetoothHidManager? = null

        fun getInstance(context: Context): BluetoothHidManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothHidManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    var hidDevice: BluetoothHidDevice? = null
        private set

    var isAppRegistered = false
        private set

    val isReady: Boolean
        get() = hidDevice != null && isAppRegistered

    var connectedDevice: BluetoothDevice? = null
        private set

    var lastDeviceState: Int = BluetoothProfile.STATE_DISCONNECTED
        private set

    var lastStatusMessage: String = ""
        private set

    var isUserDisconnecting: Boolean = false
        private set

    var isUserInitiatedConnect: Boolean = false
        private set

    fun markUserInitiatedConnect(initiated: Boolean) {
        isUserInitiatedConnect = initiated
        if (initiated) {
            isUserDisconnecting = false
        }
    }

    fun resetUserDisconnecting() {
        isUserDisconnecting = false
    }

    private val listeners = CopyOnWriteArraySet<HidDeviceListener>()

    fun addListener(l: HidDeviceListener) {
        listeners.add(l)
    }

    fun removeListener(l: HidDeviceListener) {
        listeners.remove(l)
    }

    var listener: HidDeviceListener?
        get() = listeners.firstOrNull()
        set(value) {
            listeners.clear()
            if (value != null) {
                listeners.add(value)
            }
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var isInitializing = false
    private val timeoutRunnable = Runnable {
        if (hidDevice == null && isInitializing) {
            isInitializing = false
            Log.w(TAG, "getProfileProxy timeout. HID Device profile not responding.")
            val errMsg = "获取蓝牙外设服务超时！当前系统可能未开启 Bluetooth HID Device 支持，请尝试重启蓝牙或检查系统设置"
            listeners.forEach { it.onError(errMsg) }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            mainHandler.removeCallbacks(timeoutRunnable)
            isInitializing = false

            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile connected successfully")
                hidDevice = proxy as? BluetoothHidDevice
                val msg = "已获取蓝牙外设代理，正在向系统注册键鼠描述符..."
                lastStatusMessage = msg
                mainHandler.post {
                    listeners.forEach { it.onStatusMessage(msg) }
                }
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile disconnected")
                hidDevice = null
                isAppRegistered = false
                mainHandler.post {
                    listeners.forEach {
                        it.onAppRegistered(false)
                        it.onError("蓝牙外设服务已与系统断开")
                    }
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered = $registered, device = ${pluggedDevice?.address}")
            isAppRegistered = registered
            mainHandler.post {
                listeners.forEach { it.onAppRegistered(registered) }
                val msg = if (registered) "蓝牙外设服务注册成功，随时可连接" else "蓝牙外设服务已注销"
                lastStatusMessage = msg
                if (registered) {
                    listeners.forEach { it.onStatusMessage(msg) }
                } else {
                    listeners.forEach { it.onError(msg) }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device = ${device.address}, state = $state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val autoReconnect = sp.getBoolean(KEY_AUTO_RECONNECT_ON_DISCONNECT, true)

                // 核心拦截：如果未开启断开自动重连，且本次连接非用户主动触发（即对端主机系统私自回连）
                // 或者当前仍处于主动断开状态，立即强行断开并拒绝对端连入
                if ((!autoReconnect && !isUserInitiatedConnect) || isUserDisconnecting) {
                    Log.w(
                        TAG,
                        "Rejecting incoming host connection from ${device.address}: " +
                                "autoReconnect=$autoReconnect, isUserInitiated=$isUserInitiatedConnect, isUserDisconnecting=$isUserDisconnecting"
                    )
                    try {
                        hidDevice?.disconnect(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error rejecting incoming connection", e)
                    }
                    connectedDevice = null
                    val msg = if (isUserDisconnecting) {
                        "已拦截对端主机的回连 (当前处于主动断开状态)"
                    } else {
                        "已拦截对端主机的自动回连 (已关闭断开自动重连)"
                    }
                    lastStatusMessage = msg
                    lastDeviceState = BluetoothProfile.STATE_DISCONNECTED
                    mainHandler.post {
                        listeners.forEach {
                            it.onStatusMessage(msg)
                            it.onDeviceStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
                        }
                    }
                    return
                }

                connectedDevice = device
                isUserDisconnecting = false
                isUserInitiatedConnect = true
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                }
                isUserInitiatedConnect = false
            }
            lastDeviceState = state
            mainHandler.post {
                listeners.forEach { it.onDeviceStateChanged(device, state) }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            if (device != null && hidDevice != null) {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            if (device != null && hidDevice != null) {
                hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            }
        }
    }

    /**
     * 初始化并获取 HID Device Profile Proxy
     */
    fun initialize() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            listener?.onError("当前设备不支持蓝牙硬件")
            return
        }

        if (!adapter.isEnabled) {
            listener?.onError("系统蓝牙未开启，请先开启蓝牙")
            return
        }

        if (hidDevice != null && isAppRegistered) {
            listener?.onAppRegistered(true)
            return
        }

        isInitializing = true
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, PROXY_TIMEOUT_MS)

        mainHandler.post {
            listener?.onStatusMessage("正在请求系统蓝牙外设服务 (HID Device Profile)...")
        }

        // 先尝试用 applicationContext 绑定服务
        var success = adapter.getProfileProxy(
            context.applicationContext,
            serviceListener,
            BluetoothProfile.HID_DEVICE
        )

        // 若失败，尝试使用 activity context 再次绑定
        if (!success) {
            Log.w(TAG, "getProfileProxy with applicationContext failed, trying direct context...")
            success = adapter.getProfileProxy(
                context,
                serviceListener,
                BluetoothProfile.HID_DEVICE
            )
        }

        if (!success) {
            mainHandler.removeCallbacks(timeoutRunnable)
            isInitializing = false
            Log.e(TAG, "getProfileProxy returned false. HID Device is not supported by this device/ROM.")
            listener?.onError("当前手机系统 (ROM) 未启用 Bluetooth HID Device 特性，无法模拟外设")
        }
    }

    /**
     * 重新初始化
     */
    fun reinitialize() {
        release()
        isUserDisconnecting = false
        isUserInitiatedConnect = false
        initialize()
    }

    /**
     * 注册 HID SDP 配置
     * 采用对标“妙妙触控”的标准纯蓝牙鼠标描述符与多重回退注册策略
     */
    private fun registerHidApp() {
        val hid = hidDevice
        if (hid == null) {
            Log.e(TAG, "registerHidApp failed: hidDevice is null")
            return
        }

        // 1. 清理旧应用状态残留，防止因重复注册被系统直接返回 false
        try {
            hid.unregisterApp()
        } catch (e: Exception) {
            // ignore
        }

        // 2. 对标“妙妙触控”的标准纯蓝牙鼠标 SDP 配置
        val mouseSdp = BluetoothHidDeviceAppSdpSettings(
            "Bluetooth Mouse",
            "Wireless Bluetooth Optical Mouse",
            "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            HidConsts.MOUSE_REPORT_DESCRIPTOR
        )

        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        var registered = false

        // 策略 A: 纯鼠标 + 标准 QoS (符合大部分官方规范栈)
        try {
            Log.d(TAG, "Registering strategy A: Pure Mouse with QoS...")
            registered = hid.registerApp(mouseSdp, qos, qos, executor, hidCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Strategy A exception", e)
        }

        // 策略 B: 纯鼠标 + null QoS (部分 ROM 驱动要求 QoS 为 null)
        if (!registered) {
            try {
                Log.d(TAG, "Registering strategy B: Pure Mouse with null QoS...")
                registered = hid.registerApp(mouseSdp, null, null, executor, hidCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Strategy B exception", e)
            }
        }

        // 策略 C: 备用 Combo 复合描述符
        if (!registered) {
            val comboSdp = BluetoothHidDeviceAppSdpSettings(
                "Bluetooth Combo",
                "Android Combo Controller",
                "Android",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidConsts.COMBO_REPORT_DESCRIPTOR
            )
            try {
                Log.d(TAG, "Registering strategy C: Combo Descriptor...")
                registered = hid.registerApp(comboSdp, null, null, executor, hidCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Strategy C exception", e)
            }
        }

        Log.d(TAG, "Final registerApp result: $registered")
        if (registered) {
            mainHandler.post {
                listener?.onStatusMessage("已成功提交鼠标外设描述符，等待系统就绪...")
            }
        } else {
            mainHandler.post {
                listener?.onError("系统蓝牙底层拒绝注册描述符，请尝试开关一次蓝牙后点击重试")
            }
        }
    }

    /**
     * 发送鼠标相对位移和按键报文 (纯鼠标无 Report ID，ID 传 0)
     */
    fun sendMouseReport(dx: Byte, dy: Byte, leftBtn: Boolean, rightBtn: Boolean, wheel: Byte = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        var btnMask: Byte = 0
        if (leftBtn) btnMask = (btnMask.toInt() or 0x01).toByte()
        if (rightBtn) btnMask = (btnMask.toInt() or 0x02).toByte()

        val report = byteArrayOf(btnMask, dx, dy, wheel)
        executor.execute {
            try {
                hid.sendReport(device, 0, report)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending mouse report", e)
            }
        }
    }

    /**
     * 点击鼠标左键
     */
    fun clickLeftMouse() {
        sendMouseReport(0, 0, leftBtn = true, rightBtn = false)
        mainHandler.postDelayed({
            sendMouseReport(0, 0, leftBtn = false, rightBtn = false)
        }, 50)
    }

    /**
     * 点击鼠标右键
     */
    fun clickRightMouse() {
        sendMouseReport(0, 0, leftBtn = false, rightBtn = true)
        mainHandler.postDelayed({
            sendMouseReport(0, 0, leftBtn = false, rightBtn = false)
        }, 50)
    }

    /**
     * 单击键盘按键
     */
    fun tapKey(keyCode: Byte, modifier: Byte = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        executor.execute {
            try {
                // 按下
                val pressReport = byteArrayOf(modifier, 0, keyCode, 0, 0, 0, 0, 0)
                hid.sendReport(device, HidConsts.REPORT_ID_KEYBOARD.toInt(), pressReport)

                Thread.sleep(50)

                // 松开
                val releaseReport = ByteArray(8)
                hid.sendReport(device, HidConsts.REPORT_ID_KEYBOARD.toInt(), releaseReport)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending keyboard key", e)
            }
        }
    }

    /**
     * 发送 Consumer Control 多媒体/系统键
     */
    fun tapConsumerKey(consumerCode: Short) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        executor.execute {
            try {
                val byte1 = (consumerCode.toInt() and 0xFF).toByte()
                val byte2 = ((consumerCode.toInt() shr 8) and 0xFF).toByte()
                val pressReport = byteArrayOf(byte1, byte2)
                hid.sendReport(device, HidConsts.REPORT_ID_CONSUMER.toInt(), pressReport)

                Thread.sleep(50)

                val releaseReport = byteArrayOf(0, 0)
                hid.sendReport(device, HidConsts.REPORT_ID_CONSUMER.toInt(), releaseReport)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending consumer key", e)
            }
        }
    }

    /**
     * 发送心跳空报文，防止主力机蓝牙节能策略误切断 L2CAP 链路
     */
    fun sendKeepAliveReport() {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return
        val report = byteArrayOf(0, 0, 0, 0)
        executor.execute {
            try {
                hid.sendReport(device, 0, report)
                Log.d(TAG, "Keep-alive HID null report sent to ${device.address}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send keep-alive report: ${e.message}")
            }
        }
    }

    /**
     * 获取系统已配对的蓝牙设备列表
     */
    fun getBondedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    /**
     * 主动向已配对的目标 Host 手机 (SIM卡手机) 发起 HID 连接
     */
    fun connect(device: BluetoothDevice): Boolean {
        isUserDisconnecting = false
        val hid = hidDevice
        if (hid == null) {
            Log.w(TAG, "Cannot connect: hidDevice proxy is null")
            isUserInitiatedConnect = false
            return false
        }
        if (!isAppRegistered) {
            Log.w(TAG, "Cannot connect: hid application is not registered yet")
            isUserInitiatedConnect = false
            return false
        }
        Log.d(TAG, "Initiating HID connection to ${device.name} (${device.address})")
        val success = try {
            hid.connect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling hid.connect", e)
            false
        }
        isUserInitiatedConnect = success
        return success
    }

    /**
     * 根据 MAC 地址主动发起连接
     */
    fun connect(macAddress: String): Boolean {
        isUserDisconnecting = false
        val adapter = bluetoothAdapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) return false
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            return false
        }
        return connect(device)
    }

    /**
     * 断开当前连接的目标设备
     */
    fun disconnect() {
        isUserDisconnecting = true
        isUserInitiatedConnect = false
        val device = connectedDevice ?: return
        try {
            hidDevice?.disconnect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        isUserDisconnecting = true
        isUserInitiatedConnect = false
        mainHandler.removeCallbacks(timeoutRunnable)
        isInitializing = false
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering app", e)
        }
        val adapter = bluetoothAdapter
        val hid = hidDevice
        if (adapter != null && hid != null) {
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing profile proxy", e)
            }
        }
        hidDevice = null
        isAppRegistered = false
    }
}
