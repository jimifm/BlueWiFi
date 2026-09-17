# BlueWiFi - 蓝牙键鼠外设模拟与 WLAN 自动刷新 App

本项目为标准的 Android Studio 工程。实现的功能为：
1. **蓝牙 HID 复合设备模拟**：当前手机/终端作为蓝牙鼠标与键盘，对外广播可被发现，另一台安卓手机搜索并配对连接；
2. **状态感知与自动联动刷新**：当另一台手机连接成功后，当前终端自动触发 **WLAN 扫描刷新**，并在界面中实时列出周围 Wi-Fi 信号（SSID、信号百分比、加密方式等），同时支持一键调起系统原生 WLAN 设置页面；
3. **键鼠交互验证**：界面内嵌触控板（支持指针移动、单击左键、双指/右下角右键）及常用快捷键盘按键（Enter、Esc、Home、音量加减），可在另一台手机上直观验证控制效果。

---

## 目录结构

```
blueWiFi/
├── build.gradle.kts                      # 根构建脚本
├── settings.gradle.kts                   # 模块配置
├── gradle.properties                     # Gradle 全局参数
├── app/
│   ├── build.gradle.kts                  # App 模块构建脚本 (minSdk 28, targetSdk 34)
│   ├── proguard-rules.pro                # 混淆规则
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml       # 权限与应用配置
│           ├── java/com/example/bluewifi/
│           │   ├── MainActivity.kt       # 权限、生命周期、连接回调与 UI 驱动
│           │   ├── hid/
│           │   │   ├── HidConsts.kt      # Combo HID 描述符与标准键码
│           │   │   ├── HidDeviceListener.kt
│           │   │   └── BluetoothHidManager.kt # BluetoothHidDevice 注册与报文上报
│           │   ├── wifi/
│           │   │   ├── WifiItem.kt       # Wi-Fi 实体
│           │   │   └── WifiScanManager.kt # WifiManager 扫描与广播监听
│           │   └── ui/
│           │       ├── TouchPadView.kt   # 自定义鼠标触控板 View
│           │       └── WifiListAdapter.kt# WLAN 列表 RecyclerView 适配器
│           └── res/
│               ├── layout/               # 界面与列表项 XML
│               └── values/               # 颜色、文字与主题
```

---

## 核心实现说明

### 1. 蓝牙 HID Device API
- 基于 Android 9 (API 28) 引入的 `BluetoothHidDevice` 原生 API；
- 采用包含 **键盘 (Report ID 1)**、**鼠标 (Report ID 2)** 和 **多媒体 Consumer Control (Report ID 3)** 的标准复合描述符；
- 注册 `BluetoothHidDevice.Callback`，监听目标设备的连接与断开事件。

### 2. 联动 WLAN 扫描与刷新
- 监听回调 `onConnectionStateChanged(device, state)`；
- 当 `state == BluetoothProfile.STATE_CONNECTED` 时：
  - 触发 `wifiScanManager.startScan()` 进行就地扫描刷新；
  - 界面展示周围 Wi-Fi 列表，并弹出提示告知用户；
  - 提供 `openWifiSettings()` 调起系统原生设置页面。

### 3. Android 权限适配
- Android 12+ 运行时权限：`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`；
- Wi-Fi 与定位权限：`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`，以及 Android 13+ 的 `NEARBY_WIFI_DEVICES`。

---

## 使用与测试指南

### 第一步：导入与编译
1. 打开 **Android Studio**；
2. 选择 **Open**，选择 `e:\worktab\repo\blueWiFi` 目录；
3. 等待 Gradle 同步完成；
4. 连接已开启“开发者选项”和“USB 调试”的 Android 手机（需 Android 9.0 或以上版本）；
5. 点击 **Run 'app'** 安装到手机（设为“手机 A”）。

### 第二步：蓝牙配对与连接
1. 在手机 A 上打开 App，按弹窗提示授予蓝牙与定位权限；
2. 点击界面上的 **【开启蓝牙可发现模式】** 按钮；
3. 拿出另一台安卓手机（设为“手机 B”）：
   - 打开系统【设置】->【蓝牙】；
   - 搜索可用设备，找到手机 A，点击配对并连接；
4. 手机 B 会将手机 A 识别为**输入设备（键盘与鼠标）**。

### 第三步：验证联动与控制
1. **自动刷新验证**：
   - 手机 B 成功连入瞬间，手机 A 状态变为绿色“已作为键鼠连接到：[手机B名称]”；
   - 手机 A 界面自动弹出通知：“蓝牙连接成功！已自动为您刷新终端 WLAN 列表”；
   - 手机 A 下方的 WLAN 列表自动刷新出周围的 Wi-Fi 热点。
2. **键鼠功能验证**：
   - 在手机 A 的【鼠标触摸板】滑动手指，手机 B 屏幕上将出现鼠标光标并跟随移动；
   - 在手机 A 触摸板轻击或点击【鼠标左键】，手机 B 响应点击；
   - 点击手机 A 的【回车】、【返回】或【主页】按钮，手机 B 响应对应的按键动作。
