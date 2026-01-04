package com.httppal.model

import java.time.Duration

/**
 * Configuration for an HTTP request
 */
data class RequestConfig(
    val method: HttpMethod = HttpMethod.GET,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeout: Duration = Duration.ofSeconds(30),
    val followRedirects: Boolean = true,
    val queryParameters: Map<String, String> = emptyMap(),
    val pathParameters: Map<String, String> = emptyMap(),
    val formData: List<FormDataEntry>? = null  // For multipart/form-data with file uploads
) {
    /**
     * Merge with global headers, with request headers taking precedence
     */
    fun withGlobalHeaders(globalHeaders: Map<String, String>): RequestConfig {
        val mergedHeaders = globalHeaders + headers // Request headers override global ones
        return copy(headers = mergedHeaders)
    }
    
    /**
     * Apply environment settings
     */
    fun withEnvironment(environment: Environment): RequestConfig {
        val environmentHeaders = environment.globalHeaders + headers
        val fullUrl = if (url.startsWith("http")) {
            url
        } else {
            "${environment.baseUrl.trimEnd('/')}/${url.trimStart('/')}"
        }
        return copy(
            url = fullUrl,
            headers = environmentHeaders
        )
    }
    
    /**
     * Get the final URL with query parameters
     */
    fun getFinalUrl(): String {
        if (queryParameters.isEmpty()) {
            return url
        }
        
        val separator = if (url.contains("?")) "&" else "?"
        val queryString = queryParameters.entries.joinToString("&") { (key, value) ->
            "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        
        return "$url$separator$queryString"
    }
    
    /**
     * Apply path parameters to URL
     */
    fun applyPathParameters(): RequestConfig {
        var processedUrl = url
        pathParameters.forEach { (key, value) ->
            processedUrl = processedUrl.replace("{$key}", value)
        }
        return copy(url = processedUrl)
    }
    
    /**
     * Validate the request configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (url.isBlank()) {
            errors.add("URL cannot be empty")
        }
        
        if (!isValidUrl(url) && !isValidUrlTemplate(url)) {
            errors.add("URL format is invalid")
        }
        
        if (timeout.isNegative || timeout.isZero) {
            errors.add("Timeout must be positive")
        }
        
        if (timeout.toSeconds() > 300) {
            errors.add("Timeout cannot exceed 5 minutes")
        }
        
        // Validate headers
        headers.forEach { (name, value) ->
            if (name.isBlank()) {
                errors.add("Header name cannot be empty")
            }
            if (name.contains(":") || name.contains("\n") || name.contains("\r")) {
                errors.add("Header name '$name' contains invalid characters")
            }
        }
        
        // Validate query parameters
        queryParameters.forEach { (name, _) ->
            if (name.isBlank()) {
                errors.add("Query parameter name cannot be empty")
            }
        }
        
        // Validate path parameters
        pathParameters.forEach { (name, _) ->
            if (name.isBlank()) {
                errors.add("Path parameter name cannot be empty")
            }
        }
        
        // Check for required path parameters
        val pathParamPattern = Regex("\\{([^}]+)\\}")
        val requiredParams = pathParamPattern.findAll(url).map { it.groupValues[1] }.toSet()
        val missingParams = requiredParams - pathParameters.keys
        if (missingParams.isNotEmpty()) {
            errors.add("Missing path parameters: ${missingParams.joinToString(", ")}")
        }
        
        return errors
    }
    
    /**
     * Get content type from headers
     */
    fun getContentType(): String? {
        return headers.entries
            .find { it.key.equals("content-type", ignoreCase = true) }
            ?.value
    }
    
    /**
     * Check if request has body
     */
    fun hasBody(): Boolean {
        return body != null && body.isNotBlank()
    }
    
    /**
     * Get display name for this request
     */
    fun getDisplayName(): String {
        return "$method $url"
    }
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            java.net.URL(url)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isValidUrlTemplate(url: String): Boolean {
        // Check if it's a valid URL template with path parameters
        val withoutParams = url.replace(Regex("\\{[^}]+\\}"), "placeholder")
        return try {
            java.net.URL(withoutParams)
            true
        } catch (e: Exception) {
            // Check if it's a relative path
            url.matches(Regex("^[/]?[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=-]*$"))
        }
    }
}