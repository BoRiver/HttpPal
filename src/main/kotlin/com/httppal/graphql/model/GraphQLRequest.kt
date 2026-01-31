package com.httppal.graphql.model

/**
 * Represents a GraphQL request with query, variables, and optional operation name.
 *
 * @property query The GraphQL query or mutation string
 * @property variables Optional variables for the query (JSON object)
 * @property operationName Optional operation name when query contains multiple operations
 */
data class GraphQLRequest(
    val query: String,
    val variables: Map<String, Any>? = null,
    val operationName: String? = null
)
