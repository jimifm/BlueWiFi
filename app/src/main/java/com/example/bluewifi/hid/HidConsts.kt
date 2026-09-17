package com.example.bluewifi.hid

object HidConsts {

    // Report IDs
    const val REPORT_ID_KEYBOARD: Byte = 1
    const val REPORT_ID_MOUSE: Byte = 2
    const val REPORT_ID_CONSUMER: Byte = 3

    /**
     * 标准蓝牙纯鼠标 HID Report Descriptor (像"妙妙触控"一样的标准无线鼠标)
     * 52 字节标准无 Report ID 单设备定义，全平台 (Android/iOS/PC) 免驱原生完美支持
     * 报文格式固定 4 字节: [ButtonMask, dx, dy, wheel]
     */
    val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
        0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
        0x81.toByte(), 0x02.toByte(), //     INPUT (Data,Var,Abs) - 3 bits for Buttons (Left, Right, Middle)
        0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
        0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5)
        0x81.toByte(), 0x03.toByte(), //     INPUT (Cnst,Var,Abs) - 5 bits Padding
        0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     USAGE (X)
        0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
        0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data,Var,Rel) - 3 bytes X, Y, Wheel
        0xC0.toByte(),                //   END_COLLECTION
        0xC0.toByte()                 // END_COLLECTION
    )

    /**
     * Combo HID Report Descriptor
     * 包含：
     * 1. 键盘 (Report ID 1, 8字节: 1字节修饰键 + 1字节保留 + 6字节键码)
     * 2. 鼠标 (Report ID 2, 4字节: 1字节按键掩码 + 1字节dx + 1字节dy + 1字节wheel)
     * 3. Consumer Control (Report ID 3, 2字节: 音量、主页等多媒体控制)
     */
    val COMBO_REPORT_DESCRIPTOR = byteArrayOf(
        // === 键盘 (Report ID 1) ===
        0x05.toByte(), 0x01.toByte(),       // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),       // USAGE (Keyboard)
        0xA1.toByte(), 0x01.toByte(),       // COLLECTION (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD,  //   REPORT_ID (1)
        0x05.toByte(), 0x07.toByte(),       //   USAGE_PAGE (Keyboard)
        0x19.toByte(), 0xE0.toByte(),       //   USAGE_MINIMUM (Keyboard LeftControl)
        0x29.toByte(), 0xE7.toByte(),       //   USAGE_MAXIMUM (Keyboard Right GUI)
        0x15.toByte(), 0x00.toByte(),       //   LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(),       //   LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(),       //   REPORT_SIZE (1)
        0x95.toByte(), 0x08.toByte(),       //   REPORT_COUNT (8)
        0x81.toByte(), 0x02.toByte(),       //   INPUT (Data,Var,Abs) - 8 bits Modifier Keys
        0x95.toByte(), 0x01.toByte(),       //   REPORT_COUNT (1)
        0x75.toByte(), 0x08.toByte(),       //   REPORT_SIZE (8)
        0x81.toByte(), 0x01.toByte(),       //   INPUT (Cnst,Ary,Abs) - 1 byte Reserved
        0x95.toByte(), 0x06.toByte(),       //   REPORT_COUNT (6)
        0x75.toByte(), 0x08.toByte(),       //   REPORT_SIZE (8)
        0x15.toByte(), 0x00.toByte(),       //   LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x65.toByte(),       //   LOGICAL_MAXIMUM (101)
        0x05.toByte(), 0x07.toByte(),       //   USAGE_PAGE (Keyboard)
        0x19.toByte(), 0x00.toByte(),       //   USAGE_MINIMUM (Reserved (no event indicated))
        0x29.toByte(), 0x65.toByte(),       //   USAGE_MAXIMUM (Keyboard Application)
        0x81.toByte(), 0x00.toByte(),       //   INPUT (Data,Ary,Abs) - 6 bytes Keycodes
        0xC0.toByte(),                      // END_COLLECTION

        // === 鼠标 (Report ID 2) ===
        0x05.toByte(), 0x01.toByte(),       // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),       // USAGE (Mouse)
        0xA1.toByte(), 0x01.toByte(),       // COLLECTION (Application)
        0x85.toByte(), REPORT_ID_MOUSE,     //   REPORT_ID (2)
        0x09.toByte(), 0x01.toByte(),       //   USAGE (Pointer)
        0xA1.toByte(), 0x00.toByte(),       //   COLLECTION (Physical)
        0x05.toByte(), 0x09.toByte(),       //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(),       //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(),       //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(),       //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(),       //     LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(),       //     REPORT_SIZE (1)
        0x95.toByte(), 0x03.toByte(),       //     REPORT_COUNT (3)
        0x81.toByte(), 0x02.toByte(),       //     INPUT (Data,Var,Abs) - 3 bits Buttons
        0x75.toByte(), 0x05.toByte(),       //     REPORT_SIZE (5)
        0x95.toByte(), 0x01.toByte(),       //     REPORT_COUNT (1)
        0x81.toByte(), 0x01.toByte(),       //     INPUT (Cnst,Ary,Abs) - 5 bits Padding
        0x05.toByte(), 0x01.toByte(),       //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),       //     USAGE (X)
        0x09.toByte(), 0x31.toByte(),       //     USAGE (Y)
        0x15.toByte(), 0x81.toByte(),       //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(),       //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(),       //     REPORT_SIZE (8)
        0x95.toByte(), 0x02.toByte(),       //     REPORT_COUNT (2)
        0x81.toByte(), 0x06.toByte(),       //     INPUT (Data,Var,Rel) - 2 bytes X, Y
        0x09.toByte(), 0x38.toByte(),       //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(),       //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(),       //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(),       //     REPORT_SIZE (8)
        0x95.toByte(), 0x01.toByte(),       //     REPORT_COUNT (1)
        0x81.toByte(), 0x06.toByte(),       //     INPUT (Data,Var,Rel) - 1 byte Wheel
        0xC0.toByte(),                      //   END_COLLECTION
        0xC0.toByte(),                      // END_COLLECTION

        // === Consumer Control (Report ID 3 - 音量、Home 等) ===
        0x05.toByte(), 0x0C.toByte(),       // USAGE_PAGE (Consumer Devices)
        0x09.toByte(), 0x01.toByte(),       // USAGE (Consumer Control)
        0xA1.toByte(), 0x01.toByte(),       // COLLECTION (Application)
        0x85.toByte(), REPORT_ID_CONSUMER,  //   REPORT_ID (3)
        0x15.toByte(), 0x00.toByte(),       //   LOGICAL_MINIMUM (0)
        0x26.toByte(), 0xFF.toByte(), 0x03.toByte(), // LOGICAL_MAXIMUM (1023)
        0x19.toByte(), 0x00.toByte(),       //   USAGE_MINIMUM (0)
        0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(), // USAGE_MAXIMUM (1023)
        0x75.toByte(), 0x10.toByte(),       //   REPORT_SIZE (16)
        0x95.toByte(), 0x01.toByte(),       //   REPORT_COUNT (1)
        0x81.toByte(), 0x00.toByte(),       //   INPUT (Data,Ary,Abs)
        0xC0.toByte()                       // END_COLLECTION
    )

    // 常用键盘 HID 键码 (Usage Page 0x07)
    const val KEY_NONE: Byte = 0x00
    const val KEY_ENTER: Byte = 0x28
    const val KEY_ESCAPE: Byte = 0x29
    const val KEY_BACKSPACE: Byte = 0x2A
    const val KEY_TAB: Byte = 0x2B
    const val KEY_SPACE: Byte = 0x2C
    const val KEY_HOME: Byte = 0x4A
    const val KEY_RIGHT_ARROW: Byte = 0x4F
    const val KEY_LEFT_ARROW: Byte = 0x50
    const val KEY_DOWN_ARROW: Byte = 0x51
    const val KEY_UP_ARROW: Byte = 0x52

    // Consumer Control 常用功能码 (Usage Page 0x0C)
    const val CONSUMER_HOME: Short = 0x0223.toShort()
    const val CONSUMER_VOLUME_UP: Short = 0x00E9.toShort()
    const val CONSUMER_VOLUME_DOWN: Short = 0x00EA.toShort()
    const val CONSUMER_MUTE: Short = 0x00E2.toShort()
}
