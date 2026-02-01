package com.httppal.graphql.state

import com.httppal.graphql.model.GraphQLField
import com.httppal.graphql.ui.CheckboxState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the state of selected GraphQL fields in the schema explorer.
 * Thread-safe implementation using concurrent collections.
 */
class GraphQLFieldSelectionState {
    private val selections = mutableSetOf<FieldSelection>()
    private val listeners = CopyOnWriteArrayList<(Set<FieldSelection>) -> Unit>()

    /**
     * Add a field selection.
     *
     * @param path The field path from root (e.g., ["Query", "user", "name"])
     * @param field The GraphQL field definition
     * @param arguments Optional field arguments
     */
    @Synchronized
    fun addSelection(path: List<String>, field: GraphQLField, arguments: Map<String, Any>? = null) {
        val selection = FieldSelection(path, field, arguments)
        if (selections.add(selection)) {
            notifyListeners()
        }
    }

    /**
     * Remove a field selection.
     *
     * @param path The field path to remove
     */
    @Synchronized
    fun removeSelection(path: List<String>) {
        val removed = selections.removeIf { it.fieldPath == path }
        if (removed) {
            notifyListeners()
        }
    }

    /**
     * Remove all selections that start with the given path prefix.
     * Used when unchecking a parent node to remove all its children.
     *
     * @param pathPrefix The path prefix to match
     */
    @Synchronized
    fun removeSelectionsWithPrefix(pathPrefix: List<String>) {
        val removed = selections.removeIf { selection ->
            selection.fieldPath.size >= pathPrefix.size &&
                    selection.fieldPath.subList(0, pathPrefix.size) == pathPrefix
        }
        if (removed) {
            notifyListeners()
        }
    }

    /**
     * Check if a field path is selected.
     *
     * @param path The field path to check
     * @return true if the path is selected
     */
    @Synchronized
    fun isSelected(path: List<String>): Boolean {
        return selections.any { it.fieldPath == path }
    }

    /**
     * Get the checkbox state for a given path.
     * - CHECKED: This path is selected
     * - UNCHECKED: This path is not selected and no children are selected
     * - PARTIAL: This path is not selected but some children are selected
     *
     * @param path The field path to check
     * @return The checkbox state
     */
    @Synchronized
    fun getSelectionState(path: List<String>): CheckboxState {
        // Check if this exact path is selected
        if (isSelected(path)) {
            return CheckboxState.CHECKED
        }

        // Check if any children are selected
        val hasSelectedChildren = selections.any { selection ->
            selection.fieldPath.size > path.size &&
                    selection.fieldPath.subList(0, path.size) == path
        }

        return if (hasSelectedChildren) CheckboxState.PARTIAL else CheckboxState.UNCHECKED
    }

    /**
     * Get all current selections.
     *
     * @return Immutable copy of selections
     */
    @Synchronized
    fun getAllSelections(): Set<FieldSelection> {
        return selections.toSet()
    }

    /**
     * Get selections for a specific root operation (Query, Mutation, Subscription).
     *
     * @param rootType The root operation type name
     * @return Selections under this root type
     */
    @Synchronized
    fun getSelectionsForRoot(rootType: String): Set<FieldSelection> {
        return selections.filter { it.fieldPath.firstOrNull() == rootType }.toSet()
    }

    /**
     * Get child selections for a given parent path.
     *
     * @param parentPath The parent field path
     * @return Direct child selections
     */
    @Synchronized
    fun getChildSelections(parentPath: List<String>): Set<FieldSelection> {
        return selections.filter { selection ->
            selection.fieldPath.size == parentPath.size + 1 &&
                    selection.fieldPath.subList(0, parentPath.size) == parentPath
        }.toSet()
    }

    /**
     * Clear all selections.
     */
    @Synchronized
    fun clear() {
        if (selections.isNotEmpty()) {
            selections.clear()
            notifyListeners()
        }
    }

    /**
     * Add a listener for selection changes.
     *
     * @param listener Callback invoked when selections change
     */
    fun addListener(listener: (Set<FieldSelection>) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a listener.
     *
     * @param listener The listener to remove
     */
    fun removeListener(listener: (Set<FieldSelection>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = selections.toSet()
        listeners.forEach { it(snapshot) }
    }
}

/**
 * Represents a selected GraphQL field with its path and optional arguments.
 *
 * @property fieldPath The path from root to this field (e.g., ["Query", "user", "name"])
 * @property field The GraphQL field definition
 * @property arguments Optional field arguments
 */
data class FieldSelection(
    val fieldPath: List<String>,
    val field: GraphQLField,
    val arguments: Map<String, Any>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FieldSelection) return false
        return fieldPath == other.fieldPath
    }

    override fun hashCode(): Int {
        return fieldPath.hashCode()
    }
}
