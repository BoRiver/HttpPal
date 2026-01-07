package com.httppal.model

import com.intellij.openapi.diagnostic.Logger
import java.util.*

/**
 * Serializable wrapper for Environment
 * Uses simple types to avoid Map serialization issues
 */
data class SerializableEnvironment(
    var id: String = "",
    var name: String = "",
    var baseUrl: String = "",
    var globalHeadersKeys: List<String> = emptyList(),
    var globalHeadersValues: List<String> = emptyList(),
    var description: String? = null,
    var variablesKeys: List<String> = emptyList(),
    var variablesValues: List<String> = emptyList(),
    var isActive: Boolean = false
) {
    companion object {
        private val logger = Logger.getInstance(SerializableEnvironment::class.java)
        
        /**
         * Convert Environment to SerializableEnvironment
         */
        fun fromEnvironment(environment: Environment): SerializableEnvironment {
            return try {
                logger.debug("Converting Environment to SerializableEnvironment: id=${environment.id}")
                
                val globalHeadersEntries = environment.globalHeaders.entries.toList()
                val variablesEntries = environment.variables.entries.toList()
                
                SerializableEnvironment(
                    id = environment.id,
                    name = environment.name,
                    baseUrl = environment.baseUrl,
                    globalHeadersKeys = globalHeadersEntries.map { it.key },
                    globalHeadersValues = globalHeadersEntries.map { it.value },
                    description = environment.description,
                    variablesKeys = variablesEntries.map { it.key },
                    variablesValues = variablesEntries.map { it.value },
                    isActive = environment.isActive
                ).also {
                    logger.debug("Successfully converted Environment: id=${environment.id}, name=${environment.name}")
                }
            } catch (e: Exception) {
                logger.error("Failed to convert Environment to SerializableEnvironment: id=${environment.id}, error=${e.message}", e)
                throw IllegalArgumentException("Failed to serialize environment: ${e.message}", e)
            }
        }
    }
    
    /**
     * Convert SerializableEnvironment to Environment
     */
    fun toEnvironment(): Environment {
        return try {
            logger.debug("Converting SerializableEnvironment to Environment: id=$id")
            
            // Validate required fields
            if (id.isBlank()) {
                logger.warn("SerializableEnvironment has blank id, generating new one")
            }
            if (name.isBlank()) {
                logger.warn("SerializableEnvironment has blank name: id=$id")
            }
            if (baseUrl.isBlank()) {
                logger.warn("SerializableEnvironment has blank baseUrl: id=$id")
            }
            
            // Reconstruct maps from parallel lists
            val globalHeaders = try {
                if (globalHeadersKeys.size == globalHeadersValues.size) {
                    globalHeadersKeys.zip(globalHeadersValues).toMap()
                } else {
                    logger.warn("Mismatched globalHeaders lists for id=$id: keys=${globalHeadersKeys.size}, values=${globalHeadersValues.size}")
                    emptyMap()
                }
            } catch (e: Exception) {
                logger.warn("Failed to reconstruct globalHeaders for id=$id: ${e.message}")
                emptyMap()
            }
            
            val variables = try {
                if (variablesKeys.size == variablesValues.size) {
                    variablesKeys.zip(variablesValues).toMap()
                } else {
                    logger.warn("Mismatched variables lists for id=$id: keys=${variablesKeys.size}, values=${variablesValues.size}")
                    emptyMap()
                }
            } catch (e: Exception) {
                logger.warn("Failed to reconstruct variables for id=$id: ${e.message}")
                emptyMap()
            }
            
            Environment(
                id = id.ifBlank { UUID.randomUUID().toString() },
                name = name.ifBlank { "Unnamed Environment" },
                baseUrl = baseUrl.ifBlank { "http://localhost" },
                globalHeaders = globalHeaders,
                description = description,
                variables = variables,
                isActive = isActive
            ).also {
                logger.debug("Successfully converted SerializableEnvironment: id=$id, name=$name")
            }
        } catch (e: Exception) {
            logger.error("Failed to convert SerializableEnvironment to Environment: id=$id, error=${e.message}", e)
            
            // Return a minimal valid entry instead of throwing to prevent data loss
            logger.warn("Creating fallback Environment for id=$id")
            Environment(
                id = id.ifBlank { UUID.randomUUID().toString() },
                name = name.ifBlank { "Corrupted Environment" },
                baseUrl = baseUrl.ifBlank { "http://localhost" },
                globalHeaders = emptyMap(),
                description = description,
                variables = emptyMap(),
                isActive = false
            )
        }
    }
}