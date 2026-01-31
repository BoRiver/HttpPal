package com.httppal.graphql.service

import com.httppal.graphql.model.GraphQLRequest
import com.httppal.graphql.model.GraphQLResponse

/**
 * Service for executing GraphQL requests.
 */
interface GraphQLExecutionService {

    /**
     * Execute a GraphQL query or mutation.
     *
     * @param endpoint The GraphQL endpoint URL
     * @param graphqlRequest The GraphQL request (query, variables, operationName)
     * @param additionalHeaders Additional HTTP headers to include in the request
     * @return The GraphQL response
     */
    suspend fun executeGraphQL(
        endpoint: String,
        graphqlRequest: GraphQLRequest,
        additionalHeaders: Map<String, String> = emptyMap()
    ): GraphQLResponse
}
