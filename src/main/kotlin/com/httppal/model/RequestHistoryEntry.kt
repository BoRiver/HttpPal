package com.httppal.model

import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Represents an entry in the request history
 */
data class RequestHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val request: RequestConfig,
    val response: HttpResponse?,
    val timestamp: Instant = Instant.now(),
    val executionTime: Duration?,
    val error: String? = null,
    val environment: String? = null
) {
    /**
     * Get a display name for this history entry
     */
    fun getDisplayName(): String {
        return "${request.method} ${request.url}"
    }
    
    /**
     * Get full display name with timestamp
     */
    fun getFullDisplayName(): String {
        val timeStr = timestamp.toString().substring(11, 19) // HH:MM:SS
        return "${getDisplayName()} - $timeStr"
    }
    
    /**
     * Check if the request was successful
     */
    fun wasSuccessful(): Boolean {
        return response?.isSuccessful() == true && error == null
    }
    
    /**
     * Check if the request failed
     */
    fun hasFailed(): Boolean {
        return error != null || response?.isSuccessful() == false
    }
    
    /**
     * Get status description
     */
    fun getStatusDescription(): String {
        return when {
            error != null -> "Error: $error"
            response != null -> "${response.statusCode} ${response.statusText}"
            else -> "No Response"
        }
    }
    
    /**
     * Get response time in milliseconds
     */
    fun getResponseTimeMs(): Long? {
        return response?.responseTime?.toMillis() ?: executionTime?.toMillis()
    }
    
    /**
     * Get response size in bytes
     */
    fun getResponseSize(): Int? {
        return response?.getBodySize()
    }
    
    /**
     * Convert to favorite request
     */
    fun toFavoriteRequest(name: String): FavoriteRequest {
        return FavoriteRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            request = request,
            createdAt = timestamp
        )
    }
    
    /**
     * Check if entry matches search query
     */
    fun matchesSearch(query: String): Boolean {
        val lowerQuery = query.lowercase()
        return request.url.lowercase().contains(lowerQuery) ||
               request.method.name.lowercase().contains(lowerQuery) ||
               response?.statusText?.lowercase()?.contains(lowerQuery) == true ||
               error?.lowercase()?.contains(lowerQuery) == true ||
               environment?.lowercase()?.contains(lowerQuery) == true
    }
    
    /**
     * Get execution summary
     */
    fun getExecutionSummary(): String {
        val status = getStatusDescription()
        val time = getResponseTimeMs()?.let { "${it}ms" } ?: "N/A"
        val size = getResponseSize()?.let { "${it}B" } ?: "N/A"
        return "$status | $time | $size"
    }
}