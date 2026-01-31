package com.httppal.graphql.model

/**
 * Represents a GraphQL error with message, location, path, and extensions.
 *
 * @property message The error message
 * @property locations Optional list of locations in the query where the error occurred
 * @property path Optional path to the field that caused the error
 * @property extensions Optional additional error information
 */
data class GraphQLError(
    val message: String,
    val locations: List<ErrorLocation>? = null,
    val path: List<Any>? = null,
    val extensions: Map<String, Any>? = null
)

/**
 * Represents a location in the GraphQL query source.
 *
 * @property line The line number (1-indexed)
 * @property column The column number (1-indexed)
 */
data class ErrorLocation(
    val line: Int,
    val column: Int
)
