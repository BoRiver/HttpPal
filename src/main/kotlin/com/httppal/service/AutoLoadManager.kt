package com.httppal.service

import com.httppal.model.DiscoveredEndpoint
import com.httppal.util.ErrorHandler
import com.httppal.util.HttpPalBundle
import com.httppal.util.LoggingUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manager for automatic endpoint loading functionality
 * Implements requirements 1.1, 1.3, 1.4, 1.5:
 * - Auto-load endpoints on plugin startup
 * - Display loading indicators
 * - Handle load success and failure
 * - Provide manual refresh option
 */
@Service(Service.Level.PROJECT)
class AutoLoadManager(private val project: Project) {
    
    private val logger = thisLogger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State management
    private val isLoading = AtomicBoolean(false)
    private val hasLoadedOnce = AtomicBoolean(false)
    private var currentLoadJob: Job? = null
    
    // Listeners for UI updates
    private val loadingStateListeners = ConcurrentHashMap.newKeySet<LoadingStateListener>()
    private val endpointLoadListeners = ConcurrentHashMap.newKeySet<EndpointLoadListener>()
    
    /**
     * Listener interface for loading state changes
     */
    interface LoadingStateListener {
        fun onLoadingStarted(message: String)
        fun onLoadingCompleted()
        fun onLoadingFailed(error: String)
    }
    
    /**
     * Listener interface for endpoint load events
     */
    interface EndpointLoadListener {
        fun onEndpointsLoaded(endpoints: List<DiscoveredEndpoint>)
        fun onEndpointsLoadFailed(error: Throwable)
    }
    
    /**
     * Register a loading state listener
     */
    fun addLoadingStateListener(listener: LoadingStateListener) {
        loadingStateListeners.add(listener)
    }
    
    /**
     * Unregister a loading state listener
     */
    fun removeLoadingStateListener(listener: LoadingStateListener) {
        loadingStateListeners.remove(listener)
    }
    
    /**
     * Register an endpoint load listener
     */
    fun addEndpointLoadListener(listener: EndpointLoadListener) {
        endpointLoadListeners.add(listener)
    }
    
    /**
     * Unregister an endpoint load listener
     */
    fun removeEndpointLoadListener(listener: EndpointLoadListener) {
        endpointLoadListeners.remove(listener)
    }
    
    /**
     * Start automatic endpoint loading
     * Implements requirement 1.1: Auto-load endpoints on plugin startup
     * 
     * Note: This method checks if endpoints have already been discovered during
     * plugin startup to avoid duplicate scans and notifications.
     */
    fun startAutoLoad() {
        if (isLoading.get()) {
            logger.debug("Auto-load already in progress, skipping")
            return
        }
        
        // Get the endpoint discovery service
        val discoveryService = try {
            project.service<EndpointDiscoveryService>()
        } catch (e: Throwable) {
            logger.warn("Failed to get EndpointDiscoveryService", e)
            ErrorHandler.handleError(
                ErrorHandler.ErrorInfo(
                    message = "Failed to initialize endpoint discovery service",
                    details = e.message ?: "Unknown error",
                    cause = e,
                    severity = ErrorHandler.ErrorSeverity.WARNING,
                    recoveryActions = emptyList()
                ),
                project = project,
                showDialog = false
            )
            return
        }
        
        // Check if endpoints have already been discovered
        if (discoveryService.hasDiscoveredEndpoints() && !hasLoadedOnce.get()) {
            val cachedEndpoints = discoveryService.getCachedEndpoints()
            logger.info("Endpoints already discovered (${cachedEndpoints.size} endpoints), using cached data")
            
            // Mark as loaded
            hasLoadedOnce.set(true)
            
            // Notify UI components of existing endpoints without showing notification
            notifyEndpointsLoaded(cachedEndpoints)
            
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.DEBUG,
                "Using cached endpoints from previous discovery",
                mapOf(
                    "endpointCount" to cachedEndpoints.size,
                    "classCount" to cachedEndpoints.groupBy { it.className }.size
                )
            )
            
            return
        }
        
        logger.info("Starting automatic endpoint loading")
        
        // Check if IDE is in dumb mode (indexing)
        if (DumbService.isDumb(project)) {
            logger.info("IDE is in dumb mode, waiting for smart mode before loading endpoints")
            
            // Notify listeners that we're waiting
            notifyLoadingStarted(HttpPalBundle.message("endpoints.waiting.for.indexing"))
            
            // Wait for smart mode and then load
            DumbService.getInstance(project).runWhenSmart {
                logger.info("IDE is now in smart mode, starting endpoint loading")
                startAutoLoad() // Retry now that we're in smart mode
            }
            return
        }
        
