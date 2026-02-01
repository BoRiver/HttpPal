package com.httppal.settings

import com.httppal.model.EndpointInfo
import com.httppal.model.Environment
import com.httppal.model.SerializableEnvironment
import com.httppal.util.MapUtils.safeMapOf
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger

/**
 * Project-level settings for HttpPal plugin
 */
@Service(Service.Level.PROJECT)
@State(
    name = "HttpPalProjectSettings",
    storages = [Storage("httppal-project.xml")]
)
class ProjectSettings : PersistentStateComponent<ProjectSettings.State> {
    
    private val logger = Logger.getInstance(ProjectSettings::class.java)
    
    data class State(
        var manualEndpoints: MutableList<EndpointInfo?> = mutableListOf(),
        var discoveryEnabled: Boolean = true,
        var autoRefreshEndpoints: Boolean = true,
        var excludedPackages: MutableList<String?> = mutableListOf(),
        var includedPackages: MutableList<String?> = mutableListOf(),
        var environments: MutableList<SerializableEnvironment?> = mutableListOf(),
        var currentEnvironmentId: String? = null
    )
    
    private var myState = State()
    
    override fun getState(): State {
        logger.info("ProjectSettings.getState() called: environments=${myState.environments.size}, currentEnvId=${myState.currentEnvironmentId}")
        return myState
    }
    
    override fun loadState(state: State) {
        logger.info("ProjectSettings.loadState() called: environments=${state.environments.size}, currentEnvId=${state.currentEnvironmentId}")

        // Clean up any null values that may have been persisted
        state.manualEndpoints.removeAll { it == null }
        state.environments.removeAll { it == null }
        state.excludedPackages.removeAll { it == null }
        state.includedPackages.removeAll { it == null }

        myState = state
        logger.info("ProjectSettings state loaded successfully")
    }
    
    // Manual Endpoints
    fun getManualEndpoints(): List<EndpointInfo> = myState.manualEndpoints.filterNotNull()
    
    fun addManualEndpoint(endpoint: EndpointInfo) {
        // Handle potential null values in the list
        myState.manualEndpoints.removeAll { it != null && it.id == endpoint.id }
        myState.manualEndpoints.add(endpoint)
    }

    fun removeManualEndpoint(endpointId: String) {
        // Handle potential null values in the list
        myState.manualEndpoints.removeAll { it != null && it.id == endpointId }
    }

    fun updateManualEndpoint(endpoint: EndpointInfo) {
        // Handle potential null values in the list
        val index = myState.manualEndpoints.indexOfFirst { it != null && it.id == endpoint.id }
        if (index >= 0) {
            myState.manualEndpoints[index] = endpoint
        }
    }
    
    // Discovery Settings
    fun isDiscoveryEnabled(): Boolean = myState.discoveryEnabled
    
    fun setDiscoveryEnabled(enabled: Boolean) {
        myState.discoveryEnabled = enabled
    }
    
    fun isAutoRefreshEnabled(): Boolean = myState.autoRefreshEndpoints
    
    fun setAutoRefreshEnabled(enabled: Boolean) {
        myState.autoRefreshEndpoints = enabled
    }
    
    // Package Filtering
    fun getExcludedPackages(): List<String> = myState.excludedPackages.filterNotNull()
    
    fun addExcludedPackage(packageName: String) {
        if (!myState.excludedPackages.contains(packageName)) {
            myState.excludedPackages.add(packageName)
        }
    }
    
    fun removeExcludedPackage(packageName: String) {
        myState.excludedPackages.remove(packageName)
    }
    
    fun getIncludedPackages(): List<String> = myState.includedPackages.filterNotNull()
    
    fun addIncludedPackage(packageName: String) {
        if (!myState.includedPackages.contains(packageName)) {
            myState.includedPackages.add(packageName)
        }
    }
    
    fun removeIncludedPackage(packageName: String) {
        myState.includedPackages.remove(packageName)
    }
    
    // Package filtering utilities
    fun isPackageExcluded(packageName: String): Boolean {
        return myState.excludedPackages.filterNotNull().any { excluded ->
            packageName.startsWith(excluded)
        }
    }
    
    fun isPackageIncluded(packageName: String): Boolean {
        if (myState.includedPackages.filterNotNull().isEmpty()) {
            return true // If no includes specified, all packages are included
        }
        return myState.includedPackages.filterNotNull().any { included ->
            packageName.startsWith(included)
        }
    }
    
