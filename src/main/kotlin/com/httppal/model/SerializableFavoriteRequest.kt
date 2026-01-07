package com.httppal.model

import com.intellij.openapi.diagnostic.Logger
import java.time.Instant
import java.util.*

/**
 * Serializable wrapper for FavoriteRequest
 * Uses primitive types to avoid Instant serialization issues
 */
data class SerializableFavoriteRequest(
    var id: String = "",
    var name: String = "",
    var requestMethod: String = "",
    var requestUrl: String = "",
    var requestHeaders: Map<String, String> = emptyMap(),
    var requestBody: String? = null,
    var requestTimeoutMillis: Long = 30000,
    var requestFollowRedirects: Boolean = true,
    var requestQueryParameters: Map<String, String> = emptyMap(),
    var requestPathParameters: Map<String, String> = emptyMap(),
    var tags: List<String> = emptyList(),
    var createdAtMillis: Long = 0,
    var lastUsedMillis: Long = 0,
    var useCount: Int = 0,
    var folder: String? = null
) {
    companion object {
        private val logger = Logger.getInstance(SerializableFavoriteRequest::class.java)
        
        /**
         * Convert FavoriteRequest to SerializableFavoriteRequest
         */
        fun fromFavoriteRequest(favorite: FavoriteRequest): SerializableFavoriteRequest {
            return try {
                logger.debug("Converting FavoriteRequest to SerializableFavoriteRequest: id=${favorite.id}")
                
                SerializableFavoriteRequest(
                    id = favorite.id,
                    name = favorite.name,
                    requestMethod = favorite.request.method.name,
                    requestUrl = favorite.request.url,
                    requestHeaders = favorite.request.headers,
                    requestBody = favorite.request.body,
                    requestTimeoutMillis = favorite.request.timeout.toMillis(),
                    requestFollowRedirects = favorite.request.followRedirects,
                    requestQueryParameters = favorite.request.queryParameters,
                    requestPathParameters = favorite.request.pathParameters,
                    tags = favorite.tags,
                    createdAtMillis = favorite.createdAt.toEpochMilli(),
                    lastUsedMillis = favorite.lastUsed?.toEpochMilli() ?: 0,
                    useCount = favorite.useCount,
                    folder = favorite.folder
                ).also {
                    logger.debug("Successfully converted FavoriteRequest: id=${favorite.id}, name=${favorite.name}")
                }
            } catch (e: Exception) {
                logger.error("Failed to convert FavoriteRequest to SerializableFavoriteRequest: id=${favorite.id}, error=${e.message}", e)
                throw IllegalArgumentException("Failed to serialize favorite request: ${e.message}", e)
            }
        }
    }
    
    /**
     * Convert SerializableFavoriteRequest to FavoriteRequest
     */
    fun toFavoriteRequest(): FavoriteRequest {
        return try {
            logger.debug("Converting SerializableFavoriteRequest to FavoriteRequest: id=$id")
            
            // Validate required fields
            if (id.isBlank()) {
                logger.warn("SerializableFavoriteRequest has blank id, generating new one")
            }
            if (name.isBlank()) {
                logger.warn("SerializableFavoriteRequest has blank name: id=$id")
            }
            if (requestUrl.isBlank()) {
                logger.warn("SerializableFavoriteRequest has blank URL: id=$id")
            }
            if (requestMethod.isBlank()) {
                logger.warn("SerializableFavoriteRequest has blank method: id=$id, defaulting to GET")
            }
            
            // Parse HTTP method with fallback
            val method = try {
                HttpMethod.valueOf(requestMethod)
            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid HTTP method '$requestMethod' for id=$id, defaulting to GET")
                HttpMethod.GET
            }
            
            // Create timeout with validation
            val timeout = try {
                if (requestTimeoutMillis > 0) {
                    java.time.Duration.ofMillis(requestTimeoutMillis)
                } else {
                    logger.warn("Invalid timeout for id=$id, using default 30 seconds")
                    java.time.Duration.ofSeconds(30)
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse timeout for id=$id: ${e.message}, using default")
                java.time.Duration.ofSeconds(30)
            }
            
            val request = RequestConfig(
                method = method,
                url = requestUrl.ifBlank { "http://unknown" },
                headers = requestHeaders,
                body = requestBody,
                timeout = timeout,
                followRedirects = requestFollowRedirects,
                queryParameters = requestQueryParameters,
                pathParameters = requestPathParameters
            )
            
            // Validate and create timestamps
            val createdAt = try {
                if (createdAtMillis > 0) {
                    Instant.ofEpochMilli(createdAtMillis)
                } else {
                    logger.warn("Invalid createdAt timestamp for id=$id, using current time")
                    Instant.now()
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse createdAt timestamp for id=$id: ${e.message}, using current time")
                Instant.now()
            }
            
            val lastUsed = if (lastUsedMillis > 0) {
                try {
                    Instant.ofEpochMilli(lastUsedMillis)
                } catch (e: Exception) {
                    logger.warn("Failed to parse lastUsed timestamp for id=$id: ${e.message}")
                    null
                }
            } else {
                null
            }
            
            FavoriteRequest(
                id = id.ifBlank { UUID.randomUUID().toString() },
                name = name.ifBlank { "Unnamed Favorite" },
                request = request,
                tags = tags,
                createdAt = createdAt,
                lastUsed = lastUsed,
                useCount = maxOf(0, useCount), // Ensure non-negative
                folder = folder
            ).also {
                logger.debug("Successfully converted SerializableFavoriteRequest: id=$id, name=$name")
            }
        } catch (e: Exception) {
            logger.error("Failed to convert SerializableFavoriteRequest to FavoriteRequest: id=$id, error=${e.message}", e)
            
            // Return a minimal valid entry instead of throwing to prevent data loss
            logger.warn("Creating fallback FavoriteRequest for id=$id")
            FavoriteRequest(
                id = id.ifBlank { UUID.randomUUID().toString() },
                name = name.ifBlank { "Corrupted Favorite" },
                request = RequestConfig(
                    method = HttpMethod.GET,
                    url = requestUrl.ifBlank { "http://unknown" }
                ),
                tags = emptyList(),
                createdAt = if (createdAtMillis > 0) {
                    try {
                        Instant.ofEpochMilli(createdAtMillis)
                    } catch (ex: Exception) {
                        Instant.now()
                    }
                } else {
                    Instant.now()
                },
                lastUsed = null,
                useCount = 0,
                folder = folder
            )
        }
    }
}