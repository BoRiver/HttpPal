package com.httppal.graphql.ui

import com.httppal.graphql.model.GraphQLField
import com.intellij.ui.CheckedTreeNode

/**
 * Tree node that supports checkbox state for GraphQL field selection.
 *
 * @property field The GraphQL field definition
 * @property fieldPath The path from root to this field (e.g., ["Query", "user", "name"])
 * @property checkboxState Current checkbox state (CHECKED, UNCHECKED, PARTIAL)
 */
class CheckboxTreeNode(
    val field: GraphQLField,
    val fieldPath: List<String>,
    initialCheckboxState: CheckboxState = CheckboxState.UNCHECKED
) : CheckedTreeNode() {

    var checkboxState: CheckboxState = initialCheckboxState
        set(value) {
            field = value
            // Sync with CheckedTreeNode's isChecked property
            isChecked = value == CheckboxState.CHECKED
        }

    init {
        userObject = field.name
        isChecked = initialCheckboxState == CheckboxState.CHECKED
    }

    /**
     * Get all descendant nodes recursively.
     */
    fun getAllDescendants(): List<CheckboxTreeNode> {
        val descendants = mutableListOf<CheckboxTreeNode>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? CheckboxTreeNode ?: continue
            descendants.add(child)
            descendants.addAll(child.getAllDescendants())
        }
        return descendants
    }

    /**
     * Get all ancestor nodes up to root.
     */
    fun getAllAncestors(): List<CheckboxTreeNode> {
        val ancestors = mutableListOf<CheckboxTreeNode>()
        var current = parent as? CheckboxTreeNode
        while (current != null) {
            ancestors.add(current)
            current = current.parent as? CheckboxTreeNode
        }
        return ancestors
    }

    /**
     * Get direct children as CheckboxTreeNode list.
     */
    fun getCheckboxChildren(): List<CheckboxTreeNode> {
        return (0 until childCount).mapNotNull { getChildAt(it) as? CheckboxTreeNode }
    }

    override fun toString(): String = field.name
}

/**
 * Checkbox state for tree nodes.
 */
enum class CheckboxState {
    /**
     * All child nodes are checked (or this is a leaf node that is checked).
     */
    CHECKED,

    /**
     * All child nodes are unchecked (or this is a leaf node that is unchecked).
     */
    UNCHECKED,

    /**
     * Some but not all child nodes are checked (only applicable to parent nodes).
     */
    PARTIAL
}