        currentLoadJob = scope.launch {
            try {
                isLoading.set(true)
                
                // Notify listeners that loading has started
                // Implements requirement 1.3: Display loading indicator
                notifyLoadingStarted(HttpPalBundle.message("endpoints.loading"))
                
                // Discover endpoints in background
                val endpoints = withContext(Dispatchers.IO) {
                    discoveryService.discoverEndpoints()
                }
                
                // Mark as loaded
                hasLoadedOnce.set(true)
                
                // Notify listeners of successful load
                // Implements requirement 1.4: Update UI after load completion
                notifyEndpointsLoaded(endpoints)
                notifyLoadingCompleted()
                
                // Show success notification
                ErrorHandler.showSuccess(
                    HttpPalBundle.message("success.endpoints.refreshed"),
                    project,
                    HttpPalBundle.message("endpoints.found", endpoints.size, 
                        endpoints.groupBy { it.className }.size)
                )
                
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.INFO,
                    "Auto-load completed successfully",
                    mapOf(
                        "endpointCount" to endpoints.size,
                        "classCount" to endpoints.groupBy { it.className }.size
                    )
                )
                
            } catch (e: CancellationException) {
                logger.info("Auto-load was cancelled")
                notifyLoadingCompleted()
                throw e
            } catch (e: Exception) {
                // Implements requirement 1.5: Handle load failure
                handleLoadFailure(e)
            } finally {
                isLoading.set(false)
            }
        }
    }
    
    /**
     * Manually refresh endpoints
     * Implements requirement 1.5: Provide manual refresh option
     */
    fun manualRefresh() {
        logger.info("Manual refresh requested")
        
        // Cancel any ongoing load
        cancelCurrentLoad()
        
        // Start a new load
        startAutoLoad()
    }
    
    /**
     * Cancel the current loading operation
     */
    fun cancelCurrentLoad() {
        currentLoadJob?.cancel()
        currentLoadJob = null
        isLoading.set(false)
        notifyLoadingCompleted()
        
        logger.debug("Current load operation cancelled")
    }
    
    /**
     * Check if auto-load is currently in progress
     */
    fun isLoadingInProgress(): Boolean {
        return isLoading.get()
    }
    
    /**
     * Check if endpoints have been loaded at least once
     */
    fun hasLoadedEndpoints(): Boolean {
        return hasLoadedOnce.get()
    }
    
    /**
     * Handle load failure
     * Implements requirement 1.5: Display error message and provide manual refresh
     */
    private fun handleLoadFailure(error: Exception) {
        logger.error("Auto-load failed", error)
        
        val errorMessage = when (error) {
            is java.io.IOException -> "Failed to read project files. Please check file permissions."
            is IllegalStateException -> "Project is not properly initialized. Please try again."
            else -> "Failed to discover endpoints: ${error.message}"
        }
        
        // Notify listeners of failure
        notifyLoadingFailed(errorMessage)
        notifyEndpointsLoadFailed(error)
        
        // Show error notification with recovery action
        ApplicationManager.getApplication().invokeLater {
            ErrorHandler.handleError(
                ErrorHandler.ErrorInfo(
                    message = "Endpoint auto-load failed",
                    details = errorMessage,
                    cause = error,
                    severity = ErrorHandler.ErrorSeverity.ERROR,
                    recoveryActions = listOf(
                        ErrorHandler.RecoveryAction(
                            title = "Retry",
                            action = { manualRefresh() }
                        )
                    ),
                    userFriendlyMessage = "$errorMessage\n\nWould you like to retry?"
                ),
                project = project,
                showDialog = false // Only show IDE notification, not dialog
            )
        }
        
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.ERROR,
            "Auto-load failed",
            mapOf(
                "error" to error.javaClass.simpleName,
                "message" to (error.message ?: "Unknown error")
            )
        )
    }
    
    /**
     * Notify all listeners that loading has started
     */
    private fun notifyLoadingStarted(message: String) {
        ApplicationManager.getApplication().invokeLater {
            loadingStateListeners.forEach { listener ->
                try {
                    listener.onLoadingStarted(message)
                } catch (e: Exception) {
                    logger.error("Error notifying loading state listener", e)
                }
            }
        }
    }
    
    /**
     * Notify all listeners that loading has completed
     */
    private fun notifyLoadingCompleted() {
        ApplicationManager.getApplication().invokeLater {
            loadingStateListeners.forEach { listener ->
                try {
                    listener.onLoadingCompleted()
                } catch (e: Exception) {
                    logger.error("Error notifying loading state listener", e)
                }
            }
        }
    }
    
    /**
     * Notify all listeners that loading has failed
     */
    private fun notifyLoadingFailed(error: String) {
        ApplicationManager.getApplication().invokeLater {
            loadingStateListeners.forEach { listener ->
                try {
                    listener.onLoadingFailed(error)
                } catch (e: Exception) {
                    logger.error("Error notifying loading state listener", e)
                }
            }
        }
    }
    
    /**
     * Notify all listeners that endpoints have been loaded
     */
    private fun notifyEndpointsLoaded(endpoints: List<DiscoveredEndpoint>) {
        ApplicationManager.getApplication().invokeLater {
            endpointLoadListeners.forEach { listener ->
                try {
                    listener.onEndpointsLoaded(endpoints)
                } catch (e: Exception) {
                    logger.error("Error notifying endpoint load listener", e)
                }
            }
        }
    }
    
    /**
     * Notify all listeners that endpoint loading has failed
     */
    private fun notifyEndpointsLoadFailed(error: Throwable) {
        ApplicationManager.getApplication().invokeLater {
            endpointLoadListeners.forEach { listener ->
                try {
                    listener.onEndpointsLoadFailed(error)
                } catch (e: Exception) {
                    logger.error("Error notifying endpoint load listener", e)
                }
            }
        }
    }
    
    /**
     * Cleanup resources when the manager is disposed
     */
    fun dispose() {
        logger.debug("Disposing AutoLoadManager")
        
        // Cancel any ongoing operations
        cancelCurrentLoad()
        
        // Clear listeners
        loadingStateListeners.clear()
        endpointLoadListeners.clear()
        
        // Cancel the coroutine scope
        scope.cancel()
    }
    
    companion object {
        /**
         * Get the AutoLoadManager instance for a project
         */
        fun getInstance(project: Project): AutoLoadManager {
            return project.service<AutoLoadManager>()
        }
    }
}
