package com.example.bluewifi.hid

import android.bluetooth.BluetoothDevice

interface HidDeviceListener {
    /**
     * HID 应用注册状态变化
     */
    fun onAppRegistered(registered: Boolean)

    /**
     * 目标连接设备状态变化 (如 STATE_CONNECTED, STATE_DISCONNECTED 等)
     */
    fun onDeviceStateChanged(device: BluetoothDevice, state: Int)

    /**
     * 错误信息回调
     */
    fun onError(message: String)
}
