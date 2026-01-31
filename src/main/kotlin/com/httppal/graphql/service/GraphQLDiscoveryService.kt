package com.httppal.graphql.service

import com.httppal.graphql.model.GraphQLEndpoint

/**
 * Service for discovering GraphQL endpoints from project source code.
 */
interface GraphQLDiscoveryService {

    /**
     * Discover all GraphQL endpoints in the project.
     * This will scan for Spring GraphQL and Netflix DGS annotations.
     *
     * @return List of discovered GraphQL endpoints
     */
    fun discoverGraphQLEndpoints(): List<GraphQLEndpoint>

    /**
     * Refresh the discovered endpoints.
     */
    fun refreshEndpoints()

    /**
     * Add a listener for endpoint changes.
     *
     * @param listener Callback to be invoked when endpoints change
     */
    fun addEndpointChangeListener(listener: (List<GraphQLEndpoint>) -> Unit)

    /**
     * Remove an endpoint change listener.
     */
    fun removeEndpointChangeListener(listener: (List<GraphQLEndpoint>) -> Unit)
}
