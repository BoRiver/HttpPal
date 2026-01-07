package com.httppal.service.impl

import com.httppal.model.FavoriteRequest
import com.httppal.model.RequestConfig
import com.httppal.model.RequestHistoryEntry
import com.httppal.service.FavoritesService
import com.httppal.service.FavoritesStatistics
import com.httppal.service.ImportResult
import com.httppal.settings.HttpPalSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Implementation of FavoritesService
 */
@Service
class FavoritesServiceImpl : FavoritesService {
    
    private val settings: HttpPalSettings
        get() = HttpPalSettings.getInstance()
    
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        // 手动注册 JavaTimeModule 以支持 Instant, Duration 等类型
        // 不使用 findAndRegisterModules() 以避免 IntelliJ 插件环境中的类加载器冲突
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
    
    override fun getAllFavorites(): List<FavoriteRequest> {
        return settings.getFavorites().sortedBy { it.name.lowercase() }
    }
    
    override fun getFavoritesByFolder(folder: String?): List<FavoriteRequest> {
        return settings.getFavorites()
            .filter { it.folder == folder }
            .sortedBy { it.name.lowercase() }
    }
    
    override fun getAllFolders(): List<String> {
        return settings.getFavorites()
            .mapNotNull { it.folder }
            .distinct()
            .sorted()
    }
    
