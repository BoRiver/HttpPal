package com.httppal.graphql.parser

import com.httppal.graphql.model.GraphQLSchema
import com.httppal.graphql.model.GraphQLType
import com.httppal.graphql.model.TypeKind

/**
 * Parses GraphQL query text to extract field selections.
 * Uses regex-based parsing for MVP - sufficient for basic queries.
 */
class GraphQLQueryParser(private val schema: GraphQLSchema) {

    /**
     * Parse a GraphQL query and extract field paths.
     *
     * @param queryText The GraphQL query string
     * @return Set of field paths (e.g., [["Query", "user", "name"], ["Query", "user", "email"]])
     */
    fun parse(queryText: String): Set<List<String>> {
        val fieldPaths = mutableSetOf<List<String>>()

        try {
            // Extract query operation
            val queryMatch = QUERY_PATTERN.find(queryText)
            if (queryMatch != null) {
                val selectionSet = queryMatch.groupValues[1]
                val queryType = schema.types.find { it.name == schema.queryType }
                if (queryType != null) {
                    parseSelectionSet(selectionSet, listOf("Query"), queryType, fieldPaths)
                }
            }

            // Extract mutation operation
            val mutationMatch = MUTATION_PATTERN.find(queryText)
            if (mutationMatch != null) {
                val selectionSet = mutationMatch.groupValues[1]
                val mutationType = schema.mutationType?.let { name ->
                    schema.types.find { it.name == name }
                }
                if (mutationType != null) {
                    parseSelectionSet(selectionSet, listOf("Mutation"), mutationType, fieldPaths)
                }
            }

            // Extract subscription operation
            val subscriptionMatch = SUBSCRIPTION_PATTERN.find(queryText)
            if (subscriptionMatch != null) {
                val selectionSet = subscriptionMatch.groupValues[1]
                val subscriptionType = schema.subscriptionType?.let { name ->
                    schema.types.find { it.name == name }
                }
                if (subscriptionType != null) {
                    parseSelectionSet(selectionSet, listOf("Subscription"), subscriptionType, fieldPaths)
                }
            }
        } catch (e: Exception) {
            // Parsing error - return empty set
            // In production, could log this or show error to user
        }

        return fieldPaths
    }

    /**
     * Parse a selection set recursively.
     *
     * @param selectionSet The selection set text (content between { })
     * @param parentPath The path to the parent field
     * @param parentType The GraphQL type of the parent
     * @param fieldPaths Output set to collect field paths
     */
    private fun parseSelectionSet(
        selectionSet: String,
        parentPath: List<String>,
        parentType: GraphQLType,
        fieldPaths: MutableSet<List<String>>
    ) {
        // Clean up the selection set
        val cleaned = selectionSet.trim()

        // Find all fields in this selection set
        var remaining = cleaned
        while (remaining.isNotEmpty()) {
            // Match field with optional nested selection
            val fieldMatch = FIELD_PATTERN.find(remaining) ?: break

            val fieldName = fieldMatch.groupValues[1]
            val nestedSelection = fieldMatch.groupValues[3] // Group 3 is the nested selection set

            // Get field definition from schema
            val fieldDef = parentType.fields?.find { it.name == fieldName }
            if (fieldDef != null) {
                val fieldPath = parentPath + fieldName
                fieldPaths.add(fieldPath)

                // If there's a nested selection, parse it recursively
                if (nestedSelection.isNotEmpty()) {
                    val unwrappedType = unwrapType(fieldDef.type)
                    val nestedType = schema.types.find { it.name == unwrappedType.name }
                    if (nestedType != null && nestedType.kind == TypeKind.OBJECT) {
                        parseSelectionSet(nestedSelection, fieldPath, nestedType, fieldPaths)
                    }
                }
            }

            // Move to next field
            remaining = remaining.substring(fieldMatch.range.last + 1).trim()
        }
    }

    /**
     * Unwrap GraphQL type to get the underlying object type.
     * Handles NonNull and List wrappers.
     */
    private fun unwrapType(type: GraphQLType): GraphQLType {
        return when (type.kind) {
            TypeKind.NON_NULL, TypeKind.LIST -> type.ofType?.let { unwrapType(it) } ?: type
            else -> type
        }
    }

    companion object {
        // Pattern to match query operation: query { ... }
        private val QUERY_PATTERN = Regex("""query\s*(?:\([^)]*\))?\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)

        // Pattern to match mutation operation: mutation { ... }
        private val MUTATION_PATTERN = Regex("""mutation\s*(?:\([^)]*\))?\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)

        // Pattern to match subscription operation: subscription { ... }
        private val SUBSCRIPTION_PATTERN = Regex("""subscription\s*(?:\([^)]*\))?\s*\{([^}]*(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL)

        // Pattern to match a field with optional arguments and nested selection
        // Matches: fieldName or fieldName(args) or fieldName { ... } or fieldName(args) { ... }
        private val FIELD_PATTERN = Regex("""(\w+)(?:\([^)]*\))?\s*(\{([^}]*(?:\{[^}]*\}[^}]*)*)\})?""")
    }
}
