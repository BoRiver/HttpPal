package com.httppal.graphql.builder

import com.httppal.graphql.model.GraphQLField
import com.httppal.graphql.model.GraphQLSchema
import com.httppal.graphql.state.FieldSelection
import com.httppal.graphql.state.GraphQLFieldSelectionState

/**
 * Builds formatted GraphQL queries from field selections.
 * Automatically includes parent fields and handles proper indentation.
 */
class GraphQLQueryBuilder(private val schema: GraphQLSchema) {

    /**
     * Generate a GraphQL query from the current selection state.
     *
     * @param selectionState The field selection state
     * @return Formatted GraphQL query string
     */
    fun generateQuery(selectionState: GraphQLFieldSelectionState): String {
        val selections = selectionState.getAllSelections()
        if (selections.isEmpty()) {
            return "query {\n  \n}"
        }

        // Group by root operation type
        val querySelections = selectionState.getSelectionsForRoot("Query")
        val mutationSelections = selectionState.getSelectionsForRoot("Mutation")
        val subscriptionSelections = selectionState.getSelectionsForRoot("Subscription")

        val lines = mutableListOf<String>()

        // Generate query operation
        if (querySelections.isNotEmpty()) {
            lines.add("query {")
            lines.addAll(generateRootFields(querySelections, selectionState, indent = 1))
            lines.add("}")
        }

        // Generate mutation operation
        if (mutationSelections.isNotEmpty()) {
            if (lines.isNotEmpty()) lines.add("")
            lines.add("mutation {")
            lines.addAll(generateRootFields(mutationSelections, selectionState, indent = 1))
            lines.add("}")
        }

        // Generate subscription operation
        if (subscriptionSelections.isNotEmpty()) {
            if (lines.isNotEmpty()) lines.add("")
            lines.add("subscription {")
            lines.addAll(generateRootFields(subscriptionSelections, selectionState, indent = 1))
            lines.add("}")
        }

        return lines.joinToString("\n")
    }

    /**
     * Generate root-level fields (direct children of Query/Mutation/Subscription).
     */
    private fun generateRootFields(
        rootSelections: Set<FieldSelection>,
        selectionState: GraphQLFieldSelectionState,
        indent: Int
    ): List<String> {
        // Get unique root field names (second element in path, e.g., "user" from ["Query", "user", "name"])
        val rootFieldNames = rootSelections
            .mapNotNull { it.fieldPath.getOrNull(1) }
            .distinct()
            .sorted()

        val lines = mutableListOf<String>()
        for (fieldName in rootFieldNames) {
            val fieldPath = rootSelections.first { it.fieldPath.getOrNull(1) == fieldName }.fieldPath.take(2)
            val field = rootSelections.first { it.fieldPath.getOrNull(1) == fieldName }.field

            lines.addAll(generateFieldSelection(fieldPath, field, selectionState, indent))
        }

        return lines
    }

    /**
     * Generate field selection recursively.
     */
    private fun generateFieldSelection(
        fieldPath: List<String>,
        field: GraphQLField,
        selectionState: GraphQLFieldSelectionState,
        indent: Int
    ): List<String> {
        val lines = mutableListOf<String>()
        val indentStr = "  ".repeat(indent)

        // Get child selections
        val childSelections = selectionState.getChildSelections(fieldPath)

        if (childSelections.isEmpty()) {
            // Leaf field - just add the field name
            lines.add("$indentStr${field.name}")
        } else {
            // Parent field - add field name with opening brace
            lines.add("$indentStr${field.name} {")

            // Sort children by field name for consistent output
            val sortedChildren = childSelections.sortedBy { it.field.name }

            // Add each child field
            for (childSelection in sortedChildren) {
                lines.addAll(
                    generateFieldSelection(
                        childSelection.fieldPath,
                        childSelection.field,
                        selectionState,
                        indent + 1
                    )
                )
            }

            // Add closing brace
            lines.add("$indentStr}")
        }

        return lines
    }

    /**
     * Format field arguments for display.
     * Example: (id: "123", limit: 10)
     */
    private fun formatArguments(arguments: Map<String, Any>): String {
        if (arguments.isEmpty()) return ""

        val argStrings = arguments.map { (name, value) ->
            val valueStr = when (value) {
                is String -> "\"$value\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> value.toString()
            }
            "$name: $valueStr"
        }

        return argStrings.joinToString(", ")
    }
}
