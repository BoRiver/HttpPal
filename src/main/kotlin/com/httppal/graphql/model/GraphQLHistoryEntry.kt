package com.httppal.graphql.model

import java.time.Instant
import java.util.*

/**
 * Represents a GraphQL request in history or favorites.
 *
 * @property id Unique identifier
 * @property endpoint The GraphQL endpoint URL
 * @property query The GraphQL query
 * @property variables The variables JSON string
 * @property operationName Optional operation name
 * @property name Optional name for favorites
 * @property timestamp When the request was executed
 * @property responseData The response data (JSON string)
 * @property responseErrors The response errors (JSON string)
 * @property executionTimeMs Execution time in milliseconds
 * @property environment The environment name when executed
 */
data class GraphQLHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val endpoint: String,
    val query: String,
    val variables: String? = null,
    val operationName: String? = null,
    val name: String? = null,
    val timestamp: Instant = Instant.now(),
    val responseData: String? = null,
    val responseErrors: String? = null,
    val executionTimeMs: Long? = null,
    val environment: String? = null
) {
    /**
     * Get a display name for this history entry.
     */
    fun getDisplayName(): String {
        return name ?: extractQueryName() ?: "GraphQL Query"
    }

    /**
     * Extract operation name from query (simple heuristic).
     */
    private fun extractQueryName(): String? {
        // Try to find "query QueryName" or "mutation MutationName"
        val regex = Regex("""(query|mutation|subscription)\s+(\w+)""")
        val match = regex.find(query)
        return match?.groupValues?.get(2)
    }

    /**
     * Get full display name with timestamp.
     */
    fun getFullDisplayName(): String {
        val timeStr = timestamp.toString().substring(11, 19) // HH:MM:SS
        return "${getDisplayName()} - $timeStr"
    }

    /**
     * Check if the request was successful (no errors).
     */
    fun wasSuccessful(): Boolean {
        return responseErrors.isNullOrBlank()
    }

    /**
     * Get status description.
     */
    fun getStatusDescription(): String {
        return when {
            responseErrors != null && responseErrors.isNotBlank() -> "Has Errors"
            responseData != null -> "Success"
            else -> "No Response"
        }
    }

    /**
     * Check if entry matches search query.
     */
    fun matchesSearch(query: String): Boolean {
        val lowerQuery = query.lowercase()
        return this.query.lowercase().contains(lowerQuery) ||
                endpoint.lowercase().contains(lowerQuery) ||
                name?.lowercase()?.contains(lowerQuery) == true ||
                operationName?.lowercase()?.contains(lowerQuery) == true ||
                environment?.lowercase()?.contains(lowerQuery) == true
    }
}
