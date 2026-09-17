package com.example.bluewifi.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val PROXY_TIMEOUT_MS = 6000L
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

    var listener: HidDeviceListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var isInitializing = false
    private val timeoutRunnable = Runnable {
        if (hidDevice == null && isInitializing) {
            isInitializing = false
            Log.w(TAG, "getProfileProxy timeout. HID Device profile not responding.")
            listener?.onError("获取蓝牙外设服务超时！当前系统可能未开启 Bluetooth HID Device 支持，请尝试重启蓝牙或检查系统设置")
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            mainHandler.removeCallbacks(timeoutRunnable)
            isInitializing = false

            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile connected successfully")
                hidDevice = proxy as? BluetoothHidDevice
                mainHandler.post {
                    listener?.onStatusMessage("已获取蓝牙外设代理，正在向系统注册键鼠描述符...")
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
                    listener?.onAppRegistered(false)
                    listener?.onError("蓝牙外设服务已与系统断开")
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered = $registered, device = ${pluggedDevice?.address}")
            isAppRegistered = registered
            mainHandler.post {
                listener?.onAppRegistered(registered)
                if (registered) {
                    listener?.onStatusMessage("蓝牙外设服务注册成功，随时可连接")
                } else {
                    listener?.onError("蓝牙外设服务已注销")
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device = ${device.address}, state = $state")
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice?.address == device.address) {
                    connectedDevice = null
                }
            }
            mainHandler.post {
                listener?.onDeviceStateChanged(device, state)
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
        initialize()
    }

    /**
     * 注册 HID SDP 配置
     * 传入 null QoS 保证各主流机型底层的最佳兼容性
     */
    private fun registerHidApp() {
        val hid = hidDevice
        if (hid == null) {
            Log.e(TAG, "registerHidApp failed: hidDevice is null")
            return
        }

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "BlueWiFi Mouse/Keyboard",
            "Android Bluetooth Mouse and Keyboard Combo",
            "BlueWiFi",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidConsts.COMBO_REPORT_DESCRIPTOR
        )

        try {
            // QoS 传入 null，由底层蓝牙芯片使用最佳配置，避免因参数严格校验导致失败
            val registered = hid.registerApp(sdpSettings, null, null, executor, hidCallback)
            Log.d(TAG, "hid.registerApp returned: $registered")
            if (!registered) {
                mainHandler.post {
                    listener?.onError("系统蓝牙拒绝注册外设描述符 (registerApp 返回 false)")
                }
            } else {
                mainHandler.post {
                    listener?.onStatusMessage("已提交描述符，等待系统确认就绪...")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerApp exception", e)
            mainHandler.post {
                listener?.onError("向系统注册外设异常: ${e.message}")
            }
        }
    }

    /**
     * 发送鼠标相对位移和按键报文
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
                hid.sendReport(device, HidConsts.REPORT_ID_MOUSE.toInt(), report)
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
     * 获取系统已配对的蓝牙设备列表
     */
    fun getBondedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    /**
     * 主动向已配对的目标 Host 手机 (SIM卡手机) 发起 HID 连接
     */
    fun connect(device: BluetoothDevice): Boolean {
        val hid = hidDevice
        if (hid == null) {
            Log.w(TAG, "Cannot connect: hidDevice proxy is null")
            return false
        }
        if (!isAppRegistered) {
            Log.w(TAG, "Cannot connect: hid application is not registered yet")
            return false
        }
        Log.d(TAG, "Initiating HID connection to ${device.name} (${device.address})")
        return try {
            hid.connect(device)
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling hid.connect", e)
            false
        }
    }

    /**
     * 根据 MAC 地址主动发起连接
     */
    fun connect(macAddress: String): Boolean {
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
