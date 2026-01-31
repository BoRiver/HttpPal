package com.httppal.graphql.service

import com.httppal.graphql.model.GraphQLSchema

/**
 * Service for GraphQL schema introspection and caching.
 */
interface GraphQLSchemaService {

    /**
     * Introspect the schema from a GraphQL endpoint.
     * This will query the endpoint using the standard GraphQL introspection query.
     *
     * @param endpoint The GraphQL endpoint URL
     * @param headers Optional headers to include in the introspection request
     * @return The introspected schema, or null if introspection failed
     */
    suspend fun introspectSchema(
        endpoint: String,
        headers: Map<String, String> = emptyMap()
    ): GraphQLSchema?

    /**
     * Get a cached schema for an endpoint (if available).
     *
     * @param endpoint The GraphQL endpoint URL
     * @return The cached schema, or null if not cached
     */
    fun getCachedSchema(endpoint: String): GraphQLSchema?

    /**
     * Clear the cached schema for an endpoint.
     *
     * @param endpoint The GraphQL endpoint URL
     */
    fun clearCache(endpoint: String)

    /**
     * Clear all cached schemas.
     */
    fun clearAllCaches()
}
