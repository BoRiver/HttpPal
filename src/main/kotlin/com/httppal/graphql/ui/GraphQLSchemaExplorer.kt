package com.httppal.graphql.ui

import com.httppal.graphql.builder.GraphQLQueryBuilder
import com.httppal.graphql.model.*
import com.httppal.graphql.parser.GraphQLQueryParser
import com.httppal.graphql.service.GraphQLSchemaService
import com.httppal.graphql.state.GraphQLFieldSelectionState
import com.httppal.util.HttpPalBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * GraphQL Schema Explorer with checkbox-based field selection (Postman-style).
 * Supports bidirectional sync between checkboxes and query editor.
 */
class GraphQLSchemaExplorer(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(GraphQLSchemaExplorer::class.java)
    private var checkboxTree: GraphQLCheckboxTree? = null
    private val rootNode = DefaultMutableTreeNode("Schema")
    private val treeModel = DefaultTreeModel(rootNode)

    private var currentEndpoint: String? = null
    private var currentSchema: GraphQLSchema? = null
    private val selectionState = GraphQLFieldSelectionState()
    private var queryBuilder: GraphQLQueryBuilder? = null
    private var queryParser: GraphQLQueryParser? = null

    // Callbacks
    private var onQueryUpdatedCallback: ((String) -> Unit)? = null

    // Sync flags to prevent loops
    private var isSyncingFromCheckboxes = false
    private var isSyncingFromEditor = false

    init {
        // Layout
        border = JBUI.Borders.empty(5)

        // Title label
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        // Show initial state
        showEmptyState()

        // Listen to selection changes
        selectionState.addListener { selections ->
            if (!isSyncingFromEditor) {
                regenerateQuery()
            }
        }
    }

    /**
     * Load schema for the specified endpoint.
     */
    fun loadSchema(endpoint: String) {
        this.currentEndpoint = endpoint

        val schemaService = service<GraphQLSchemaService>()

        // Try to get from cache first
        val cachedSchema = schemaService.getCachedSchema(endpoint)
        if (cachedSchema != null) {
            displaySchema(cachedSchema)
            return
        }

        // Show loading state
        showLoadingState()

        // Fetch schema in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schema = schemaService.introspectSchema(endpoint)

                SwingUtilities.invokeLater {
                    if (schema != null) {
                        displaySchema(schema)
                    } else {
                        showErrorState()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to load schema", e)
                SwingUtilities.invokeLater {
                    showErrorState()
                }
            }
        }
    }

    /**
     * Display schema content with checkbox tree.
     */
    private fun displaySchema(schema: GraphQLSchema) {
        this.currentSchema = schema
        this.queryBuilder = GraphQLQueryBuilder(schema)
        this.queryParser = GraphQLQueryParser(schema)

        // Create root node for checkbox tree
        val checkboxRoot = CheckboxTreeNode(
            field = GraphQLField(
                name = "Schema (${schema.types.size} types)",
                type = GraphQLType(name = "Schema", kind = TypeKind.OBJECT)
            ),
            fieldPath = emptyList()
        )

        // Add Query type
        val queryType = schema.types.find { it.name == schema.queryType }
        if (queryType != null) {
            val queryNode = createOperationNode("Query", queryType, listOf("Query"))
            checkboxRoot.add(queryNode)
        }

        // Add Mutation type
        if (schema.mutationType != null) {
            val mutationType = schema.types.find { it.name == schema.mutationType }
            if (mutationType != null) {
                val mutationNode = createOperationNode("Mutation", mutationType, listOf("Mutation"))
                checkboxRoot.add(mutationNode)
            }
        }

        // Add Subscription type
        if (schema.subscriptionType != null) {
            val subscriptionType = schema.types.find { it.name == schema.subscriptionType }
            if (subscriptionType != null) {
                val subscriptionNode = createOperationNode("Subscription", subscriptionType, listOf("Subscription"))
                checkboxRoot.add(subscriptionNode)
            }
        }

        // Create checkbox tree
        val newTree = GraphQLCheckboxTree(checkboxRoot)
        newTree.addCheckboxChangeListener { node, newState ->
            handleCheckboxChange(node, newState)
        }

        // Replace old tree
        removeAll()
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(newTree)
        add(scrollPane, BorderLayout.CENTER)

        this.checkboxTree = newTree

        // Expand root and Query nodes
        newTree.expandRow(0)
        if (checkboxRoot.childCount > 0) {
            newTree.expandRow(1)
        }

        revalidate()
        repaint()
    }

    /**
     * Create an operation node (Query/Mutation/Subscription).
     */
    private fun createOperationNode(
        operationName: String,
        operationType: GraphQLType,
        path: List<String>
    ): CheckboxTreeNode {
        val operationNode = CheckboxTreeNode(
            field = GraphQLField(
                name = operationName,
                type = operationType
            ),
            fieldPath = path
        )

        // Add fields
        operationType.fields?.forEach { field ->
            val fieldNode = createFieldNode(field, path + field.name, operationType)
            operationNode.add(fieldNode)
        }

        return operationNode
    }

    /**
     * Create a field node recursively.
     */
    private fun createFieldNode(
        field: GraphQLField,
        fieldPath: List<String>,
        parentType: GraphQLType
    ): CheckboxTreeNode {
        val node = CheckboxTreeNode(
            field = field,
            fieldPath = fieldPath
        )

        // Add child fields if this is an object type
        val unwrappedType = unwrapType(field.type)
        val fieldType = currentSchema?.types?.find { it.name == unwrappedType.name }
        if (fieldType != null && fieldType.kind == TypeKind.OBJECT) {
            fieldType.fields?.forEach { childField ->
                val childNode = createFieldNode(childField, fieldPath + childField.name, fieldType)
                node.add(childNode)
            }
        }

        return node
    }

    /**
     * Unwrap type to get the underlying type.
     */
    private fun unwrapType(type: GraphQLType): GraphQLType {
        return when (type.kind) {
            TypeKind.NON_NULL, TypeKind.LIST -> type.ofType?.let { unwrapType(it) } ?: type
            else -> type
        }
    }

    /**
     * Handle checkbox state change.
     */
    private fun handleCheckboxChange(node: CheckboxTreeNode, newState: CheckboxState) {
        if (isSyncingFromEditor) return

        isSyncingFromCheckboxes = true

        try {
            when (newState) {
                CheckboxState.CHECKED -> {
                    // Add this node and all descendants to selection
                    addNodeToSelection(node)
                    node.getAllDescendants().forEach { addNodeToSelection(it) }
                }
                CheckboxState.UNCHECKED -> {
                    // Remove this node and all descendants from selection
                    selectionState.removeSelectionsWithPrefix(node.fieldPath)
                }
                CheckboxState.PARTIAL -> {
                    // Partial state is calculated, not set directly
                }
            }
        } finally {
            isSyncingFromCheckboxes = false
        }
    }

    /**
     * Add a node to the selection state.
     */
    private fun addNodeToSelection(node: CheckboxTreeNode) {
        if (node.fieldPath.isNotEmpty()) {
            selectionState.addSelection(node.fieldPath, node.field)
        }
    }

    /**
     * Regenerate query from current selections.
     */
    private fun regenerateQuery() {
        val builder = queryBuilder ?: return
        val query = builder.generateQuery(selectionState)
        onQueryUpdatedCallback?.invoke(query)
    }

    /**
     * Sync checkbox states from query text.
     * Called when the query editor is modified.
     */
    fun syncFromQuery(queryText: String) {
        if (isSyncingFromCheckboxes) return

        isSyncingFromEditor = true

        try {
            val parser = queryParser ?: return
            val tree = checkboxTree ?: return

            // Parse query to get field paths
            val fieldPaths = parser.parse(queryText)

            // Clear current selections
            selectionState.clear()

            // Update selections and checkbox states
            for (fieldPath in fieldPaths) {
                // Find the node
                val node = tree.findNodeByPath(fieldPath)
                if (node != null) {
                    // Add to selection
                    selectionState.addSelection(fieldPath, node.field)

                    // Update checkbox state
                    tree.setCheckboxStateSilently(node, CheckboxState.CHECKED)
                }
            }
        } finally {
            isSyncingFromEditor = false
        }
    }

    /**
     * Show empty state.
     */
    private fun showEmptyState() {
        removeAll()
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        val emptyLabel = JBLabel(HttpPalBundle.message("graphql.schema.no.schema"))
        emptyLabel.border = JBUI.Borders.empty(10)
        add(emptyLabel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    /**
     * Show loading state.
     */
    private fun showLoadingState() {
        removeAll()
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        val loadingLabel = JBLabel(HttpPalBundle.message("graphql.schema.loading"))
        loadingLabel.border = JBUI.Borders.empty(10)
        add(loadingLabel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    /**
     * Show error state.
     */
    private fun showErrorState() {
        removeAll()
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        val errorLabel = JBLabel(HttpPalBundle.message("graphql.introspect.failed"))
        errorLabel.border = JBUI.Borders.empty(10)
        add(errorLabel, BorderLayout.CENTER)

        revalidate()
        repaint()
    }

    /**
     * Clear schema.
     */
    fun clear() {
        currentEndpoint = null
        currentSchema = null
        selectionState.clear()
        showEmptyState()
    }

    /**
     * Set callback for query updates (checkbox → query editor).
     */
    fun setOnQueryUpdatedCallback(callback: (String) -> Unit) {
        this.onQueryUpdatedCallback = callback
    }
}
