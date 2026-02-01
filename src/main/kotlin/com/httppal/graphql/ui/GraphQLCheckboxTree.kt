package com.httppal.graphql.ui

import com.httppal.graphql.model.TypeKind
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Custom tree component with checkbox support for GraphQL field selection.
 * Implements Postman-style checkbox interaction.
 */
class GraphQLCheckboxTree(root: CheckboxTreeNode) : CheckboxTree(
    GraphQLCheckboxTreeCellRenderer(),
    root
) {

    private val checkboxChangeListeners = mutableListOf<(CheckboxTreeNode, CheckboxState) -> Unit>()

    init {
        // Tree settings
        isRootVisible = true
        showsRootHandles = true
        border = JBUI.Borders.empty()
    }

    /**
     * Override to handle checkbox state changes.
     * This is called by CheckboxTree when a checkbox is clicked.
     */
    override fun onNodeStateChanged(node: CheckedTreeNode) {
        super.onNodeStateChanged(node)

        if (node is CheckboxTreeNode) {
            // Determine new state based on isChecked
            val newState = if (node.isChecked) CheckboxState.CHECKED else CheckboxState.UNCHECKED

            // Update our custom state
            updateNodeState(node, newState)

            // Notify listeners
            notifyCheckboxChange(node, newState)
        }
    }

    /**
     * Update node state and propagate to children and ancestors.
     */
    private fun updateNodeState(node: CheckboxTreeNode, newState: CheckboxState) {
        // Update this node's state
        node.checkboxState = newState

        // Propagate down to all descendants
        if (newState == CheckboxState.CHECKED || newState == CheckboxState.UNCHECKED) {
            node.getAllDescendants().forEach { descendant ->
                descendant.checkboxState = newState
                descendant.isChecked = (newState == CheckboxState.CHECKED)
            }
        }

        // Propagate up to ancestors
        updateAncestorStates(node)

        // Repaint the tree
        repaint()
    }

    /**
     * Update ancestor states based on children states.
     */
    private fun updateAncestorStates(node: CheckboxTreeNode) {
        var current = node.parent as? CheckboxTreeNode
        while (current != null) {
            val children = current.getCheckboxChildren()
            if (children.isEmpty()) {
                current = current.parent as? CheckboxTreeNode
                continue
            }

            val allChecked = children.all { it.checkboxState == CheckboxState.CHECKED }
            val allUnchecked = children.all { it.checkboxState == CheckboxState.UNCHECKED }

            val newState = when {
                allChecked -> CheckboxState.CHECKED
                allUnchecked -> CheckboxState.UNCHECKED
                else -> CheckboxState.PARTIAL
            }

            current.checkboxState = newState
            // For CheckboxTree, set isChecked based on state
            current.isChecked = (newState == CheckboxState.CHECKED)

            current = current.parent as? CheckboxTreeNode
        }
    }

    /**
     * Set checkbox state for a node without triggering listeners.
     * Used for syncing from query editor.
     */
    fun setCheckboxStateSilently(node: CheckboxTreeNode, state: CheckboxState) {
        node.checkboxState = state
        node.isChecked = (state == CheckboxState.CHECKED)

        // Also update descendants
        node.getAllDescendants().forEach { descendant ->
            descendant.checkboxState = state
            descendant.isChecked = (state == CheckboxState.CHECKED)
        }

        // Update ancestors
        updateAncestorStates(node)

        // Repaint
        repaint()
    }

    /**
     * Add a listener for checkbox state changes.
     */
    fun addCheckboxChangeListener(listener: (CheckboxTreeNode, CheckboxState) -> Unit) {
        checkboxChangeListeners.add(listener)
    }

    /**
     * Remove a checkbox change listener.
     */
    fun removeCheckboxChangeListener(listener: (CheckboxTreeNode, CheckboxState) -> Unit) {
        checkboxChangeListeners.remove(listener)
    }

    private fun notifyCheckboxChange(node: CheckboxTreeNode, newState: CheckboxState) {
        checkboxChangeListeners.forEach { it(node, newState) }
    }

    /**
     * Find node by field path.
     */
    fun findNodeByPath(path: List<String>): CheckboxTreeNode? {
        val root = model.root as? CheckboxTreeNode ?: return null
        return findNodeRecursive(root, path, 0)
    }

    private fun findNodeRecursive(node: CheckboxTreeNode, path: List<String>, depth: Int): CheckboxTreeNode? {
        if (depth >= path.size) return null

        // Check if current node matches the path at this depth
        val targetName = path[depth]

        // For root level (Query, Mutation, Subscription), match by name
        if (depth == 0) {
            if (node.field.name == targetName) {
                if (depth == path.size - 1) {
                    return node
                }
                // Search in children for next level
                for (child in node.getCheckboxChildren()) {
                    val found = findNodeRecursive(child, path, depth + 1)
                    if (found != null) return found
                }
            }
            // Also search in children at same depth
            for (child in node.getCheckboxChildren()) {
                val found = findNodeRecursive(child, path, depth)
                if (found != null) return found
            }
        } else {
            // For other levels, match by field name
            if (node.field.name == targetName) {
                if (depth == path.size - 1) {
                    return node
                }
                // Search in children for next level
                for (child in node.getCheckboxChildren()) {
                    val found = findNodeRecursive(child, path, depth + 1)
                    if (found != null) return found
                }
            }
            // Search in children at same depth
            for (child in node.getCheckboxChildren()) {
                val found = findNodeRecursive(child, path, depth)
                if (found != null) return found
            }
        }

        return null
    }

    /**
     * Expand all nodes in the tree.
     */
    fun expandAll() {
        var row = 0
        while (row < rowCount) {
            expandRow(row)
            row++
        }
    }

    /**
     * Collapse all nodes except root.
     */
    fun collapseAll() {
        var row = rowCount - 1
        while (row > 0) {
            collapseRow(row)
            row--
        }
    }
}

/**
 * Custom cell renderer for GraphQL checkbox tree.
 */
private class GraphQLCheckboxTreeCellRenderer : CheckboxTree.CheckboxTreeCellRenderer() {

    override fun customizeRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? CheckboxTreeNode

        if (node != null) {
            val field = node.field

            // Build display text
            val text = buildString {
                append(field.name)

                // Add return type
                append(": ")
                append(getTypeName(field.type))

                // Mark deprecated
                if (field.isDeprecated) {
                    append(" [deprecated]")
                }
            }

            // Set text with appropriate attributes
            textRenderer.append(
                text,
                if (field.isDeprecated) SimpleTextAttributes.GRAYED_ATTRIBUTES
                else SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        } else {
            // Fallback for non-CheckboxTreeNode
            textRenderer.append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    private fun getTypeName(type: com.httppal.graphql.model.GraphQLType): String {
        return when (type.kind) {
            TypeKind.NON_NULL -> "${getTypeName(type.ofType!!)}!"
            TypeKind.LIST -> "[${getTypeName(type.ofType!!)}]"
            else -> type.name
        }
    }
}
