package com.httppal.graphql.service.impl

import com.httppal.graphql.model.GraphQLHistoryEntry
import com.httppal.graphql.service.GraphQLHistoryService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of GraphQL history service with in-memory storage.
 */
@Service
class GraphQLHistoryServiceImpl : GraphQLHistoryService {

    private val logger = Logger.getInstance(GraphQLHistoryServiceImpl::class.java)
    private val history = mutableListOf<GraphQLHistoryEntry>()
    private val historyById = ConcurrentHashMap<String, GraphQLHistoryEntry>()
    private val maxHistorySize = 1000

    @Synchronized
    override fun addToHistory(entry: GraphQLHistoryEntry) {
        logger.info("Adding GraphQL entry to history: ${entry.getDisplayName()}")

        // Add to list
        history.add(0, entry) // Add to beginning

        // Add to map
        historyById[entry.id] = entry

        // Trim if exceeds max size
        if (history.size > maxHistorySize) {
            val removed = history.removeAt(history.size - 1)
            historyById.remove(removed.id)
        }
    }

    @Synchronized
    override fun getHistory(): List<GraphQLHistoryEntry> {
        return history.toList()
    }

    @Synchronized
    override fun getHistoryForEndpoint(endpoint: String): List<GraphQLHistoryEntry> {
        return history.filter { it.endpoint == endpoint }
    }

    @Synchronized
    override fun clearHistory() {
        logger.info("Clearing GraphQL history")
        history.clear()
        historyById.clear()
    }

    @Synchronized
    override fun deleteEntry(id: String) {
        logger.info("Deleting GraphQL history entry: $id")
        history.removeIf { it.id == id }
        historyById.remove(id)
    }

    @Synchronized
    override fun getEntry(id: String): GraphQLHistoryEntry? {
        return historyById[id]
    }

    @Synchronized
    override fun searchHistory(query: String): List<GraphQLHistoryEntry> {
        if (query.isBlank()) {
            return getHistory()
        }
        return history.filter { it.matchesSearch(query) }
    }

    companion object {
        fun getInstance(): GraphQLHistoryService = service()
    }
}
