package com.httppal.model

/**
 * Represents an endpoint discovered from source code analysis or OpenAPI specification
 */
data class DiscoveredEndpoint(
    val method: HttpMethod,
    val path: String,
    val className: String,
    val methodName: String,
    val parameters: List<EndpointParameter>,
    val sourceFile: String,
    val lineNumber: Int,
    
    // OpenAPI 相关字段
    val source: EndpointSource = EndpointSource.CODE_SCAN,
    val openAPIFile: String? = null,           // OpenAPI 文件路径（如果来自 OpenAPI）
    val operationId: String? = null,           // OpenAPI operationId
    val summary: String? = null,               // 端点摘要
    val tags: List<String> = emptyList(),      // OpenAPI tags
    val schemaInfo: SchemaInfo? = null         // 关联的 schema 信息
) {
    /**
     * Convert to EndpointInfo for use in requests
     */
    fun toEndpointInfo(baseUrl: String? = null): EndpointInfo {
        return EndpointInfo(
            method = method,
            path = path,
            baseUrl = baseUrl,
            parameters = parameters,
            source = if (source == EndpointSource.CODE_SCAN) EndpointSource.DISCOVERED else source,
            sourceLocation = SourceLocation(
                fileName = sourceFile,
                className = className,
                methodName = methodName,
                lineNumber = lineNumber
            ),
            name = summary,
            description = summary
        )
    }
}