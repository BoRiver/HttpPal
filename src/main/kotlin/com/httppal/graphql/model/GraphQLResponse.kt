package com.httppal.graphql.model

/**
 * Represents a GraphQL response containing data, errors, and optional extensions.
 *
 * @property data The response data (can be null if errors occurred)
 * @property errors Optional list of errors that occurred during execution
 * @property extensions Optional extensions provided by the server
 */
data class GraphQLResponse(
    val data: Any?,
    val errors: List<GraphQLError>? = null,
    val extensions: Map<String, Any>? = null
) {
    /**
     * Returns true if the response has no errors.
     */
    fun isSuccessful(): Boolean = errors.isNullOrEmpty()

    /**
     * Returns true if the response has errors.
     */
    fun hasErrors(): Boolean = !errors.isNullOrEmpty()
}
