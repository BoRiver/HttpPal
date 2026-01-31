package com.httppal.graphql.service

import com.httppal.graphql.model.GraphQLHistoryEntry

/**
 * Service for managing GraphQL request history.
 */
interface GraphQLHistoryService {

    /**
     * Add a GraphQL request to history.
     */
    fun addToHistory(entry: GraphQLHistoryEntry)

    /**
     * Get all history entries.
     */
    fun getHistory(): List<GraphQLHistoryEntry>

    /**
     * Get history entries for a specific endpoint.
     */
    fun getHistoryForEndpoint(endpoint: String): List<GraphQLHistoryEntry>

    /**
     * Clear all history.
     */
    fun clearHistory()

    /**
     * Delete a specific history entry.
     */
    fun deleteEntry(id: String)

    /**
     * Get a specific history entry by ID.
     */
    fun getEntry(id: String): GraphQLHistoryEntry?

    /**
     * Search history entries.
     */
    fun searchHistory(query: String): List<GraphQLHistoryEntry>
}