    override fun addFavorite(favorite: FavoriteRequest): Boolean {
        return try {
            val validationErrors = favorite.validate()
            if (validationErrors.isNotEmpty()) {
                return false
            }
            
            // Check for duplicate names in the same folder
            if (isFavoriteNameExists(favorite.name, favorite.folder)) {
                return false
            }
            
            settings.addFavorite(favorite)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun updateFavorite(favorite: FavoriteRequest): Boolean {
        return try {
            val validationErrors = favorite.validate()
            if (validationErrors.isNotEmpty()) {
                return false
            }
            
            // Check if favorite exists
            val existing = getFavoriteById(favorite.id)
            if (existing == null) {
                return false
            }
            
            // Check for duplicate names (excluding current favorite)
            val duplicateExists = settings.getFavorites()
                .any { it.id != favorite.id && it.name == favorite.name && it.folder == favorite.folder }
            
            if (duplicateExists) {
                return false
            }
            
            settings.addFavorite(favorite) // This will replace existing due to same ID
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun removeFavorite(favoriteId: String): Boolean {
        return try {
            settings.removeFavorite(favoriteId)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun removeFavoritesByFolder(folder: String): Int {
        val favoritesToRemove = getFavoritesByFolder(folder)
        var removedCount = 0
        
        favoritesToRemove.forEach { favorite ->
            if (removeFavorite(favorite.id)) {
                removedCount++
            }
        }
        
        return removedCount
    }
    
    override fun searchFavorites(query: String): List<FavoriteRequest> {
        if (query.isBlank()) {
            return getAllFavorites()
        }
        
        return settings.getFavorites()
            .filter { it.matchesSearch(query) }
            .sortedWith(compareBy<FavoriteRequest> { 
                // Prioritize exact name matches
                if (it.name.equals(query, ignoreCase = true)) 0 else 1
            }.thenBy { 
                // Then by name similarity
                it.name.lowercase()
            })
    }
    
    override fun searchFavoritesByTags(tags: List<String>): List<FavoriteRequest> {
        if (tags.isEmpty()) {
            return getAllFavorites()
        }
        
        return settings.getFavorites()
            .filter { favorite ->
                tags.any { tag -> favorite.hasTag(tag) }
            }
            .sortedBy { it.name.lowercase() }
    }
    
    override fun getFavoritesByUsage(): List<FavoriteRequest> {
        return settings.getFavorites()
            .sortedWith(compareByDescending<FavoriteRequest> { it.useCount }
                .thenByDescending { it.lastUsed ?: Instant.MIN })
    }
    
    override fun getRecentlyUsedFavorites(limit: Int): List<FavoriteRequest> {
        return settings.getFavorites()
            .filter { it.lastUsed != null }
            .sortedByDescending { it.lastUsed }
            .take(limit)
    }
    
    override fun markFavoriteAsUsed(favoriteId: String): Boolean {
        val favorite = getFavoriteById(favoriteId) ?: return false
        val updatedFavorite = favorite.markAsUsed()
        return updateFavorite(updatedFavorite)
    }
    
    override fun createFavoriteFromHistory(historyEntry: RequestHistoryEntry, name: String, folder: String?): FavoriteRequest {
        return FavoriteRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            request = historyEntry.request,
            folder = folder,
            createdAt = Instant.now()
        )
    }
    
    override fun createFavoriteFromRequest(request: RequestConfig, name: String, folder: String?): FavoriteRequest {
        return FavoriteRequest(
            id = UUID.randomUUID().toString(),
            name = name,
            request = request,
            folder = folder,
            createdAt = Instant.now()
        )
    }
    
    override fun duplicateFavorite(favoriteId: String, newName: String): FavoriteRequest? {
        val original = getFavoriteById(favoriteId) ?: return null
        
        val duplicate = original.copy(
            id = UUID.randomUUID().toString(),
            name = newName,
            createdAt = Instant.now(),
            lastUsed = null,
            useCount = 0
        )
        
        return if (addFavorite(duplicate)) duplicate else null
    }
    
    override fun moveFavoriteToFolder(favoriteId: String, folder: String?): Boolean {
        val favorite = getFavoriteById(favoriteId) ?: return false
        val updatedFavorite = favorite.copy(folder = folder)
        return updateFavorite(updatedFavorite)
    }
    
    override fun renameFolder(oldFolder: String, newFolder: String): Int {
        val favoritesInFolder = getFavoritesByFolder(oldFolder)
        var renamedCount = 0
        
        favoritesInFolder.forEach { favorite ->
            val updatedFavorite = favorite.copy(folder = newFolder)
            if (updateFavorite(updatedFavorite)) {
                renamedCount++
            }
        }
        
        return renamedCount
    }
    
    override fun getFavoriteById(favoriteId: String): FavoriteRequest? {
        return settings.getFavorites().find { it.id == favoriteId }
    }
    
    override fun isFavoriteNameExists(name: String, folder: String?): Boolean {
        return settings.getFavorites()
            .any { it.name.equals(name, ignoreCase = true) && it.folder == folder }
    }
    
    override fun getFavoritesStatistics(): FavoritesStatistics {
        val favorites = settings.getFavorites()
        val sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS)
        
        return FavoritesStatistics(
            totalFavorites = favorites.size,
            totalFolders = getAllFolders().size,
            mostUsedFavorite = favorites.maxByOrNull { it.useCount },
            recentlyAddedCount = favorites.count { it.createdAt.isAfter(sevenDaysAgo) },
            averageUseCount = if (favorites.isNotEmpty()) {
                favorites.map { it.useCount }.average()
            } else 0.0,
            favoritesByMethod = favorites.groupBy { it.request.method.name }
                .mapValues { it.value.size }
        )
    }
    
    override fun exportFavorites(): String {
        return try {
            val favorites = settings.getFavorites()
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(favorites)
        } catch (e: Exception) {
            "{\"error\": \"Failed to export favorites: ${e.message}\"}"
        }
    }
    
    override fun importFavorites(json: String): ImportResult {
        return try {
            val importedFavorites = objectMapper.readValue<List<FavoriteRequest>>(json)
            var importedCount = 0
            var skippedCount = 0
            val errors = mutableListOf<String>()
            
            importedFavorites.forEach { favorite ->
                try {
                    // Generate new ID to avoid conflicts
                    val newFavorite = favorite.copy(
                        id = UUID.randomUUID().toString(),
                        createdAt = Instant.now()
                    )
                    
                    if (addFavorite(newFavorite)) {
                        importedCount++
                    } else {
                        skippedCount++
                        errors.add("Skipped '${favorite.name}': validation failed or duplicate name")
                    }
                } catch (e: Exception) {
                    skippedCount++
                    errors.add("Failed to import '${favorite.name}': ${e.message}")
                }
            }
            
            ImportResult(
                success = errors.isEmpty(),
                importedCount = importedCount,
                skippedCount = skippedCount,
                errors = errors
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                importedCount = 0,
                skippedCount = 0,
                errors = listOf("Failed to parse JSON: ${e.message}")
            )
        }
    }
    
    companion object {
        fun getInstance(): FavoritesService {
            return ApplicationManager.getApplication().getService(FavoritesService::class.java)
        }
    }
}