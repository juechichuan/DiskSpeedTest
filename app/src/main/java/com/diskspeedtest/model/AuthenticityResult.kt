package com.diskspeedtest.model

/**
 * 存储设备真伪验证结果
 */
data class AuthenticityResult(
    /** 是否为真品 */
    val isGenuine: Boolean,
    /** 综合评分 0-100 */
    val score: Int,
    /** 各检测项结果 */
    val checks: List<CheckItem>,
    /** 总体结论 */
    val conclusion: String,
    /** 建议 */
    val suggestion: String = ""
) {
    data class CheckItem(
        val name: String,
        val passed: Boolean,
        val detail: String
    )
}
