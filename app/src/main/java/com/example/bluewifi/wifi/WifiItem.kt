package com.example.bluewifi.wifi

data class WifiItem(
    val ssid: String,
    val bssid: String,
    val level: Int,          // RSSI in dBm (如 -50)
    val signalPercent: Int,  // 0 ~ 100%
    val capabilities: String,// 加密协议描述
    val frequency: Int       // 频率 (MHz, 例如 2412 或 5180)
) {
    val is5Ghz: Boolean
        get() = frequency in 4900..5900
}
