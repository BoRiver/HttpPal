package com.httppal.model

import java.time.Instant

/**
 * Represents a favorite request saved by the user
 */
data class FavoriteRequest(
    val id: String = "",
    val name: String = "",
    val request: RequestConfig = RequestConfig(),
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val lastUsed: Instant? = null,
    val useCount: Int = 0,
    val folder: String? = null
) {
    /**
     * Get display name with method prefix
     */
    fun getDisplayName(): String {
        return "$name (${request.method})"
    }
    
    /**
     * Get full display name with folder
     */
    fun getFullDisplayName(): String {
        return if (folder != null) {
            "$folder / ${getDisplayName()}"
        } else {
            getDisplayName()
        }
    }
    
    /**
     * Validate the favorite request
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (name.isBlank()) {
            errors.add("Favorite name cannot be empty")
        }
        
        if (name.length > 100) {
            errors.add("Favorite name cannot exceed 100 characters")
        }
        
        // Validate request configuration
        errors.addAll(request.validate())
        
        // Validate tags
        tags.forEach { tag ->
            if (tag.isBlank()) {
                errors.add("Tag cannot be empty")
            }
            if (tag.length > 30) {
                errors.add("Tag '$tag' cannot exceed 30 characters")
            }
        }
        
        if (useCount < 0) {
            errors.add("Use count cannot be negative")
        }
        
        return errors
    }
    
    /**
     * Mark as used (increment use count and update last used time)
     */
    fun markAsUsed(): FavoriteRequest {
        return copy(
            lastUsed = Instant.now(),
            useCount = useCount + 1
        )
    }
    
    /**
     * Check if favorite matches search query
     */
    fun matchesSearch(query: String): Boolean {
        val lowerQuery = query.lowercase()
        return name.lowercase().contains(lowerQuery) ||
               tags.any { it.lowercase().contains(lowerQuery) } ||
               request.url.lowercase().contains(lowerQuery) ||
               request.method.name.lowercase().contains(lowerQuery) ||
               folder?.lowercase()?.contains(lowerQuery) == true
    }
    
    /**
     * Check if favorite has specific tag
     */
    fun hasTag(tag: String): Boolean {
        return tags.any { it.equals(tag, ignoreCase = true) }
    }
}