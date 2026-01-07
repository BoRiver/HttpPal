package com.httppal.settings

import com.httppal.model.Environment
import com.httppal.model.FavoriteRequest
import com.httppal.model.RequestHistoryEntry
import com.httppal.model.SerializableHistoryEntry
import com.httppal.model.SerializableFavoriteRequest
import com.httppal.util.MapUtils.safeMapOf
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.Transient
import com.intellij.util.xmlb.annotations.XCollection
import java.time.Duration

/**
 * Application-level settings for HttpPal plugin
 * Uses PasswordSafe for sensitive data like authentication headers
 */
@Service
@State(
    name = "HttpPalSettings",
    storages = [Storage("httppal.xml")]
)
class HttpPalSettings : PersistentStateComponent<HttpPalSettings.State> {
    
    private val logger = Logger.getInstance(HttpPalSettings::class.java)
    
    class State {
        var globalHeaders: MutableMap<String, String> = mutableMapOf()
        var sensitiveHeaderNames: MutableSet<String> = mutableSetOf()
        var currentEnvironmentId: String? = null
        
        @XCollection(style = XCollection.Style.v2)
        var environments: MutableList<Environment> = mutableListOf()
        
        // Use SerializableFavoriteRequest to avoid Instant/Duration serialization issues
        @XCollection(style = XCollection.Style.v2)
        var favorites: MutableList<SerializableFavoriteRequest> = mutableListOf()
        
        // Use SerializableHistoryEntry to avoid Instant/Duration serialization issues
        @XCollection(style = XCollection.Style.v2)
        var history: MutableList<SerializableHistoryEntry> = mutableListOf()
        
        var maxHistorySize: Int = 1000
        var defaultTimeout: Long = 30000 // milliseconds
        var defaultThreadCount: Int = 10
        var autoSaveRequests: Boolean = true
        var enableHistoryPersistence: Boolean = true
        var excludeSensitiveFromHistory: Boolean = true
        
        // Performance settings
        var maxConcurrentRequests: Int = 50
        var connectionPoolSize: Int = 20
        var requestRetryCount: Int = 3
        
        // UI settings
        var enableSyntaxHighlighting: Boolean = true
        var autoFormatResponse: Boolean = true
        var showResponseTime: Boolean = true
    }
    
    private var myState = State()
    
    @Transient
    private val passwordSafe = PasswordSafe.instance
    
    companion object {
        private const val HTTPPAL_SERVICE_NAME = "HttpPal Plugin"
        private const val GLOBAL_HEADERS_KEY = "global_headers"
        
        // Common sensitive header names
        private val DEFAULT_SENSITIVE_HEADERS = setOf(
            "authorization", "auth", "x-api-key", "x-auth-token", 
            "bearer", "token", "password", "secret", "key"
        )
        
        fun getInstance(): HttpPalSettings {
            return ApplicationManager.getApplication().getService(HttpPalSettings::class.java)
        }
    }
    
    override fun getState(): State {
        return try {
            logger.info("Saving state: ${myState.history.size} history entries")
            myState
        } catch (e: Exception) {
            logger.error("Failed to serialize state: ${e.message}", e)
            // Return empty state to prevent corruption
            State()
        }
    }
    
    override fun loadState(state: State) {
        try {
            logger.info("Loading HttpPalSettings state: history entries=${state.history.size}, favorites=${state.favorites.size}")
            myState = state
            
            // Initialize default sensitive headers if not set
            if (myState.sensitiveHeaderNames.isEmpty()) {
                myState.sensitiveHeaderNames.addAll(DEFAULT_SENSITIVE_HEADERS)
            }
            
            // Perform data migration if needed
            performDataMigration()
            
            logger.info("HttpPalSettings state loaded successfully")
        } catch (e: Exception) {
            logger.error("Failed to deserialize state, using empty state: ${e.message}", e)
            myState = State()
            myState.sensitiveHeaderNames.addAll(DEFAULT_SENSITIVE_HEADERS)
        }
    }
    
    // Global Headers with PasswordSafe integration
    fun getGlobalHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers.putAll(myState.globalHeaders)
        
        // Load sensitive headers from PasswordSafe
        val sensitiveHeaders = loadSensitiveHeaders()
        headers.putAll(sensitiveHeaders)
        
