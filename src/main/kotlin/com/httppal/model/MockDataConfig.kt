package com.httppal.model

/**
 * Mock 数据配置，包含生成的所有请求数据
 */
data class MockDataConfig(
    val pathParameters: Map<String, String> = emptyMap(),
    val queryParameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,                  // JSON 格式的请求体
    val contentType: String = "application/json"
)