    fun shouldProcessPackage(packageName: String): Boolean {
        return isPackageIncluded(packageName) && !isPackageExcluded(packageName)
    }
    
    // Environment Management
    fun getEnvironments(): List<Environment> {
        return try {
            val environments = myState.environments.filterNotNull().map { serializable ->
                try {
                    serializable.toEnvironment()
                } catch (e: Exception) {
                    logger.error("Failed to convert SerializableEnvironment to Environment: id=${serializable.id}, error=${e.message}", e)
                    null
                }
            }.filterNotNull()
            
            logger.debug("Successfully loaded ${environments.size} environment entries")
            environments
        } catch (e: Exception) {
            logger.error("Failed to load environments, returning empty list: ${e.message}", e)
            emptyList()
        }
    }
    
    fun addEnvironment(environment: Environment) {
        try {
            logger.info("addEnvironment() called: id=${environment.id}, name=${environment.name}")

            // Convert to SerializableEnvironment
            val serializable = SerializableEnvironment.fromEnvironment(environment)
            logger.debug("Converted Environment to SerializableEnvironment: id=${serializable.id}")

            // Remove existing environment with same ID and add new one
            // Handle potential null values in the list
            myState.environments.removeAll { it != null && it.id == environment.id }
            myState.environments.add(serializable)

            logger.info("Environment persisted successfully: id=${environment.id}, name=${environment.name}, total environments: ${myState.environments.size}")
        } catch (e: Exception) {
            logger.error("Failed to add environment: id=${environment.id}, error=${e.message}", e)
            // Don't throw - environment failure shouldn't break the application
        }
    }
    
    fun removeEnvironment(environmentId: String) {
        logger.info("removeEnvironment() called: id=$environmentId")
        // Handle potential null values in the list
        val removed = myState.environments.removeAll { it != null && it.id == environmentId }
        if (myState.currentEnvironmentId == environmentId) {
            myState.currentEnvironmentId = null
        }
        logger.info("Environment removal result: $removed, remaining environments: ${myState.environments.size}")
    }
    
    fun updateEnvironment(environment: Environment) {
        try {
            logger.info("updateEnvironment() called: id=${environment.id}, name=${environment.name}")

            // Convert to SerializableEnvironment
            val serializable = SerializableEnvironment.fromEnvironment(environment)
            logger.debug("Converted Environment to SerializableEnvironment: id=${serializable.id}")

            // Handle potential null values in the list
            val index = myState.environments.indexOfFirst { it != null && it.id == environment.id }
            if (index >= 0) {
                myState.environments[index] = serializable
                logger.info("Environment updated successfully at index $index")
            } else {
                logger.warn("Environment not found for update: id=${environment.id}")
            }
        } catch (e: Exception) {
            logger.error("Failed to update environment: id=${environment.id}, error=${e.message}", e)
            // Don't throw - environment failure shouldn't break the application
        }
    }
    
    fun getCurrentEnvironmentId(): String? = myState.currentEnvironmentId
    
    fun setCurrentEnvironmentId(environmentId: String?) {
        myState.currentEnvironmentId = environmentId
    }
    
    fun getEnvironmentById(id: String): Environment? {
        return try {
            val serializable = myState.environments.find { it != null && it.id == id }
            serializable?.toEnvironment()
        } catch (e: Exception) {
            logger.error("Failed to get environment by id: $id, error=${e.message}", e)
            null
        }
    }
    
    fun getEnvironmentByName(name: String): Environment? {
        return try {
            val serializable = myState.environments.find { it != null && it.name == name }
            serializable?.toEnvironment()
        } catch (e: Exception) {
            logger.error("Failed to get environment by name: $name, error=${e.message}", e)
            null
        }
    }
    
    /**
     * Get statistics about project settings
     */
    fun getStatistics(): Map<String, Any> {
        return safeMapOf(
            "manualEndpointsCount" to myState.manualEndpoints.size,
            "excludedPackagesCount" to myState.excludedPackages.size,
            "includedPackagesCount" to myState.includedPackages.size,
            "environmentsCount" to myState.environments.size,
            "currentEnvironmentId" to myState.currentEnvironmentId,
            "discoveryEnabled" to myState.discoveryEnabled,
            "autoRefreshEnabled" to myState.autoRefreshEndpoints
        )
    }
    
    companion object {
        fun getInstance(project: Project): ProjectSettings {
            return project.getService(ProjectSettings::class.java)
        }
    }
}