        return headers.toMap()
    }
    
    fun setGlobalHeaders(headers: Map<String, String>) {
        val (sensitive, nonSensitive) = separateHeaders(headers)
        
        // Store non-sensitive headers in regular state
        myState.globalHeaders.clear()
        myState.globalHeaders.putAll(nonSensitive)
        
        // Store sensitive headers in PasswordSafe
        saveSensitiveHeaders(sensitive)
    }
    
    fun addGlobalHeader(name: String, value: String) {
        if (isSensitiveHeader(name)) {
            val sensitiveHeaders = loadSensitiveHeaders().toMutableMap()
            sensitiveHeaders[name] = value
            saveSensitiveHeaders(sensitiveHeaders)
        } else {
            myState.globalHeaders[name] = value
        }
    }
    
    fun removeGlobalHeader(name: String) {
        if (isSensitiveHeader(name)) {
            val sensitiveHeaders = loadSensitiveHeaders().toMutableMap()
            sensitiveHeaders.remove(name)
            saveSensitiveHeaders(sensitiveHeaders)
        } else {
            myState.globalHeaders.remove(name)
        }
    }
    
    fun clearGlobalHeaders() {
        myState.globalHeaders.clear()
        saveSensitiveHeaders(emptyMap())
    }
    
    // Environment Management
    fun getCurrentEnvironmentId(): String? = myState.currentEnvironmentId
    
    fun setCurrentEnvironmentId(environmentId: String?) {
        myState.currentEnvironmentId = environmentId
    }
    
    fun getEnvironments(): List<Environment> = myState.environments.toList()
    
    fun addEnvironment(environment: Environment) {
        myState.environments.removeIf { it.id == environment.id }
        myState.environments.add(environment)
    }
    
    fun removeEnvironment(environmentId: String) {
        myState.environments.removeIf { it.id == environmentId }
        if (myState.currentEnvironmentId == environmentId) {
            myState.currentEnvironmentId = null
        }
    }
    
    // Favorites Management
    fun getFavorites(): List<FavoriteRequest> {
        return try {
            val favorites = myState.favorites.map { serializable ->
                try {
                    serializable.toFavoriteRequest()
                } catch (e: Exception) {
                    logger.error("Failed to convert SerializableFavoriteRequest to FavoriteRequest: id=${serializable.id}, error=${e.message}", e)
                    null
                }
            }.filterNotNull()
            
            logger.debug("Successfully loaded ${favorites.size} favorite entries")
            favorites
        } catch (e: Exception) {
            logger.error("Failed to load favorites, returning empty list: ${e.message}", e)
            emptyList()
        }
    }
    
    fun addFavorite(favorite: FavoriteRequest) {
        try {
            logger.debug("Adding favorite: id=${favorite.id}, name=${favorite.name}")
            
            // Convert to SerializableFavoriteRequest
            val serializable = SerializableFavoriteRequest.fromFavoriteRequest(favorite)
            logger.debug("Converted FavoriteRequest to SerializableFavoriteRequest: id=${serializable.id}")
            
            // Remove existing favorite with same ID and add new one
            myState.favorites.removeIf { it.id == favorite.id }
            myState.favorites.add(serializable)
            
            logger.info("Favorite persisted successfully: id=${favorite.id}, name=${favorite.name}")
        } catch (e: Exception) {
            logger.error("Failed to add favorite: id=${favorite.id}, error=${e.message}", e)
            // Don't throw - favorite failure shouldn't break the application
        }
    }
    
    fun removeFavorite(favoriteId: String) {
        myState.favorites.removeIf { it.id == favoriteId }
    }
    
    // History Management with size limits and sensitive data filtering
    fun getHistory(): List<RequestHistoryEntry> {
        logger.debug("Getting history: ${myState.history.size} entries")
        
        return try {
            val entries = myState.history.map { serializable ->
                try {
                    serializable.toHistoryEntry()
                } catch (e: Exception) {
                    logger.error("Failed to convert SerializableHistoryEntry to RequestHistoryEntry: id=${serializable.id}, error=${e.message}", e)
                    null
                }
            }.filterNotNull()
            
            logger.debug("Successfully loaded ${entries.size} history entries")
            entries
        } catch (e: Exception) {
            logger.error("Failed to load history, returning empty list: ${e.message}", e)
            emptyList()
        }
    }
    
    fun addToHistory(entry: RequestHistoryEntry) {
        logger.info("HttpPalSettings.addToHistory() called: id=${entry.id}, url=${entry.request.url}")
        
        if (!myState.enableHistoryPersistence) {
            logger.warn("History persistence is disabled, skipping add")
            return
        }
        
        try {
            val processedEntry = if (myState.excludeSensitiveFromHistory) {
                logger.debug("Sanitizing history entry to exclude sensitive headers")
                sanitizeHistoryEntry(entry)
            } else {
                entry
            }
            
            // Convert to SerializableHistoryEntry
            val serializable = SerializableHistoryEntry.fromHistoryEntry(processedEntry)
            logger.debug("Converted RequestHistoryEntry to SerializableHistoryEntry: id=${serializable.id}")
            
            // Add to beginning of list
            myState.history.add(0, serializable)
            logger.debug("History entry added to state, current size: ${myState.history.size}")
            
            // Trim history if it exceeds max size
            trimHistoryToSize()
            
            logger.info("History entry persisted successfully, final size: ${myState.history.size}")
        } catch (e: Exception) {
            logger.error("Failed to add history entry: id=${entry.id}, error=${e.message}", e)
            // Don't throw - history failure shouldn't break the application
        }
    }
    
    fun clearHistory() {
        myState.history.clear()
    }
    
    fun removeFromHistory(entryId: String) {
        myState.history.removeIf { it.id == entryId }
    }
    
    fun getHistoryByTimeRange(startTime: java.time.Instant, endTime: java.time.Instant): List<RequestHistoryEntry> {
        return try {
            myState.history
                .map { it.toHistoryEntry() }
                .filter { entry ->
                    entry.timestamp.isAfter(startTime) && entry.timestamp.isBefore(endTime)
                }
        } catch (e: Exception) {
            logger.error("Failed to get history by time range: ${e.message}", e)
            emptyList()
        }
    }
    
    fun searchHistory(query: String): List<RequestHistoryEntry> {
        if (query.isBlank()) return getHistory()
        
        return try {
            myState.history
                .map { it.toHistoryEntry() }
                .filter { entry ->
                    entry.matchesSearch(query)
                }
        } catch (e: Exception) {
            logger.error("Failed to search history: ${e.message}", e)
            emptyList()
        }
    }
    
    // Settings
    fun getMaxHistorySize(): Int = myState.maxHistorySize
    
    fun setMaxHistorySize(size: Int) {
        if (size > 0 && size <= 10000) {
            myState.maxHistorySize = size
            // Trim current history if needed
            trimHistoryToSize()
        }
    }
    
    fun getDefaultTimeout(): Duration = Duration.ofMillis(myState.defaultTimeout)
    
    fun setDefaultTimeout(timeout: Duration) {
        if (!timeout.isNegative && !timeout.isZero && timeout.toSeconds() <= 300) {
            myState.defaultTimeout = timeout.toMillis()
        }
    }
    
    fun getDefaultThreadCount(): Int = myState.defaultThreadCount
    
    fun setDefaultThreadCount(count: Int) {
        if (count > 0 && count <= 100) {
            myState.defaultThreadCount = count
        }
    }
    
    fun isAutoSaveRequests(): Boolean = myState.autoSaveRequests
    
    fun setAutoSaveRequests(autoSave: Boolean) {
        myState.autoSaveRequests = autoSave
    }
    
    // History persistence settings
    fun isHistoryPersistenceEnabled(): Boolean = myState.enableHistoryPersistence
    
    fun setHistoryPersistenceEnabled(enabled: Boolean) {
        myState.enableHistoryPersistence = enabled
        if (!enabled) {
            clearHistory()
        }
    }
    
    fun isExcludeSensitiveFromHistory(): Boolean = myState.excludeSensitiveFromHistory
    
    fun setExcludeSensitiveFromHistory(exclude: Boolean) {
        myState.excludeSensitiveFromHistory = exclude
    }
    
    // Performance settings
    fun getMaxConcurrentRequests(): Int = myState.maxConcurrentRequests
    
    fun setMaxConcurrentRequests(count: Int) {
        if (count > 0 && count <= 100) {
            myState.maxConcurrentRequests = count
        }
    }
    
    fun getConnectionPoolSize(): Int = myState.connectionPoolSize
    
    fun setConnectionPoolSize(size: Int) {
        if (size > 0 && size <= 50) {
            myState.connectionPoolSize = size
        }
    }
    
    fun getRequestRetryCount(): Int = myState.requestRetryCount
    
    fun setRequestRetryCount(count: Int) {
        if (count >= 0 && count <= 10) {
            myState.requestRetryCount = count
        }
    }
    
    // UI settings
    fun isSyntaxHighlightingEnabled(): Boolean = myState.enableSyntaxHighlighting
    
    fun setSyntaxHighlightingEnabled(enabled: Boolean) {
        myState.enableSyntaxHighlighting = enabled
    }
    
    fun isAutoFormatResponseEnabled(): Boolean = myState.autoFormatResponse
    
    fun setAutoFormatResponseEnabled(enabled: Boolean) {
        myState.autoFormatResponse = enabled
    }
    
    fun isShowResponseTimeEnabled(): Boolean = myState.showResponseTime
    
    fun setShowResponseTimeEnabled(enabled: Boolean) {
        myState.showResponseTime = enabled
    }
    
    // Sensitive header management
    fun getSensitiveHeaderNames(): Set<String> = myState.sensitiveHeaderNames.toSet()
    
    fun addSensitiveHeaderName(headerName: String) {
        myState.sensitiveHeaderNames.add(headerName.lowercase())
    }
    
    fun removeSensitiveHeaderName(headerName: String) {
        myState.sensitiveHeaderNames.remove(headerName.lowercase())
    }
    
    fun isSensitiveHeader(headerName: String): Boolean {
        val lowerName = headerName.lowercase()
        return myState.sensitiveHeaderNames.any { lowerName.contains(it) }
    }
    
    // Private helper methods
    private fun separateHeaders(headers: Map<String, String>): Pair<Map<String, String>, Map<String, String>> {
        val sensitive = mutableMapOf<String, String>()
        val nonSensitive = mutableMapOf<String, String>()
        
        headers.forEach { (name, value) ->
            if (isSensitiveHeader(name)) {
                sensitive[name] = value
            } else {
                nonSensitive[name] = value
            }
        }
        
        return Pair(sensitive, nonSensitive)
    }
    
    private fun loadSensitiveHeaders(): Map<String, String> {
        val credentialAttributes = CredentialAttributes(
            generateServiceName(HTTPPAL_SERVICE_NAME, GLOBAL_HEADERS_KEY)
        )
        
        val credentials = passwordSafe.get(credentialAttributes)
        return if (credentials?.password != null) {
            parseHeadersFromString(credentials.password!!.toString())
        } else {
            emptyMap()
        }
    }
    
    private fun saveSensitiveHeaders(headers: Map<String, String>) {
        val credentialAttributes = CredentialAttributes(
            generateServiceName(HTTPPAL_SERVICE_NAME, GLOBAL_HEADERS_KEY)
        )
        
        if (headers.isEmpty()) {
            passwordSafe.set(credentialAttributes, null)
        } else {
            val headersString = serializeHeadersToString(headers)
            val credentials = Credentials("httppal", headersString)
            passwordSafe.set(credentialAttributes, credentials)
        }
    }
    
    private fun parseHeadersFromString(headersString: String): Map<String, String> {
        return try {
            headersString.split("\n")
                .filter { it.isNotBlank() && it.contains(":") }
                .associate { line ->
                    val parts = line.split(":", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun serializeHeadersToString(headers: Map<String, String>): String {
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
    
    private fun sanitizeHistoryEntry(entry: RequestHistoryEntry): RequestHistoryEntry {
        val sanitizedRequest = entry.request.copy(
            headers = entry.request.headers.filterKeys { !isSensitiveHeader(it) }
        )
        return entry.copy(request = sanitizedRequest)
    }
    
    private fun trimHistoryToSize() {
        val maxSize = myState.maxHistorySize
        if (myState.history.size > maxSize) {
            val removed = myState.history.size - maxSize
            myState.history.removeAt(myState.history.size - 1)
            logger.info("Trimmed $removed history entries (max size: $maxSize), current size: ${myState.history.size}")
        }
    }
    
    /**
     * Export settings to a map for backup/restore
     * Note: Sensitive headers are not included in exports for security
     */
    fun exportSettings(): Map<String, Any> {
        return safeMapOf(
            // General settings
            "maxHistorySize" to myState.maxHistorySize,
            "defaultTimeout" to myState.defaultTimeout,
            "defaultThreadCount" to myState.defaultThreadCount,
            "autoSaveRequests" to myState.autoSaveRequests,
            "enableHistoryPersistence" to myState.enableHistoryPersistence,
            "excludeSensitiveFromHistory" to myState.excludeSensitiveFromHistory,
            // Security settings (names only, not values)
            "sensitiveHeaderNames" to myState.sensitiveHeaderNames.toList(),
            // Environment settings
            "environments" to myState.environments.toList(),
            "currentEnvironmentId" to myState.currentEnvironmentId,
            // Non-sensitive global headers only
            "globalHeaders" to myState.globalHeaders.toMap(),
            // Performance settings
            "maxConcurrentRequests" to myState.maxConcurrentRequests,
            "connectionPoolSize" to myState.connectionPoolSize,
            "requestRetryCount" to myState.requestRetryCount,
            // UI settings
            "enableSyntaxHighlighting" to myState.enableSyntaxHighlighting,
            "autoFormatResponse" to myState.autoFormatResponse,
            "showResponseTime" to myState.showResponseTime,
            // Metadata
            "exportVersion" to "1.0",
            "exportTimestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * Get statistics about current settings
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "historyCount" to myState.history.size,
            "favoritesCount" to myState.favorites.size,
            "environmentsCount" to myState.environments.size,
            "globalHeadersCount" to myState.globalHeaders.size,
            "sensitiveHeadersCount" to myState.sensitiveHeaderNames.size,
            "historyUsagePercent" to if (myState.maxHistorySize > 0) {
                (myState.history.size * 100) / myState.maxHistorySize
            } else 0,
            "maxConcurrentRequests" to myState.maxConcurrentRequests,
            "connectionPoolSize" to myState.connectionPoolSize,
            "requestRetryCount" to myState.requestRetryCount,
            "defaultTimeoutSeconds" to myState.defaultTimeout / 1000,
            "defaultThreadCount" to myState.defaultThreadCount
        )
    }
    
    /**
     * Import settings from a map (used by configuration UI)
     */
    fun importSettings(importData: Map<String, Any>) {
        try {
            // General settings
            importData["maxHistorySize"]?.toString()?.toIntOrNull()?.let { 
                if (it in 1..10000) setMaxHistorySize(it)
            }
            importData["defaultTimeout"]?.toString()?.toLongOrNull()?.let { 
                if (it in 1000..300000) setDefaultTimeout(Duration.ofMillis(it))
            }
            importData["defaultThreadCount"]?.toString()?.toIntOrNull()?.let { 
                if (it in 1..100) setDefaultThreadCount(it)
            }
            importData["autoSaveRequests"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setAutoSaveRequests(it)
            }
            importData["enableHistoryPersistence"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setHistoryPersistenceEnabled(it)
            }
            importData["excludeSensitiveFromHistory"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setExcludeSensitiveFromHistory(it)
            }
            
            // Performance settings
            importData["maxConcurrentRequests"]?.toString()?.toIntOrNull()?.let { 
                if (it in 1..100) setMaxConcurrentRequests(it)
            }
            importData["connectionPoolSize"]?.toString()?.toIntOrNull()?.let { 
                if (it in 1..50) setConnectionPoolSize(it)
            }
            importData["requestRetryCount"]?.toString()?.toIntOrNull()?.let { 
                if (it in 0..10) setRequestRetryCount(it)
            }
            
            // UI settings
            importData["enableSyntaxHighlighting"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setSyntaxHighlightingEnabled(it)
            }
            importData["autoFormatResponse"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setAutoFormatResponseEnabled(it)
            }
            importData["showResponseTime"]?.toString()?.toBooleanStrictOrNull()?.let { 
                setShowResponseTimeEnabled(it)
            }
            
            // Security settings (header names only)
            @Suppress("UNCHECKED_CAST")
            (importData["sensitiveHeaderNames"] as? List<String>)?.let { headerNames ->
                // Clear existing and add imported ones
                val currentHeaders = getSensitiveHeaderNames().toList()
                currentHeaders.forEach { removeSensitiveHeaderName(it) }
                headerNames.forEach { addSensitiveHeaderName(it) }
            }
            
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid import data format: ${e.message}", e)
        }
    }
    
    /**
     * Perform data migration for compatibility with older versions
     */
    private fun performDataMigration() {
        try {
            logger.debug("Performing data migration check")
            
            // Migration is handled automatically by the serialization framework
            // If old data exists, it will be loaded as empty lists and new data will be created
            // This is safe because we're using different field types now
            
            logger.debug("Data migration completed successfully")
        } catch (e: Exception) {
            logger.warn("Data migration encountered issues: ${e.message}", e)
            // Continue with empty state - better than crashing
        }
    }
}