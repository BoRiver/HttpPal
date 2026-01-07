package com.httppal.model


/**
 * Represents an environment configuration (dev, staging, production, etc.)
 */
data class Environment(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val globalHeaders: Map<String, String> = emptyMap(),
    val description: String? = null,
    val variables: Map<String, String> = emptyMap(),
    val isActive: Boolean = false
) {
    /**
     * Validate the environment configuration
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (name.isBlank()) {
            errors.add("Environment name cannot be empty")
        }
        
        if (name.length > 50) {
            errors.add("Environment name cannot exceed 50 characters")
        }
        
        if (baseUrl.isBlank()) {
            errors.add("Base URL cannot be empty")
        }
        
        if (!isValidUrl(baseUrl)) {
            errors.add("Base URL format is invalid")
        }
        
        // Validate global headers
        globalHeaders.forEach { (headerName, _) ->
            if (headerName.isBlank()) {
                errors.add("Global header name cannot be empty")
            }
            if (headerName.contains(":") || headerName.contains("\n") || headerName.contains("\r")) {
                errors.add("Global header name '$headerName' contains invalid characters")
            }
        }
        
        // Validate variables
        variables.forEach { (varName, _) ->
            if (varName.isBlank()) {
                errors.add("Variable name cannot be empty")
            }
            if (!varName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                errors.add("Variable name '$varName' must be a valid identifier")
            }
        }
        
        return errors
    }
    
    /**
     * Apply environment variables to a string
     */
    fun applyVariables(text: String): String {
        var result = text
        variables.forEach { (name, value) ->
            result = result.replace("{{$name}}", value)
            result = result.replace("\${$name}", value)
        }
        return result
    }
    
    /**
     * Get normalized base URL (ensure it ends without slash)
     */
    fun getNormalizedBaseUrl(): String {
        return baseUrl.trimEnd('/')
    }
    
    /**
     * Build full URL from path
     */
    fun buildUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        return "${getNormalizedBaseUrl()}/$normalizedPath"
    }
    
    /**
     * Get display name with status
     */
    fun getDisplayName(): String {
        val status = if (isActive) " (Active)" else ""
        return "$name$status"
    }
    
    /**
     * Create a copy with updated active status
     */
    fun withActiveStatus(active: Boolean): Environment {
        return copy(isActive = active)
    }
    
    private fun isValidUrl(url: String): Boolean {
        return try {
            val urlObj = java.net.URL(url)
            urlObj.protocol in listOf("http", "https")
        } catch (e: Exception) {
            false
        }
    }
}