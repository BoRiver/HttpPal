package com.httppal.graphql.ui

import com.httppal.graphql.model.TypeKind
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer

/**
 * Custom tree component with checkbox support for GraphQL field selection.
 * Implements Postman-style checkbox interaction.
 */
class GraphQLCheckboxTree(root: CheckboxTreeNode) : JTree(DefaultTreeModel(root)) {

    private val checkboxChangeListeners = mutableListOf<(CheckboxTreeNode, CheckboxState) -> Unit>()

    init {
        // Use custom renderer
        cellRenderer = GraphQLCheckboxTreeCellRenderer()

        // Handle checkbox clicks
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val path = getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? CheckboxTreeNode ?: return

                // Check if click is in checkbox area (first 20 pixels)
                if (e.x < 20) {
                    toggleCheckbox(node)
                    e.consume()
                }
            }
        })

        // Tree settings
        isRootVisible = true
        showsRootHandles = true
        border = JBUI.Borders.empty()
    }

    /**
     * Toggle checkbox state for a node.
     */
    private fun toggleCheckbox(node: CheckboxTreeNode) {
        val newState = when (node.checkboxState) {
            CheckboxState.CHECKED -> CheckboxState.UNCHECKED
            CheckboxState.UNCHECKED -> CheckboxState.CHECKED
            CheckboxState.PARTIAL -> CheckboxState.CHECKED
        }

        updateNodeState(node, newState)
        notifyCheckboxChange(node, newState)

        // Repaint the tree
        repaint()
    }

    /**
     * Update node state and propagate to children and ancestors.
     */
    private fun updateNodeState(node: CheckboxTreeNode, newState: CheckboxState) {
        node.checkboxState = newState

        // Propagate down to all descendants
        if (newState == CheckboxState.CHECKED || newState == CheckboxState.UNCHECKED) {
            node.getAllDescendants().forEach { it.checkboxState = newState }
        }

        // Propagate up to ancestors
        updateAncestorStates(node)
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

            current.checkboxState = when {
                allChecked -> CheckboxState.CHECKED
                allUnchecked -> CheckboxState.UNCHECKED
                else -> CheckboxState.PARTIAL
            }

            current = current.parent as? CheckboxTreeNode
        }
    }

    /**
     * Set checkbox state for a node without triggering listeners.
     * Used for syncing from query editor.
     */
    fun setCheckboxStateSilently(node: CheckboxTreeNode, state: CheckboxState) {
        updateNodeState(node, state)
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

        // Check if current node matches
        if (node.fieldPath.size == depth + 1 && node.fieldPath.last() == path[depth]) {
            // If this is the last element in path, we found it
            if (depth == path.size - 1) {
                return node
            }
            // Otherwise, search in children
            for (child in node.getCheckboxChildren()) {
                val found = findNodeRecursive(child, path, depth + 1)
                if (found != null) return found
            }
        }

        // Search in all children
        for (child in node.getCheckboxChildren()) {
            val found = findNodeRecursive(child, path, depth)
            if (found != null) return found
        }

        return null
    }
}

/**
 * Custom cell renderer for checkbox tree.
 */
private class GraphQLCheckboxTreeCellRenderer : TreeCellRenderer {

    private val checkboxRenderer = CheckboxTree.CheckboxTreeCellRenderer()

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val node = value as? CheckboxTreeNode

        if (node != null) {
            // Configure checkbox state
            val checked = when (node.checkboxState) {
                CheckboxState.CHECKED -> true
                CheckboxState.UNCHECKED -> false
                CheckboxState.PARTIAL -> false // Will show as indeterminate
            }

            // Use CheckedTreeNode for rendering
            val checkedNode = CheckedTreeNode(node.field.name)
            checkedNode.isChecked = checked

            val component = checkboxRenderer.getTreeCellRendererComponent(
                tree, checkedNode, selected, expanded, leaf, row, hasFocus
            )

            // Customize text based on field info
            val field = node.field
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

            checkboxRenderer.textRenderer.clear()
            checkboxRenderer.textRenderer.append(
                text,
                if (field.isDeprecated) SimpleTextAttributes.GRAYED_ATTRIBUTES
                else SimpleTextAttributes.REGULAR_ATTRIBUTES
            )

            return component
        }

        // Fallback for non-checkbox nodes
        return checkboxRenderer.getTreeCellRendererComponent(
            tree, value, selected, expanded, leaf, row, hasFocus
        )
    }

    private fun getTypeName(type: com.httppal.graphql.model.GraphQLType): String {
        return when (type.kind) {
            TypeKind.NON_NULL -> "${getTypeName(type.ofType!!)}!"
            TypeKind.LIST -> "[${getTypeName(type.ofType!!)}]"
            else -> type.name
        }
    }
}
