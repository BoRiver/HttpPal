package com.httppal.graphql.model

import com.intellij.psi.PsiElement

/**
 * Represents a discovered GraphQL endpoint in the project.
 *
 * @property url The endpoint URL
 * @property operationType The type of operation (QUERY, MUTATION, SUBSCRIPTION)
 * @property operationName The name of the operation
 * @property description Optional description
 * @property psiElement The PSI element where this endpoint was discovered
 * @property sourceFile The file containing the endpoint definition
 */
data class GraphQLEndpoint(
    val url: String,
    val operationType: GraphQLOperationType,
    val operationName: String,
    val description: String? = null,
    val psiElement: PsiElement? = null,
    val sourceFile: String? = null
)

/**
 * Enum representing GraphQL operation types.
 */
enum class GraphQLOperationType {
    QUERY,
    MUTATION,
    SUBSCRIPTION
}
