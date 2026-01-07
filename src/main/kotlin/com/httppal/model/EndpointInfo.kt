package com.httppal.model


/**
 * Represents an API endpoint configuration
 */
data class EndpointInfo(
    val id: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val path: String = "",
    val baseUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val parameters: List<EndpointParameter> = emptyList(),
    val source: EndpointSource = EndpointSource.MANUAL,
    val sourceLocation: SourceLocation? = null,
    val name: String? = null,
    val description: String? = null
) {
    /**
     * Get the full URL by combining base URL and path
     */
    fun getFullUrl(): String {
        return if (baseUrl != null) {
            "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
        } else {
            path
        }
    }
    
    /**
     * Get display name for this endpoint
     */
    fun getDisplayName(): String {
        return name ?: "${method.name} ${path}"
    }
    
    /**
     * Validate the endpoint configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (path.isBlank()) {
            errors.add("Endpoint path cannot be empty")
        }
        
        if (baseUrl != null && baseUrl.isBlank()) {
            errors.add("Base URL cannot be empty if specified")
        }
        
        if (baseUrl != null && !isValidUrl(baseUrl)) {
            errors.add("Base URL format is invalid")
        }
        
        // Validate headers
        headers.forEach { (headerName, _) ->
            if (headerName.isBlank()) {
                errors.add("Header name cannot be empty")
            }
            if (headerName.contains(":") || headerName.contains("\n") || headerName.contains("\r")) {
                errors.add("Header name '$headerName' contains invalid characters")
            }
        }
        
        // Validate parameters
        parameters.forEachIndexed { index, parameter ->
            val paramErrors = parameter.validate()
            paramErrors.forEach { error ->
                errors.add("Parameter $index: $error")
            }
        }
        
        return errors
    }
    
    /**
     * Convert to RequestConfig for execution
     */
    fun toRequestConfig(body: String? = null): RequestConfig {
        return RequestConfig(
            method = method,
            url = getFullUrl(),
            headers = headers,
            body = body
        )
    }
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            java.net.URL(url)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Source of the endpoint (discovered from code, OpenAPI file, or manually created)
 */
enum class EndpointSource {
    CODE_SCAN,      // 从代码扫描发现
    OPENAPI,        // 从 OpenAPI 文件发现
    MANUAL,         // 手动创建
    DISCOVERED      // 保留向后兼容（等同于 CODE_SCAN）
}

/**
 * Location in source code where endpoint was discovered
 */
data class SourceLocation(
    val fileName: String = "",
    val className: String = "",
    val methodName: String = "",
    val lineNumber: Int = 0
)