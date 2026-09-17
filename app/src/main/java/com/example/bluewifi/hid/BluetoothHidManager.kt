package com.example.bluewifi.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
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
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private var isAppRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    var connectedDevice: BluetoothDevice? = null
        private set

    var listener: HidDeviceListener? = null

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "Bluetooth HID Device profile connected")
                hidDevice = proxy as? BluetoothHidDevice
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
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered = $registered")
            isAppRegistered = registered
            mainHandler.post {
                listener?.onAppRegistered(registered)
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
            // 设备请求报告，默认应答
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
        if (bluetoothAdapter == null) {
            listener?.onError("当前设备不支持蓝牙")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener?.onError("请先开启蓝牙")
        }

        val success = bluetoothAdapter.getProfileProxy(
            context.applicationContext,
            serviceListener,
            BluetoothProfile.HID_DEVICE
        )

        if (!success) {
            Log.e(TAG, "getProfileProxy returned false. HID Device may not be supported by this ROM.")
            listener?.onError("当前系统未开启 Bluetooth HID Device 权限或不支持此特性")
        }
    }

    /**
     * 注册 HID SDP 配置
     */
    private fun registerHidApp() {
        val hid = hidDevice ?: return

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "BlueWiFi Combo Controller",
            "Android Bluetooth Mouse and Keyboard Combo",
            "BlueWiFi Inc.",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidConsts.COMBO_REPORT_DESCRIPTOR
        )

        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )

        hid.registerApp(sdpSettings, inQos, outQos, executor, hidCallback)
    }

    /**
     * 发送鼠标相对位移和按键报文
     * @param dx X轴相对位移 (-127 ~ 127)
     * @param dy Y轴相对位移 (-127 ~ 127)
     * @param leftBtn 左键是否按下
     * @param rightBtn 右键是否按下
     * @param wheel 滚轮 (-127 ~ 127)
     */
    fun sendMouseReport(dx: Byte, dy: Byte, leftBtn: Boolean, rightBtn: Boolean, wheel: Byte = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        var btnMask: Byte = 0
        if (leftBtn) btnMask = (btnMask.toInt() or 0x01).toByte()
        if (rightBtn) btnMask = (btnMask.toInt() or 0x02).toByte()

        val report = byteArrayOf(btnMask, dx, dy, wheel)
        executor.execute {
            hid.sendReport(device, HidConsts.REPORT_ID_MOUSE.toInt(), report)
        }
    }

    /**
     * 点击鼠标左键（按下并松开）
     */
    fun clickLeftMouse() {
        sendMouseReport(0, 0, leftBtn = true, rightBtn = false)
        mainHandler.postDelayed({
            sendMouseReport(0, 0, leftBtn = false, rightBtn = false)
        }, 50)
    }

    /**
     * 点击鼠标右键（按下并松开）
     */
    fun clickRightMouse() {
        sendMouseReport(0, 0, leftBtn = false, rightBtn = true)
        mainHandler.postDelayed({
            sendMouseReport(0, 0, leftBtn = false, rightBtn = false)
        }, 50)
    }

    /**
     * 单击指定键盘按键（按下并释放）
     */
    fun tapKey(keyCode: Byte, modifier: Byte = 0) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        executor.execute {
            // 按下
            val pressReport = byteArrayOf(modifier, 0, keyCode, 0, 0, 0, 0, 0)
            hid.sendReport(device, HidConsts.REPORT_ID_KEYBOARD.toInt(), pressReport)

            Thread.sleep(50)

            // 松开
            val releaseReport = ByteArray(8)
            hid.sendReport(device, HidConsts.REPORT_ID_KEYBOARD.toInt(), releaseReport)
        }
    }

    /**
     * 发送 Consumer Control 多媒体/系统键（音量、Home等）
     */
    fun tapConsumerKey(consumerCode: Short) {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        executor.execute {
            val byte1 = (consumerCode.toInt() and 0xFF).toByte()
            val byte2 = ((consumerCode.toInt() shr 8) and 0xFF).toByte()
            val pressReport = byteArrayOf(byte1, byte2)
            hid.sendReport(device, HidConsts.REPORT_ID_CONSUMER.toInt(), pressReport)

            Thread.sleep(50)

            val releaseReport = byteArrayOf(0, 0)
            hid.sendReport(device, HidConsts.REPORT_ID_CONSUMER.toInt(), releaseReport)
        }
    }

    /**
     * 获取系统已配对的蓝牙设备列表
     */
    fun getBondedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    /**
     * 主动向已配对的目标 Host 手机 (SIM卡手机) 发起 HID 鼠标/键盘连接
     */
    fun connect(device: BluetoothDevice): Boolean {
        val hid = hidDevice
        if (hid == null) {
            Log.w(TAG, "Cannot connect: BluetoothHidDevice service is not ready yet")
            return false
        }
        Log.d(TAG, "Initiating HID connection to ${device.name} (${device.address})")
        return hid.connect(device)
    }

    /**
     * 根据 MAC 地址主动发起连接
     */
    fun connect(macAddress: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(macAddress)) return false
        val device = adapter.getRemoteDevice(macAddress)
        return connect(device)
    }

    /**
     * 断开当前连接的目标设备
     */
    fun disconnect() {
        val device = connectedDevice ?: return
        hidDevice?.disconnect(device)
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering app", e)
        }
        if (bluetoothAdapter != null && hidDevice != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
        executor.shutdown()
    }
}
