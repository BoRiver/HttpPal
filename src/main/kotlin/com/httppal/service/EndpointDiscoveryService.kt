package com.httppal.service

import com.httppal.model.DiscoveredEndpoint
import com.intellij.psi.PsiFile

/**
 * Service for discovering API endpoints from source code
 * Uses PSI to analyze Java files for Spring MVC and JAX-RS annotations
 */
interface EndpointDiscoveryService {
    
    /**
     * Discover all endpoints in the current project
     */
    fun discoverEndpoints(): List<DiscoveredEndpoint>
    
    /**
     * Refresh endpoint discovery (re-scan all files)
     */
    fun refreshEndpoints()
    
    /**
     * Notify all registered listeners that endpoints have changed.
     * This method does NOT trigger a new scan - it only notifies listeners
     * of the current endpoint state.
     * 
     * Use this method when you want to notify UI components of endpoint changes
     * without performing a full rescan (e.g., after initial discovery during startup).
     * 
     * @param endpoints The current list of discovered endpoints to notify listeners about
     */
    fun notifyEndpointsChanged(endpoints: List<DiscoveredEndpoint>)
    
    /**
     * Add listener for endpoint changes
     */
    fun addEndpointChangeListener(listener: (List<DiscoveredEndpoint>) -> Unit)
    
    /**
     * Remove endpoint change listener
     */
    fun removeEndpointChangeListener(listener: (List<DiscoveredEndpoint>) -> Unit)
    
    /**
     * Register an endpoint change listener
     * The listener will be notified when endpoints are added, modified, or removed
     * 
     * @param listener The listener to register
     */
    fun registerEndpointChangeListener(listener: EndpointChangeListener)
    
    /**
     * Unregister an endpoint change listener
     * 
     * @param listener The listener to unregister
     */
    fun unregisterEndpointChangeListener(listener: EndpointChangeListener)
    
    /**
     * Get endpoints discovered in a specific file
     */
    fun getEndpointsForFile(file: PsiFile): List<DiscoveredEndpoint>
    
    /**
     * Check if a file contains discoverable endpoints
     */
    fun hasEndpoints(file: PsiFile): Boolean
    
    /**
     * Get endpoints grouped by controller/resource class
     */
    fun getEndpointsByClass(): Map<String, List<DiscoveredEndpoint>>
    
    /**
     * Scan a single file for endpoints and update the cache
     * This method supports incremental scanning for file change events
     * 
     * @param file The PSI file to scan
     * @return List of endpoints discovered in the file
     */
    fun scanFile(file: PsiFile): List<DiscoveredEndpoint>
    
    /**
     * Check if endpoints have been discovered at least once
     * This method does not trigger a discovery operation
     * 
     * @return true if initial discovery has completed, false otherwise
     */
    fun hasDiscoveredEndpoints(): Boolean
    
    /**
     * Get cached endpoints without triggering a new discovery
     * Returns empty list if no discovery has been performed yet
     * 
     * @return List of cached endpoints
     */
    fun getCachedEndpoints(): List<DiscoveredEndpoint>
}