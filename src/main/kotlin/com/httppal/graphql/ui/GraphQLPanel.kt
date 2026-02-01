package com.httppal.graphql.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.httppal.graphql.model.GraphQLRequest
import com.httppal.graphql.model.GraphQLResponse
import com.httppal.graphql.service.GraphQLExecutionService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Main GraphQL panel containing endpoint input, query editor, variables editor,
 * and execute button.
 */
class GraphQLPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(GraphQLPanel::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    // UI Components
    private val endpointField = JTextField("https://countries.trevorblades.com/")
    private val executeButton = JButton("执行查询")
    private val introspectButton = JButton("获取 Schema")
    private val generateSampleButton = JButton("生成示例")
    private val queryEditor = GraphQLQueryEditor(project)
    private val variablesEditor = GraphQLVariablesEditor(project)

    private var onExecuteCallback: ((GraphQLResponse) -> Unit)? = null
    private var onIntrospectCallback: ((String) -> Unit)? = null

    init {
        initializeUI()
        setupEventListeners()
    }

    private fun initializeUI() {
        // Top panel with endpoint and buttons
        val topPanel = createTopPanel()
        add(topPanel, BorderLayout.NORTH)

        // Center panel with editors split vertically
        val editorSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        editorSplitPane.topComponent = createQueryPanel()
        editorSplitPane.bottomComponent = createVariablesPanel()
        editorSplitPane.resizeWeight = 0.7
        editorSplitPane.dividerLocation = 400

        add(editorSplitPane, BorderLayout.CENTER)
    }

    private fun createTopPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(10)

        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(5)

        // Endpoint label
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JBLabel("Endpoint:"), gbc)

        // Endpoint field
        gbc.gridx = 1
        gbc.weightx = 1.0
        endpointField.toolTipText = "GraphQL endpoint URL"
        panel.add(endpointField, gbc)

        // Buttons panel
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))

        executeButton.preferredSize = Dimension(140, 30)
        executeButton.toolTipText = "Execute the GraphQL query"
        buttonsPanel.add(executeButton)

        introspectButton.preferredSize = Dimension(150, 30)
        introspectButton.toolTipText = "从端点获取 schema"
        buttonsPanel.add(introspectButton)

        generateSampleButton.preferredSize = Dimension(120, 30)
        generateSampleButton.toolTipText = "生成示例查询"
        buttonsPanel.add(generateSampleButton)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        panel.add(buttonsPanel, gbc)

        return panel
    }

    private fun createQueryPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5, 10)

        val label = JBLabel("Query:")
        label.border = JBUI.Borders.empty(0, 0, 5, 0)

        panel.add(label, BorderLayout.NORTH)
        panel.add(queryEditor, BorderLayout.CENTER)

        return panel
    }

    private fun createVariablesPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5, 10)

        val label = JBLabel("Variables (JSON):")
        label.border = JBUI.Borders.empty(0, 0, 5, 0)

        panel.add(label, BorderLayout.NORTH)
        panel.add(variablesEditor, BorderLayout.CENTER)

        return panel
    }

    private fun setupEventListeners() {
        executeButton.addActionListener {
            executeQuery()
        }

        introspectButton.addActionListener {
            introspectSchema()
        }

        generateSampleButton.addActionListener {
            generateSample()
        }
    }

    private fun executeQuery() {
        val endpoint = endpointField.text.trim()
        if (endpoint.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a GraphQL endpoint URL",
                "Missing Endpoint",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val queryText = queryEditor.getText().trim()
        if (queryText.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a GraphQL query",
                "Missing Query",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Parse variables (if any)
        val variablesText = variablesEditor.getText().trim()
        val variables: Map<String, Any>? = if (variablesText.isNotEmpty()) {
            try {
                objectMapper.readValue<Map<String, Any>>(variablesText)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    this,
                    "Invalid JSON in variables: ${e.message}",
                    "Variables Error",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
        } else {
            null
        }

        // Build GraphQL request
        val graphqlRequest = GraphQLRequest(
            query = queryText,
            variables = variables,
            operationName = null
        )

        // Execute in coroutine
        executeButton.isEnabled = false
        executeButton.text = "Executing..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val executionService = service<GraphQLExecutionService>()
                val startTime = System.currentTimeMillis()
                val response = executionService.executeGraphQL(endpoint, graphqlRequest)
                val executionTime = System.currentTimeMillis() - startTime

                // Save to history
                saveToHistory(endpoint, graphqlRequest, response, executionTime)

                // Update UI on Swing thread
                SwingUtilities.invokeLater {
                    executeButton.isEnabled = true
                    executeButton.text = "Execute Query"
                    onExecuteCallback?.invoke(response)
                }

            } catch (e: Exception) {
                logger.error("GraphQL execution failed", e)
                SwingUtilities.invokeLater {
                    executeButton.isEnabled = true
                    executeButton.text = "Execute Query"
                    JOptionPane.showMessageDialog(
                        this@GraphQLPanel,
                        "Execution failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun introspectSchema() {
        val endpoint = endpointField.text.trim()
        if (endpoint.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a GraphQL endpoint URL",
                "Missing Endpoint",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        introspectButton.isEnabled = false
        introspectButton.text = "Introspecting..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                onIntrospectCallback?.invoke(endpoint)

                SwingUtilities.invokeLater {
                    introspectButton.isEnabled = true
                    introspectButton.text = "Introspect Schema"
                }

            } catch (e: Exception) {
                logger.error("Schema introspection failed", e)
                SwingUtilities.invokeLater {
                    introspectButton.isEnabled = true
                    introspectButton.text = "Introspect Schema"
                    JOptionPane.showMessageDialog(
                        this@GraphQLPanel,
                        "Introspection failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun saveToHistory(
        endpoint: String,
        request: GraphQLRequest,
        response: GraphQLResponse,
        executionTimeMs: Long
    ) {
        try {
            val historyService = service<com.httppal.graphql.service.GraphQLHistoryService>()

            // Convert response to JSON strings
            val responseDataJson = if (response.data != null) {
                objectMapper.writeValueAsString(response.data)
            } else null

            val responseErrorsJson = if (response.errors != null && response.errors.isNotEmpty()) {
                objectMapper.writeValueAsString(response.errors)
            } else null

            val variablesJson = if (request.variables != null && request.variables.isNotEmpty()) {
                objectMapper.writeValueAsString(request.variables)
            } else null

            val historyEntry = com.httppal.graphql.model.GraphQLHistoryEntry(
                endpoint = endpoint,
                query = request.query,
                variables = variablesJson,
                operationName = request.operationName,
                responseData = responseDataJson,
                responseErrors = responseErrorsJson,
                executionTimeMs = executionTimeMs
            )

            historyService.addToHistory(historyEntry)
            logger.info("Saved GraphQL request to history")

        } catch (e: Exception) {
            logger.warn("Failed to save GraphQL request to history: ${e.message}")
        }
    }

    private fun generateSample() {
        val endpoint = endpointField.text.trim()
        if (endpoint.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "请先输入 GraphQL 端点 URL",
                "缺少端点",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val schemaService = service<com.httppal.graphql.service.GraphQLSchemaService>()
        val schema = schemaService.getCachedSchema(endpoint)

        if (schema == null) {
            JOptionPane.showMessageDialog(
                this,
                "未找到 schema。请先点击\"获取 Schema\"按钮。",
                "缺少 Schema",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        try {
            val mockGenerator = com.httppal.graphql.util.GraphQLMockGenerator(schema)

            // 生成示例查询
            val sampleQuery = mockGenerator.generateSampleQuery("Query", maxDepth = 2)
            queryEditor.setText(sampleQuery)

            logger.info("Generated sample GraphQL query")

            JOptionPane.showMessageDialog(
                this,
                "已生成示例查询！",
                "成功",
                JOptionPane.INFORMATION_MESSAGE
            )

        } catch (e: Exception) {
            logger.error("Failed to generate sample", e)
            JOptionPane.showMessageDialog(
                this,
                "生成示例失败：${e.message}",
                "错误",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    /**
     * Set callback to be invoked when a query is executed.
     */
    fun setOnExecuteCallback(callback: (GraphQLResponse) -> Unit) {
        this.onExecuteCallback = callback
    }

    /**
     * Set callback to be invoked when introspection is requested.
     */
    fun setOnIntrospectCallback(callback: (String) -> Unit) {
        this.onIntrospectCallback = callback
    }

    /**
     * Set the endpoint URL.
     */
    fun setEndpoint(endpoint: String) {
        endpointField.text = endpoint
    }

    /**
     * Get the current endpoint URL.
     */
    fun getEndpoint(): String = endpointField.text.trim()

    /**
     * Set the query text.
     */
    fun setQuery(query: String) {
        queryEditor.setText(query)
    }

    /**
     * Get the current query text.
     */
    fun getQuery(): String = queryEditor.getText()

    /**
     * Set the variables text.
     */
    fun setVariables(variables: String) {
        variablesEditor.setText(variables)
    }

    /**
     * Get the current variables text.
     */
    fun getVariables(): String = variablesEditor.getText()

    /**
     * Insert text at cursor position in the query editor.
     */
    fun insertTextAtCursor(text: String) {
        queryEditor.insertTextAtCursor("\n$text\n")
    }

    /**
     * Clear all fields.
     */
    fun clear() {
        queryEditor.clear()
        variablesEditor.clear()
    }

    /**
     * Get the query editor component.
     */
    fun getQueryEditor(): GraphQLQueryEditor = queryEditor

    /**
     * Set the query text silently without triggering listeners.
     */
    fun setQuerySilently(query: String) {
        queryEditor.setTextSilently(query)
    }

    /**
     * Dispose the editors when the panel is no longer needed.
     */
    fun dispose() {
        queryEditor.dispose()
        variablesEditor.dispose()
    }
}
