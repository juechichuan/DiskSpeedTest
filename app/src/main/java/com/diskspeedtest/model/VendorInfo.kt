package com.diskspeedtest.model

/**
 * 存储设备厂商信息
 */
data class VendorInfo(
    /** 厂商名称 */
    val vendor: String = "未知",
    /** 产品型号 */
    val model: String = "未知",
    /** 产品序列号 */
    val serial: String = "未知",
    /** 固件版本 */
    val revision: String = "未知",
    /** SD 卡 CID（如果可获取） */
    val cid: String = "",
    /** 设备类型：sd / usb / internal */
    val deviceType: String = "unknown",
    /** 速度等级（SD卡）如 Class10, U1, U3, V30, A1, A2 */
    val speedClass: String = "未知",
    /** 信息来源说明 */
    val source: String = ""
) {
    /** 是否成功获取到有效信息 */
    val hasValidInfo: Boolean
        get() = vendor != "未知" || model != "未知"
}
