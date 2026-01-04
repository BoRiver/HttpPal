package com.httppal.model

/**
 * Represents a parameter for an API endpoint
 */
data class EndpointParameter(
    val name: String = "",
    val type: ParameterType = ParameterType.QUERY,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
    val dataType: String? = null,
    val example: String? = null,
    val qualifiedType: String? = null
) {
    /**
     * Validate the parameter
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (name.isBlank()) {
            errors.add("Parameter name cannot be empty")
        }
        
        // Validate parameter name based on type
        when (type) {
            ParameterType.PATH -> {
                if (!name.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                    errors.add("Path parameter name '$name' must be a valid identifier")
                }
            }
            ParameterType.QUERY -> {
                if (name.contains("=") || name.contains("&")) {
                    errors.add("Query parameter name '$name' contains invalid characters")
                }
            }
            ParameterType.HEADER -> {
                if (name.contains(":") || name.contains("\n") || name.contains("\r")) {
                    errors.add("Header parameter name '$name' contains invalid characters")
                }
            }
            ParameterType.BODY -> {
                // Body parameters can have more flexible naming
            }
        }
        
        return errors
    }
    
    /**
     * Get display name with type information
     */
    fun getDisplayName(): String {
        val typeStr = when (type) {
            ParameterType.PATH -> "Path"
            ParameterType.QUERY -> "Query"
            ParameterType.HEADER -> "Header"
            ParameterType.BODY -> "Body"
        }
        val requiredStr = if (required) " (Required)" else ""
        return "$name [$typeStr]$requiredStr"
    }
    
    /**
     * Get effective value (default value if no value provided)
     */
    fun getEffectiveValue(providedValue: String?): String? {
        return providedValue?.takeIf { it.isNotBlank() } ?: defaultValue
    }
}

/**
 * Types of parameters supported
 */
enum class ParameterType {
    PATH, QUERY, HEADER, BODY;
    
    companion object {
        fun fromString(type: String): ParameterType? {
            return try {
                valueOf(type.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}