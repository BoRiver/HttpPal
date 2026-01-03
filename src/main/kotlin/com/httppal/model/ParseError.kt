package com.httppal.model

/**
 * 解析错误信息模型
 */
data class ParseError(
    val file: String,
    val line: Int? = null,
    val column: Int? = null,
    val message: String,
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
    val suggestion: String? = null             // 修复建议
)

/**
 * 错误严重程度
 */
enum class ErrorSeverity {
    ERROR, WARNING, INFO
}
