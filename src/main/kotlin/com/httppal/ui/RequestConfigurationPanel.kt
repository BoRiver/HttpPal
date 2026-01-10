package com.httppal.ui

import com.httppal.model.*
import com.httppal.service.EnvironmentService
import com.httppal.service.FavoritesService
import com.httppal.service.HttpPalService
import com.httppal.service.ParseResult
import com.httppal.service.RequestExecutionService
import com.httppal.util.ErrorHandler
import com.httppal.util.HttpPalBundle
import com.httppal.util.LoggingUtils
import com.httppal.util.ValidationUtils
import com.httppal.util.VisualFeedbackHelper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.time.Duration
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

/**
 * Panel for configuring HTTP requests with form validation and syntax highlighting
 * Implements requirements 2.1, 3.2, 5.1, 5.5
 */
class RequestConfigurationPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val httpPalService = service<HttpPalService>()
    private val environmentService = project.service<EnvironmentService>()  // Use project-level service
    
    // New components for manual request creation
    private val toolbar = RequestToolbar(project)
    private val enhancedUrlField = EnhancedUrlField(project)
    
    // New services - use correct service level
    private val templateService = project.service<com.httppal.service.RequestTemplateService>()
    private val curlParserService = project.service<com.httppal.service.CurlParserService>()
    private val variableResolver = project.service<com.httppal.service.EnvironmentVariableResolver>()
    
    // New state for manual request creation
    private var requestSource: RequestSource = RequestSource.MANUAL
    private var originalRequest: RequestConfig? = null
    
    // Form components
    private val methodComboBox = JComboBox(HttpMethod.values())
    private val headersTable = createHeadersTable()
    private val bodyEditor = createBodyEditor()
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(30, 1, 300, 1))
    private val followRedirectsCheckBox = JCheckBox(HttpPalBundle.message("form.request.follow.redirects"), true)
    
    // New parameter panels (Postman-style)
    private val queryParametersPanel = QueryParametersPanel(project)
    private val pathParametersPanel = PathParametersPanel(project)
    private val formDataPanel = FormDataPanel(project)
    
    // Tabbed pane for request configuration
    private val requestTabbedPane = com.intellij.ui.components.JBTabbedPane()
    
    // Body type selector
    private val bodyTypeComboBox = JComboBox(arrayOf("none", "raw", "form-data"))
    private val bodyCardPanel = JPanel(CardLayout())
    
    // Mock data generation service
    private val mockDataGeneratorService = project.service<com.httppal.service.MockDataGeneratorService>()
    
    // Concurrent execution components
    private val concurrentExecutionPanel = ConcurrentExecutionPanel(project)
    
    // Validation components
    private val validationPanel = JPanel(BorderLayout())
    private val validationLabel = JBLabel()
    private val sendButton = JButton(HttpPalBundle.message("request.send.button"))
    private val cancelButton = JButton(HttpPalBundle.message("button.cancel"))
    private val exportButton = JButton(HttpPalBundle.message("request.export.button"))
    private val mockDataButton = JButton("Generate Mock Data")
    
    // State
    private var currentRequest: RequestConfig? = null
    private var currentEndpoint: DiscoveredEndpoint? = null  // Store current endpoint for details display
    private var validationErrors: List<String>? = emptyList()
    private var lastValidationState: Boolean = true  // Track validation state changes
    private var onSendRequestCallback: ((RequestConfig) -> Unit)? = null
    private var onConcurrentExecutionCallback: ((ConcurrentExecutionResult) -> Unit)? = null
    private var onCancelRequestCallback: (() -> Unit)? = null
    private var isRequestInProgress = false
    private var currentExecutionId: String? = null
    
    // Endpoint details panel
    private val endpointDetailsPanel = JPanel()
    private var endpointDetailsVisible = false
    
    init {
        setupUI()
        setupValidation()
        setupEventHandlers()
        setupKeyboardShortcuts()
        setupEndpointChangeListener()
    }
    
    private fun setupUI() {
        border = JBUI.Borders.empty(15)  // 增加外边距
        
        // Add toolbar at the top
        add(toolbar, BorderLayout.NORTH)
        
        // Setup toolbar callbacks
        toolbar.setOnNewRequestCallback { template ->
            createNewRequest(template)
        }
        toolbar.setOnSaveRequestCallback {
            saveAsFavorite()
        }
        toolbar.setOnQuickCreateCallback {
            showQuickCreateDialog()
        }
        toolbar.setOnImportFromClipboardCallback {
            importFromClipboard()
        }
        toolbar.setOnImportFromPostmanCallback {
            importFromPostman()
        }
        
        // Create main container panel
        val mainContainer = JPanel(BorderLayout())
        
        // Create endpoint details panel (initially hidden)
        endpointDetailsPanel.layout = BoxLayout(endpointDetailsPanel, BoxLayout.Y_AXIS)
        endpointDetailsPanel.border = JBUI.Borders.compound(
            JBUI.Borders.empty(5, 0, 10, 0),
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        )
        endpointDetailsPanel.isVisible = false
        mainContainer.add(endpointDetailsPanel, BorderLayout.NORTH)
        
        // Create scrollable main panel
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(8)  // 增加组件间距
        gbc.anchor = GridBagConstraints.WEST
        
        // Method selection
        gbc.gridx = 0; gbc.gridy = 0
        val methodLabel = JBLabel(HttpPalBundle.message("request.method.label"))
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 13f)  // 增大字体
        mainPanel.add(methodLabel, gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE
        methodComboBox.preferredSize = Dimension(120, 32)  // 增大尺寸
        methodComboBox.font = methodComboBox.font.deriveFont(13f)
        mainPanel.add(methodComboBox, gbc)
        
        // URL input - use EnhancedUrlField instead of plain text field
        gbc.gridx = 0; gbc.gridy = 1
        val urlLabel = JBLabel(HttpPalBundle.message("request.url.label"))
        urlLabel.font = urlLabel.font.deriveFont(Font.BOLD, 13f)
        mainPanel.add(urlLabel, gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0

        // EnhancedUrlField callbacks will be set in setupValidation()

        mainPanel.add(enhancedUrlField, gbc)
        
        // Path Parameters section (auto-shows when URL contains {param})
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 0.0
        pathParametersPanel.onParametersChanged = { params ->
            // Update URL when path parameters change
            val currentUrl = enhancedUrlField.getText()
            val updatedUrl = pathParametersPanel.applyToUrl(currentUrl)
            if (updatedUrl != currentUrl) {
                enhancedUrlField.setText(updatedUrl)
            }
        }
        mainPanel.add(pathParametersPanel, gbc)
        
        // Add a small separator
        gbc.gridy = 3; gbc.insets = JBUI.insets(15, 8, 5, 8)
        mainPanel.add(JSeparator(), gbc)
        
        // Request options panel
        gbc.gridy = 4; gbc.insets = JBUI.insets(8)
        val optionsPanel = createOptionsPanel()
        mainPanel.add(optionsPanel, gbc)
        
        // Tabbed pane for Params/Headers/Body
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0; gbc.weighty = 1.0 // Give it full weight to expand correctly
        
        // Setup query parameters callback
        // Note: We don't auto-update the URL here to avoid duplicate parameters
        // Parameters are stored in RequestConfig.queryParameters and applied in getFinalUrl()
        queryParametersPanel.onParametersChanged = { params ->
            // Just validate the form when parameters change
            validateForm()
        }
        
        // Create headers panel
        val headersPanel = createHeadersPanel()
        
        // Create body panel with type selector
        val bodyPanelContainer = createBodyPanelWithTypes()
        
        // Add tabs
        requestTabbedPane.addTab("Params", queryParametersPanel)
        requestTabbedPane.addTab("Headers", headersPanel)
        requestTabbedPane.addTab("Body", bodyPanelContainer)
        
        mainPanel.add(requestTabbedPane, gbc)
        
        // Concurrent execution section
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0; gbc.weighty = 0.0
        setupConcurrentExecutionPanel()
        mainPanel.add(concurrentExecutionPanel, gbc)
        
        // Wrap in scroll pane for better visibility
        // Use a wrapper panel to ensure it stretches to full width
        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.viewport.isOpaque = false
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        mainContainer.add(scrollPane, BorderLayout.CENTER)
        
        add(mainContainer, BorderLayout.CENTER)
        
        // Bottom panel with validation and send button
        val bottomPanel = createBottomPanel()
        add(bottomPanel, BorderLayout.SOUTH)
    }
    
    private fun createOptionsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = TitledBorder(HttpPalBundle.message("form.request.options.title"))
        
        // Timeout setting
        panel.add(JBLabel(HttpPalBundle.message("form.request.timeout.seconds")))
        timeoutSpinner.preferredSize = Dimension(80, timeoutSpinner.preferredSize.height)
        panel.add(timeoutSpinner)
        
        // Follow redirects checkbox
        panel.add(Box.createHorizontalStrut(20))
        panel.add(followRedirectsCheckBox)
        
        return panel
    }
    
    private fun createHeadersPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder(HttpPalBundle.message("form.request.headers.title"))
        
        // Headers table
        val scrollPane = JBScrollPane(headersTable)
        scrollPane.preferredSize = Dimension(400, 120)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Headers controls
        val controlsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val addHeaderButton = JButton(HttpPalBundle.message("button.add.header"))
        val removeHeaderButton = JButton(HttpPalBundle.message("button.remove.selected"))
        val clearHeadersButton = JButton(HttpPalBundle.message("button.clear.all"))
        
        addHeaderButton.addActionListener { addHeaderRow() }
        removeHeaderButton.addActionListener { removeSelectedHeaders() }
        clearHeadersButton.addActionListener { clearAllHeaders() }
        
        controlsPanel.add(addHeaderButton)
        controlsPanel.add(removeHeaderButton)
        controlsPanel.add(clearHeadersButton)
        panel.add(controlsPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createHeadersTable(): JBTable {
        val model = object : DefaultTableModel(
            arrayOf(
                HttpPalBundle.message("headers.name.column"),
                HttpPalBundle.message("headers.value.column")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean = true
        }
        
        val table = JBTable(model)
        table.fillsViewportHeight = true
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        
        // Add initial empty row
        model.addRow(arrayOf("", ""))
        
        // Add validation on cell editing
        table.model.addTableModelListener { validateForm() }
        
        return table
    }
    
    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = TitledBorder(HttpPalBundle.message("form.request.body.title"))
        
        // Content type selector
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        topPanel.add(JBLabel(HttpPalBundle.message("form.request.content.type")))
        val contentTypeCombo = JComboBox(arrayOf(
            HttpPalBundle.message("content.type.json"),
            HttpPalBundle.message("content.type.xml"),
            HttpPalBundle.message("content.type.plain"),
            HttpPalBundle.message("content.type.form.urlencoded"),
            HttpPalBundle.message("content.type.form.multipart"),
            HttpPalBundle.message("content.type.html")
        ))
        contentTypeCombo.isEditable = true
        contentTypeCombo.addActionListener { updateBodyEditorSyntax() }
        topPanel.add(contentTypeCombo)
        panel.add(topPanel, BorderLayout.NORTH)
        
        // Body editor
        val editorPanel = JPanel(BorderLayout())
        editorPanel.add(bodyEditor.component, BorderLayout.CENTER)
        panel.add(editorPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    /**
     * Create body panel with type selector supporting raw text and form-data
     */
    private fun createBodyPanelWithTypes(): JPanel {
        val container = JPanel(BorderLayout())
        container.border = JBUI.Borders.empty()
        
        // Top panel with body type selector
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5))
        topPanel.add(JBLabel("Body type:"))
        
            bodyTypeComboBox.addActionListener {
                val cardLayout = bodyCardPanel.layout as CardLayout
                val selected = bodyTypeComboBox.selectedItem as String
                when (selected) {
                    "none" -> cardLayout.show(bodyCardPanel, "none")
                    "raw" -> cardLayout.show(bodyCardPanel, "raw")
                    "form-data" -> cardLayout.show(bodyCardPanel, "form-data")
                }
                
                // Force revalidate to ensure editor renders correctly
                if (selected == "raw") {
                    // Ensure editor component is visible and repainted
                    bodyEditor.component.isVisible = true
                    bodyEditor.contentComponent.revalidate()
                    bodyEditor.contentComponent.repaint()
                    
                    // Also force parent layout update
                    bodyCardPanel.revalidate()
                    bodyCardPanel.repaint()
                }
                
                validateForm()
            }
        topPanel.add(bodyTypeComboBox)
        
        container.add(topPanel, BorderLayout.NORTH)
        
        // Body content panel with CardLayout
        bodyCardPanel.layout = CardLayout()
        
        // None panel (empty)
        val nonePanel = JPanel()
        nonePanel.add(JBLabel("This request does not have a body"))
        bodyCardPanel.add(nonePanel, "none")
        
        // Raw body panel (existing editor)
        val rawBodyPanel = createBodyPanel()
        bodyCardPanel.add(rawBodyPanel, "raw")
        
        // Form-data panel (new)
        bodyCardPanel.add(formDataPanel, "form-data")
        
        // Set default to "none"
        bodyTypeComboBox.selectedItem = "none"
        
        container.add(bodyCardPanel, BorderLayout.CENTER)
        
        return container
    }
    
    private fun createBodyEditor(): EditorEx {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
        val editor = editorFactory.createEditor(document, project) as EditorEx
        
        // Configure editor settings
        val settings = editor.settings
        settings.isLineNumbersShown = true
        settings.isAutoCodeFoldingEnabled = true
        settings.isFoldingOutlineShown = true
        settings.isAllowSingleLogicalLineFolding = false
        settings.isRightMarginShown = false
        
        // Set initial file type for JSON syntax highlighting
        val jsonFileType = FileTypeManager.getInstance().getFileTypeByExtension("json")
        val highlighterFactory = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
        (editor as EditorEx).highlighter = highlighterFactory.createEditorHighlighter(project, jsonFileType)
        
        // Add document listener for validation
        document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                validateForm()
            }
        })
        
        return editor
    }
    
    private fun createBottomPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        // Validation panel
        validationPanel.isVisible = false
        validationLabel.foreground = Color.RED
        validationPanel.add(validationLabel, BorderLayout.CENTER)
        panel.add(validationPanel, BorderLayout.CENTER)
        
        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        
        // Send button
        sendButton.preferredSize = Dimension(120, 30)
        sendButton.addActionListener { sendRequest() }
        
        // Add visual feedback to send button
        // Implements requirement 3.3: Show button click effects
        com.httppal.util.VisualFeedbackHelper.addClickEffect(sendButton)
        com.httppal.util.VisualFeedbackHelper.addHoverEffect(sendButton)
        
        // Cancel button (initially hidden)
        // Implements requirement 3.4: Show cancel button during request execution
        cancelButton.preferredSize = Dimension(120, 30)
        cancelButton.isVisible = false
        cancelButton.addActionListener { cancelRequest() }
        
        // Add visual feedback to cancel button
        com.httppal.util.VisualFeedbackHelper.addClickEffect(cancelButton)
        com.httppal.util.VisualFeedbackHelper.addHoverEffect(cancelButton)
        
        // Export button
        exportButton.preferredSize = Dimension(140, 30)
        exportButton.addActionListener { showJMeterExportDialog() }
        exportButton.toolTipText = HttpPalBundle.message("tooltip.export.jmeter")
        
        // Add visual feedback to export button
        // Implements requirement 3.3: Show button click effects
        com.httppal.util.VisualFeedbackHelper.addClickEffect(exportButton)
        com.httppal.util.VisualFeedbackHelper.addHoverEffect(exportButton)
        
        // Mock data button
        // Implements Task 12.1: Add "Generate Mock Data" button
        mockDataButton.preferredSize = Dimension(160, 30)
        mockDataButton.addActionListener { generateAndFillMockData() }
        mockDataButton.toolTipText = "Generate mock data based on OpenAPI schema"
        mockDataButton.isEnabled = false  // Initially disabled, enabled when endpoint with schema is selected
        
        // Add visual feedback to mock data button
        com.httppal.util.VisualFeedbackHelper.addClickEffect(mockDataButton)
        com.httppal.util.VisualFeedbackHelper.addHoverEffect(mockDataButton)
        
        buttonPanel.add(mockDataButton)
        buttonPanel.add(exportButton)
        buttonPanel.add(cancelButton)
        buttonPanel.add(sendButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun setupValidation() {
        // Add validation listeners to form components with debouncing
        var validationJob: kotlinx.coroutines.Job? = null
        val validationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        
        val debouncedValidation = {
            validationJob?.cancel()
            validationJob = validationScope.launch {
                kotlinx.coroutines.delay(300) // 300ms debounce
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    validateForm()
                    detectModifications()  // Also detect modifications on changes
                }
            }
        }
        
        // EnhancedUrlField already has its own validation callback
        enhancedUrlField.onUrlChanged = { url ->
            // Update path parameters when URL changes
            pathParametersPanel.updateFromUrl(url)
            // Trigger debounced validation and modification detection
            debouncedValidation()
        }
        
        methodComboBox.addActionListener { 
            validateForm()
            detectModifications()
        }
        timeoutSpinner.addChangeListener { 
            validateForm()
            detectModifications()
        }
        
        // Initial validation
        validateForm()
    }
    
    private fun setupEventHandlers() {
        // EnhancedUrlField handles its own focus events
        // No need for additional event handlers here
    }
    
    private fun validateForm() {
        ErrorHandler.withErrorHandling("Validate request form", project, this) {
            val errors = mutableListOf<String>()
            
            // Validate URL
            val url = enhancedUrlField.getText().trim()  // Use enhancedUrlField instead of urlTextField
            val urlValidation = ValidationUtils.validateNotBlank(url, "URL")
            if (!urlValidation.isValid) {
                errors.addAll(urlValidation.errors)
            } else {
                // Check if we have an active environment
                val currentEnvironment = environmentService.getCurrentEnvironment()
                
                // Additional URL format validation
                if (currentEnvironment != null) {
                    // With environment: allow relative paths or absolute URLs
                    if (!isValidUrlOrTemplate(url) && !isValidRelativePath(url)) {
                        errors.add(HttpPalBundle.message("validation.url.format.invalid"))
                    }
                } else {
                    // Without environment: require absolute URL
                    if (!isValidUrlOrTemplate(url)) {
                        errors.add(HttpPalBundle.message("validation.url.format.absolute.required"))
                    }
                }
            }
            
            // Validate timeout
            val timeout = timeoutSpinner.value as Int
            val timeoutValidation = ValidationUtils.validateRange(timeout, "Timeout", 1, 300)
            if (!timeoutValidation.isValid) {
                errors.addAll(timeoutValidation.errors)
            }
            
            // Validate headers
            val headersValidation = validateHeaders()
            if (!headersValidation.isValid) {
                errors.addAll(headersValidation.errors)
            }
            
            // Validate body for specific methods
            /*val method = methodComboBox.selectedItem as HttpMethod
            val body = bodyEditor.document.text
            if (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH) && body.isNotBlank()) {
                val bodyValidation = validateRequestBody(body)
                if (!bodyValidation.isValid) {
                    errors.addAll(bodyValidation.errors)
                }
            }*/
            
            validationErrors = errors
            updateValidationDisplay()
            updateSendButtonState()  // Use the new method to handle concurrent execution state
            exportButton.isEnabled = errors.isEmpty()
            
            // Only log when validation state changes (to avoid log spam)
            val currentValidationState = errors.isEmpty()
            if (currentValidationState != lastValidationState) {
                lastValidationState = currentValidationState
                
                if (errors.isNotEmpty()) {
                    // Only log at DEBUG level to reduce noise
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.DEBUG,
                        "Request form validation failed",
                        mapOf("errorCount" to errors.size, "errors" to errors.joinToString("; "))
                    )
                } else {
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.DEBUG,
                        "Request form validation passed",
                        mapOf("url" to url, "method" to (methodComboBox.selectedItem as HttpMethod).name)
                    )
                }
            }
        }
    }
    
    private fun validateHeaders(): ValidationResult {
        val errors = mutableListOf<String>()
        val model = headersTable.model as DefaultTableModel
        val headerNames = mutableSetOf<String>()
        
        for (row in 0 until model.rowCount) {
            val name = model.getValueAt(row, 0)?.toString()?.trim() ?: ""
            val value = model.getValueAt(row, 1)?.toString()?.trim() ?: ""
            
            // Skip empty rows
            if (name.isEmpty() && value.isEmpty()) continue
            
            // Validate header name
            if (name.isEmpty()) {
                errors.add(HttpPalBundle.message("validation.header.row.empty", row + 1))
                continue
            }
            
            val nameValidation = ValidationUtils.validateHeaderName(name)
            if (!nameValidation.isValid) {
                errors.addAll(nameValidation.errors.map { HttpPalBundle.message("validation.header.row.invalid", row + 1) })
            }
            
            // Check for duplicate header names
            if (headerNames.contains(name.lowercase())) {
                errors.add(HttpPalBundle.message("validation.header.duplicate", name, row + 1))
            } else {
                headerNames.add(name.lowercase())
            }
            
            // Validate header value (basic validation)
            if (value.contains("\n") || value.contains("\r")) {
                errors.add(HttpPalBundle.message("validation.header.value.linebreak", row + 1))
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.valid()
        } else {
            ValidationResult.invalid(errors)
        }
    }
    
    private fun validateRequestBody(body: String): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Basic JSON validation if content type suggests JSON
        if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
            try {
                // Simple JSON structure validation
                var braceCount = 0
                var bracketCount = 0
                var inString = false
                var escaped = false
                
                for (char in body) {
                    when {
                        escaped -> escaped = false
                        char == '\\' && inString -> escaped = true
                        char == '"' -> inString = !inString
                        !inString -> when (char) {
                            '{' -> braceCount++
                            '}' -> braceCount--
                            '[' -> bracketCount++
                            ']' -> bracketCount--
                        }
                    }
                }
                
                if (braceCount != 0) {
                    errors.add(HttpPalBundle.message("validation.body.json.braces"))
                }
                if (bracketCount != 0) {
                    errors.add(HttpPalBundle.message("validation.body.json.brackets"))
                }
            } catch (e: Exception) {
                errors.add(HttpPalBundle.message("validation.body.json.invalid"))
            }
        }
        
        return if (errors.isEmpty()) {
            ValidationResult.valid()
        } else {
            ValidationResult.invalid(errors)
        }
    }
    
    private fun updateValidationDisplay() {
        if (validationErrors?.isEmpty() != false) {
            validationPanel.isVisible = false
        } else {
            validationLabel.text = "<html>${validationErrors?.joinToString("<br>")}</html>"
            validationPanel.isVisible = true
        }
        revalidate()
        repaint()
    }
    
    private fun updateBodyEditorSyntax() {
        // This would update syntax highlighting based on content type
        // Implementation depends on IntelliJ Platform APIs
    }
    
    private fun addHeaderRow() {
        val model = headersTable.model as DefaultTableModel
        model.addRow(arrayOf("", ""))
        validateForm()
    }
    
    private fun removeSelectedHeaders() {
        val model = headersTable.model as DefaultTableModel
        val selectedRows = headersTable.selectedRows
        
        // Remove rows in reverse order to maintain indices
        for (i in selectedRows.indices.reversed()) {
            model.removeRow(selectedRows[i])
        }
        
        // Ensure at least one empty row exists
        if (model.rowCount == 0) {
            model.addRow(arrayOf("", ""))
        }
        
        validateForm()
    }
    
    private fun clearAllHeaders() {
        val model = headersTable.model as DefaultTableModel
        model.rowCount = 0
        model.addRow(arrayOf("", ""))
        validateForm()
    }
    
    private fun sendRequest() {
        if (validationErrors?.isNotEmpty() == true) {
            // Show error feedback
            // Implements requirement 3.1: Immediate visual feedback
            VisualFeedbackHelper.showErrorFeedback(
                sendButton as JComponent,
                "Please fix validation errors"
            )
            return
        }
        
        val requestConfig = buildRequestConfig()
        currentRequest = requestConfig
        
        // Show success feedback on button
        // Implements requirement 3.1: Immediate visual feedback
        com.httppal.util.VisualFeedbackHelper.showSuccessFeedback(
            sendButton as JComponent
        )
        
        // Show cancel button and hide send button during request execution
        // Implements requirement 3.4: Show cancel button during request execution
        showRequestInProgress()
        
        // Check if concurrent execution is enabled
        if (concurrentExecutionPanel.isConcurrentExecutionEnabled()) {
            // Concurrent execution will be handled by the concurrent execution panel
            // The panel will call its own execution logic
            // Ensure the send button remains disabled during concurrent execution
            sendButton.isEnabled = false
            return
        }
        
        onSendRequestCallback?.invoke(requestConfig)
    }
    
    /**
     * Cancel the current request
     * Implements requirement 3.4: Implement cancel logic
     */
    private fun cancelRequest() {
        // Notify callback to cancel the request
        onCancelRequestCallback?.invoke()
        
        // Update UI state
        // Implements requirement 3.4: Update UI state after cancellation
        hideRequestInProgress()
        
        // Show feedback
        com.httppal.util.VisualFeedbackHelper.showTemporaryStatus(
            validationLabel,
            HttpPalBundle.message("message.request.cancelled"),
            2000,
            Color.ORANGE
        )
        
        // If we have an execution ID, try to cancel it
        currentExecutionId?.let { executionId ->
            val requestExecutionService = service<RequestExecutionService>()
            val cancelled = requestExecutionService.cancelExecution(executionId)
            if (cancelled) {
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.INFO,
                    "Request cancelled successfully",
                    mapOf("executionId" to executionId)
                )
            }
        }
        
        currentExecutionId = null
    }
    
    /**
     * Show UI state for request in progress
     * Implements requirement 3.4: Show cancel button during request execution
     */
    private fun showRequestInProgress() {
        isRequestInProgress = true
        
        com.httppal.util.AsyncUIHelper.invokeLater {
            sendButton.isVisible = false
            cancelButton.isVisible = true
            exportButton.isEnabled = false
            
            // Disable form inputs
            setEnabled(false)
            
            revalidate()
            repaint()
        }
    }
    
    /**
     * Hide UI state for request in progress
     * Implements requirement 3.4: Update UI state after completion/cancellation
     */
    private fun hideRequestInProgress() {
        isRequestInProgress = false
        
        com.httppal.util.AsyncUIHelper.invokeLater {
            sendButton.isVisible = true
            cancelButton.isVisible = false
            exportButton.isEnabled = true
            
            // Re-enable form inputs
            setEnabled(true)
            
            // Ensure send button state is correctly updated based on concurrent execution status
            updateSendButtonState()
            
            revalidate()
            repaint()
        }
    }
    
    private fun setupConcurrentExecutionPanel() {
        // Set up the request config provider for concurrent execution
        concurrentExecutionPanel.setRequestConfigProvider {
            if (validationErrors?.isEmpty() != false) {
                buildRequestConfig()
            } else {
                null
            }
        }
        
        // Set up callback for concurrent execution results
        concurrentExecutionPanel.setOnExecutionCallback { result ->
            onConcurrentExecutionCallback?.invoke(result)
        }
        
        // Listen for concurrent execution state changes to disable/enable send button
        // When concurrent execution is enabled, disable the send button
        // When concurrent execution is disabled, re-enable the send button
        concurrentExecutionPanel.setOnConcurrentStateChangeListener { isEnabled ->
            updateSendButtonState()
        }
    }
    
    private fun updateSendButtonState() {
        val isConcurrentEnabled = concurrentExecutionPanel.isConcurrentExecutionEnabled()
        
        // If concurrent execution is enabled, disable the send button
        // Otherwise, set it based on validation state
        if (isConcurrentEnabled) {
            sendButton.isEnabled = false
        } else {
            // Re-enable based on validation status
            sendButton.isEnabled = validationErrors?.isEmpty() != false
        }
    }
    
    /**
     * Check if concurrent execution is currently enabled
     */
    fun isConcurrentExecutionEnabled(): Boolean {
        return concurrentExecutionPanel.isConcurrentExecutionEnabled()
    }
    
    /**
     * Update endpoint details panel with OpenAPI information
     * Implements requirement 5.4: Display endpoint description, summary, tags, and operationId
     */
    private fun updateEndpointDetailsPanel(endpoint: DiscoveredEndpoint) {
        // Clear existing content
        endpointDetailsPanel.removeAll()
        
        // Check if endpoint has OpenAPI information
        val hasOpenAPIInfo = endpoint.source == EndpointSource.OPENAPI && 
            (endpoint.summary != null || endpoint.operationId != null || endpoint.tags.isNotEmpty())
        
        if (!hasOpenAPIInfo) {
            endpointDetailsPanel.isVisible = false
            endpointDetailsVisible = false
            revalidate()
            repaint()
            return
        }
        
        // Create details content panel
        val detailsContent = JPanel()
        detailsContent.layout = BoxLayout(detailsContent, BoxLayout.Y_AXIS)
        detailsContent.border = JBUI.Borders.empty(8, 10, 8, 10)
        detailsContent.background = JBColor(Color(245, 248, 250), Color(45, 48, 51))
        
        // Add OpenAPI indicator icon
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        headerPanel.isOpaque = false
        val openAPILabel = JBLabel("📋 OpenAPI Endpoint")
        openAPILabel.font = openAPILabel.font.deriveFont(Font.BOLD, 12f)
        openAPILabel.foreground = JBColor(Color(0, 102, 204), Color(88, 166, 255))
        headerPanel.add(openAPILabel)
        detailsContent.add(headerPanel)
        detailsContent.add(Box.createVerticalStrut(8))
        
        // Display summary if available
        endpoint.summary?.let { summary ->
            val summaryPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            summaryPanel.isOpaque = false
            val summaryLabel = JBLabel("<html><b>Summary:</b> $summary</html>")
            summaryLabel.font = summaryLabel.font.deriveFont(12f)
            summaryPanel.add(summaryLabel)
            detailsContent.add(summaryPanel)
        }
        
        // Display operationId if available
        endpoint.operationId?.let { operationId ->
            val operationIdPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            operationIdPanel.isOpaque = false
            val operationIdLabel = JBLabel("<html><b>Operation ID:</b> <code>$operationId</code></html>")
            operationIdLabel.font = operationIdLabel.font.deriveFont(11f)
            operationIdLabel.foreground = JBColor.GRAY
            operationIdPanel.add(operationIdLabel)
            detailsContent.add(operationIdPanel)
        }
        
        // Display tags if available
        if (endpoint.tags.isNotEmpty()) {
            val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            tagsPanel.isOpaque = false
            val tagsLabel = JBLabel("<html><b>Tags:</b></html>")
            tagsLabel.font = tagsLabel.font.deriveFont(11f)
            tagsPanel.add(tagsLabel)
            
            // Add tag badges
            endpoint.tags.forEach { tag ->
                val tagBadge = JBLabel(" $tag ")
                tagBadge.font = tagBadge.font.deriveFont(10f)
                tagBadge.foreground = JBColor(Color(255, 255, 255), Color(200, 200, 200))
                tagBadge.background = JBColor(Color(0, 122, 204), Color(70, 130, 180))
                tagBadge.isOpaque = true
                tagBadge.border = JBUI.Borders.empty(2, 6, 2, 6)
                tagsPanel.add(tagBadge)
            }
            
            detailsContent.add(tagsPanel)
        }
        
        // Display OpenAPI file source if available
        endpoint.openAPIFile?.let { file ->
            val filePanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            filePanel.isOpaque = false
            val fileName = file.substringAfterLast('/')
            val fileLabel = JBLabel("<html><b>Source:</b> <i>$fileName</i></html>")
            fileLabel.font = fileLabel.font.deriveFont(10f)
            fileLabel.foreground = JBColor.GRAY
            filePanel.add(fileLabel)
            detailsContent.add(filePanel)
        }
        
        endpointDetailsPanel.add(detailsContent)
        endpointDetailsPanel.isVisible = true
        endpointDetailsVisible = true
        
        revalidate()
        repaint()
    }
    
    /**
     * Hide endpoint details panel
     */
    private fun hideEndpointDetailsPanel() {
        endpointDetailsPanel.isVisible = false
        endpointDetailsVisible = false
        endpointDetailsPanel.removeAll()
        revalidate()
        repaint()
    }
    
    private fun buildRequestConfig(): RequestConfig {
        val method = methodComboBox.selectedItem as HttpMethod
        var url = enhancedUrlField.getText().trim()  // Use enhancedUrlField instead of urlTextField

        // Remove query parameters from URL if present
        // Query parameters will be handled separately via queryParameters field
        url = url.split("?")[0]

        // Apply environment base URL if environment is active and URL is relative
        val currentEnvironment = environmentService.getCurrentEnvironment()
        
        LoggingUtils.logDebug("Building request config - URL: $url, Environment: ${currentEnvironment?.name ?: "None"}")
        
        if (currentEnvironment != null && !isAbsoluteUrl(url)) {
            // Ensure base URL doesn't end with / and path starts with /
            val baseUrl = currentEnvironment.baseUrl.trimEnd('/')
            val path = if (url.startsWith('/')) url else "/$url"
            val originalUrl = url
            url = baseUrl + path
            
            LoggingUtils.logInfo("Applied environment base URL: $originalUrl -> $url (base: ${currentEnvironment.baseUrl})")
        } else if (currentEnvironment == null) {
            LoggingUtils.logWarning("No environment selected, using URL as-is: $url")
        } else {
            LoggingUtils.logDebug("URL is absolute, not applying environment base URL: $url")
        }
        
        val headers = getHeadersFromTable()
        
        // Merge environment global headers with request headers
        val finalHeaders = if (currentEnvironment != null) {
            currentEnvironment.globalHeaders.toMutableMap().apply {
                putAll(headers) // Request headers override global headers
            }
        } else {
            headers
        }
        // Get query and path parameters from panels
        val queryParams = queryParametersPanel.getParameters()
        val pathParams = pathParametersPanel.getParameters()
        
        // Determine body content based on body type
        var bodyContent: String? = null
        var formData: List<com.httppal.model.FormDataEntry>? = null
        
        when (bodyTypeComboBox.selectedItem as String) {
            "raw" -> {
                bodyContent = bodyEditor.document.text.takeIf { it.isNotBlank() }
            }
            "form-data" -> {
                // Convert FormDataPanel entries to FormDataEntry model
                formData = formDataPanel.getFormData().map { entry ->
                    if (entry.type == FormDataPanel.FormDataType.FILE) {
                        com.httppal.model.FormDataEntry.file(
                            key = entry.key,
                            filePath = entry.value,
                            contentType = entry.contentType
                        )
                    } else {
                        com.httppal.model.FormDataEntry.text(
                            key = entry.key,
                            value = entry.value
                        )
                    }
                }
            }
            // "none" leaves both null
        }
        
        val timeout = Duration.ofSeconds((timeoutSpinner.value as Int).toLong())
        val followRedirects = followRedirectsCheckBox.isSelected
        
        return RequestConfig(
            method = method,
            url = url,
            headers = finalHeaders,
            body = bodyContent,
            timeout = timeout,
            followRedirects = followRedirects,
            queryParameters = queryParams,
            pathParameters = pathParams,
            formData = formData
        )
    }
    
    /**
     * Check if URL is absolute (has protocol)
     */
    private fun isAbsoluteUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) || 
               url.startsWith("https://", ignoreCase = true)
    }
    
    /**
     * Check if path is a valid relative path
     */
    private fun isValidRelativePath(path: String): Boolean {
        // Valid relative paths start with / or are just path segments
        return path.matches(Regex("^[/]?[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]*$"))
    }
    
    private fun getHeadersFromTable(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val model = headersTable.model as DefaultTableModel
        
        for (row in 0 until model.rowCount) {
            val name = model.getValueAt(row, 0)?.toString()?.trim() ?: ""
            val value = model.getValueAt(row, 1)?.toString()?.trim() ?: ""
            
            if (name.isNotEmpty() && value.isNotEmpty()) {
                headers[name] = value
            }
        }
        
        return headers
    }
    
    private fun isValidUrlOrTemplate(url: String): Boolean {
        // Check if it's a valid URL
        try {
            java.net.URL(url)
            return true
        } catch (e: Exception) {
            // Not a valid absolute URL, check if it's a valid template or relative path
        }
        
        // Check if it's a valid URL template with path parameters
        val withoutParams = url.replace(Regex("\\{[^}]+\\}"), "placeholder")
        try {
            java.net.URL(withoutParams)
            return true
        } catch (e: Exception) {
            // Check if it's a valid relative path
            return url.matches(Regex("^[/]?[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=-]*$"))
        }
    }
    
    // Public API methods
    
    /**
     * Populate the form with endpoint information
     * Implements requirement 3.2: populate request form when endpoint is selected
     */
    fun populateFromEndpoint(endpoint: DiscoveredEndpoint) {
        // Store current endpoint
        currentEndpoint = endpoint
        
        // Update endpoint details panel
        updateEndpointDetailsPanel(endpoint)
        
        methodComboBox.selectedItem = endpoint.method
        enhancedUrlField.setText(endpoint.path)
        
        // Clear existing headers and add any endpoint-specific headers
        clearAllHeaders()
        
        // Separate parameters by type
        val pathParams = endpoint.parameters.filter { it.type == ParameterType.PATH }
        val queryParams = endpoint.parameters.filter { it.type == ParameterType.QUERY }
        val headerParams = endpoint.parameters.filter { it.type == ParameterType.HEADER }
        
        // Update Path Parameters Panel (clears existing and sets new parameters with full details)
        pathParametersPanel.setParametersList(pathParams)
        
        // Update Query Parameters Panel (clears existing and sets new parameters with full details)
        val uiQueryParams = queryParams.map { 
             com.httppal.ui.QueryParametersPanel.QueryParameter(
                 enabled = true,
                 key = it.name,
                 value = it.defaultValue ?: it.example ?: "",
                 description = it.description ?: ""
             )
        }
        queryParametersPanel.setParametersList(uiQueryParams)
        
        // We don't need to update path/query parameters from URL here
        // since setParametersList already sets all the parameter details from the endpoint
        // The URL field is already set, and path/query parameters will be applied when needed

        // Update Headers
        headerParams.forEach { param ->
             val model = headersTable.model as DefaultTableModel
             // Check if header already exists
             var exists = false
             for (i in 0 until model.rowCount) {
                 if (model.getValueAt(i, 0) == param.name) {
                     exists = true; break
                 }
             }
             if (!exists) {
                model.insertRow(0, arrayOf(param.name, param.defaultValue ?: param.example ?: ""))
             }
        }
        
        // Enable mock data button if endpoint has schema info
        mockDataButton.isEnabled = endpoint.schemaInfo != null || endpoint.parameters.isNotEmpty()
        
        // Set source
        requestSource = RequestSource.SCANNED_ENDPOINT
        toolbar.setRequestSource(requestSource)
        toolbar.setModified(false)
        originalRequest = null
        
        // Clear body content when switching endpoints
        // Use WriteCommandAction for safe editor modification
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            bodyEditor.document.setText("")
        }
        bodyTypeComboBox.selectedItem = "none"
        
        validateForm()
    }
    
    /**
     * Populate the form with request configuration
     * Implements requirement 2.1: allow specification of HTTP method, URL, headers, and request body
     */
    fun populateFromRequest(request: RequestConfig) {
        // Clear current endpoint and hide details panel
        currentEndpoint = null
        hideEndpointDetailsPanel()
        
        methodComboBox.selectedItem = request.method
        enhancedUrlField.setText(request.url)
        timeoutSpinner.value = request.timeout.seconds.toInt()
        followRedirectsCheckBox.isSelected = request.followRedirects
        
        // Populate path and query parameters panels
        pathParametersPanel.updateFromUrl(request.url)
        pathParametersPanel.setParameters(request.pathParameters)
        queryParametersPanel.syncFromUrl(request.url)
        // If we have specific query parameters (e.g. from history), apply them
        if (request.queryParameters.isNotEmpty()) {
            queryParametersPanel.setParameters(request.queryParameters)
        }
        
        // Set body based on type
        if (request.formData != null && request.formData!!.isNotEmpty()) {
            bodyTypeComboBox.selectedItem = "form-data"
            val uiEntries = request.formData!!.map { entry ->
                FormDataPanel.FormDataEntry(
                    enabled = true,
                    key = entry.key,
                    value = entry.value,
                    type = if (entry.isFile) FormDataPanel.FormDataType.FILE else FormDataPanel.FormDataType.TEXT,
                    contentType = entry.contentType
                )
            }
            formDataPanel.setFormData(uiEntries)
        } else if (request.body != null && request.body!!.isNotBlank()) {
            bodyTypeComboBox.selectedItem = "raw"
            // Use ApplicationManager to run document modification in write action
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    bodyEditor.document.setText(request.body!!)
                }
            }
        } else {
            bodyTypeComboBox.selectedItem = "none"
        }
        
        // Set headers
        val model = headersTable.model as DefaultTableModel
        model.rowCount = 0
        
        request.headers.forEach { (name, value) ->
            model.addRow(arrayOf(name, value))
        }
        
        // Add empty row for new headers
        model.addRow(arrayOf("", ""))
        
        // Disable mock data button when no endpoint is selected
        mockDataButton.isEnabled = false
        
        currentRequest = request
        validateForm()
    }
    
    /**
     * Get current request configuration
     */
    fun getCurrentRequest(): RequestConfig? {
        return if (validationErrors?.isEmpty() != false) {
            buildRequestConfig()
        } else {
            null
        }
    }
    
    /**
     * Set callback for send request action
     */
    fun setOnSendRequestCallback(callback: (RequestConfig) -> Unit) {
        onSendRequestCallback = callback
    }
    
    /**
     * Set callback for concurrent execution results
     */
    fun setOnConcurrentExecutionCallback(callback: (ConcurrentExecutionResult) -> Unit) {
        onConcurrentExecutionCallback = callback
    }
    
    /**
     * Set callback for cancel request action
     * Implements requirement 3.4: Allow external components to handle cancellation
     */
    fun setOnCancelRequestCallback(callback: () -> Unit) {
        onCancelRequestCallback = callback
    }
    
    /**
     * Notify that request has started (show cancel button)
     * Implements requirement 3.4: Show cancel button during request execution
     */
    fun notifyRequestStarted(executionId: String? = null) {
        currentExecutionId = executionId
        showRequestInProgress()
    }
    
    /**
     * Notify that request has completed (hide cancel button)
     * Implements requirement 3.4: Update UI state after completion
     */
    fun notifyRequestCompleted() {
        hideRequestInProgress()
        currentExecutionId = null
    }
    
    /**
     * Check if request is in progress
     */
    fun isRequestInProgress(): Boolean {
        return isRequestInProgress
    }
    
    /**
     * Get the concurrent execution panel for external access
     */
    fun getConcurrentExecutionPanel(): ConcurrentExecutionPanel {
        return concurrentExecutionPanel
    }
    
    /**
     * Enable or disable the form
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        methodComboBox.isEnabled = enabled
        enhancedUrlField.getTextField().isEnabled = enabled
        headersTable.isEnabled = enabled
        bodyEditor.isViewer = !enabled
        timeoutSpinner.isEnabled = enabled
        followRedirectsCheckBox.isEnabled = enabled
        // Only enable send button if form is enabled and validation passes, and concurrent execution is not enabled
        sendButton.isEnabled = enabled && (validationErrors?.isEmpty() != false) && !concurrentExecutionPanel.isConcurrentExecutionEnabled()
        exportButton.isEnabled = enabled && (validationErrors?.isEmpty() != false)
    }
    
    /**
     * Clear the form
     */
    fun clearForm() {
        methodComboBox.selectedItem = HttpMethod.GET
        enhancedUrlField.setText("")
        
        // Use ApplicationManager to run document modification in write action
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                bodyEditor.document.setText("")
            }
        }
        
        timeoutSpinner.value = 30
        followRedirectsCheckBox.isSelected = true
        clearAllHeaders()
        currentRequest = null
        validateForm()
    }
    
    /**
     * Get validation errors
     */
    fun getValidationErrors(): List<String> {
        return validationErrors?.toList() ?: emptyList()
    }
    
    /**
     * Check if form is valid
     */
    override fun isValid(): Boolean {
        // 使用安全调用，因为在构造期间此方法可能在字段初始化之前被调用
        return validationErrors?.isEmpty() ?: true
    }
    
    /**
     * Handle environment change
     * Updates URL field and validates form when environment changes
     */
    fun onEnvironmentChanged(environment: Environment?) {
        // Update URL field placeholder or validation based on environment
        val urlField = enhancedUrlField.getTextField()
        if (environment != null) {
            urlField.toolTipText = "Enter the request path (e.g., /api/users) - will be prefixed with ${environment.baseUrl}"
            urlField.emptyText.text = "/api/endpoint"
        } else {
            urlField.toolTipText = "Enter the full request URL (e.g., https://api.example.com/users)"
            urlField.emptyText.text = "https://api.example.com/endpoint"
        }
        
        // Only re-validate if the form has content (avoid validating empty form)
        if (enhancedUrlField.getText().isNotBlank()) {
            validateForm()
        }
    }
    
    /**
     * Show JMeter export dialog for current request
     * Implements requirements 11.1, 11.2, 11.5: export functionality with UI integration
     */
    private fun showJMeterExportDialog() {
        val currentRequest = getCurrentRequest()
        if (currentRequest == null) {
            ErrorHandler.handleValidationErrors(
                listOf(HttpPalBundle.message("error.configure.request.first")),
                this,
                HttpPalBundle.message("error.title.export")
            )
            return
        }
        
        // Get current environment from HttpPalService
        val currentEnvironment = httpPalService.getCurrentEnvironment()
        
        // Show export dialog
        val dialog = JMeterExportDialog(project, listOf(currentRequest), currentEnvironment)
        dialog.show()
    }
    
    private fun setupEndpointChangeListener() {
        val connection = project.messageBus.connect(project)
        // Subscribe to endpoint changes if using message bus
        // Or register directly with service if using listener pattern completely
        
        val endpointDiscoveryService = project.service<com.httppal.service.EndpointDiscoveryService>()
        val listener = object : com.httppal.service.EndpointChangeListener {
            override fun onEndpointsChanged(notification: com.httppal.service.EndpointChangeNotification) {
                // Check if current endpoint was modified
                val current = currentEndpoint ?: return
                
                // Find matching modified endpoint
                val modifiedEndpoint = notification.modifiedEndpoints.find { 
                    it.className == current.className && it.methodName == current.methodName 
                }
                
                if (modifiedEndpoint != null) {
                    ApplicationManager.getApplication().invokeLater {
                        // Only auto-update if we are in SCANNED_ENDPOINT mode and user hasn't heavily modified it
                        if (requestSource == RequestSource.SCANNED_ENDPOINT) {
                            // Notify user and update
                            populateFromEndpoint(modifiedEndpoint)
                            
                            com.httppal.util.VisualFeedbackHelper.showTemporaryStatus(
                                validationLabel,
                                "Endpoint updated from source code",
                                3000,
                                JBColor.BLUE
                            )
                        }
                    }
                }
            }
        }
        
        endpointDiscoveryService.registerEndpointChangeListener(listener)
        
        // Ensure listener is removed when panel is disposed? 
        // Since we don't have a clear "dispose" hook for JPanel easily without addNotify/removeNotify or Disposer...
        // For project-level service it's tricky.
        // Let's rely on standard swing hierarchy listener if possible or proper dispose.
        // However, this panel seems to live long.
    }

    // New methods for manual request creation
    
    /**
     * Create a new request, optionally from a template
     * Implements requirements 1.2, 1.3, 1.4, 1.5, 2.6
     */
    fun createNewRequest(template: RequestTemplate? = null) {
        // Clear the form
        clearForm()
        
        // Clear current endpoint and hide details panel
        currentEndpoint = null
        hideEndpointDetailsPanel()
        
        // Apply template if provided
        if (template != null) {
            applyTemplate(template)
        } else {
            // For blank request, set a default URL to make the form initially valid
            enhancedUrlField.setText("https://httpbin.org/get")
        }
        
        // Update source
        requestSource = RequestSource.MANUAL
        toolbar.setRequestSource(requestSource)
        toolbar.setModified(false)
        
        // Clear original request for modification detection
        originalRequest = null
        
        // Focus on URL field
        enhancedUrlField.requestFocusOnTextField()
    }
    
    /**
     * Apply a template to the current request
     * Implements requirement 2.6
     */
    private fun applyTemplate(template: RequestTemplate) {
        // Set method
        methodComboBox.selectedItem = template.method
        
        // Set URL if provided
        if (!template.urlTemplate.isNullOrBlank()) {
            enhancedUrlField.setText(template.urlTemplate)
        }
        
        // Set headers
        val model = headersTable.model as DefaultTableModel
        model.rowCount = 0
        
        template.headers.forEach { (name, value) ->
            model.addRow(arrayOf(name, value))
        }
        
        // Add empty row for new headers
        model.addRow(arrayOf("", ""))
        
        // Set body if provided
        if (!template.body.isNullOrBlank()) {
            // Use ApplicationManager to run document modification in write action
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    bodyEditor.document.setText(template.body)
                }
            }
        }
        
        validateForm()
    }
    
    /**
     * Show quick create dialog
     * Implements requirements 4.1, 4.2, 4.3, 4.4, 4.5
     */
    private fun showQuickCreateDialog() {
        val dialog = QuickCreateDialog(project) { config ->
            // Apply the configuration from quick create dialog
            populateFromRequest(config)
            requestSource = RequestSource.MANUAL
            toolbar.setRequestSource(requestSource)
            toolbar.setModified(false)
            originalRequest = null
        }
        dialog.show()
    }
    
    /**
     * Import request from clipboard
     * Implements requirements 5.1, 5.2, 5.3, 5.4, 5.5
     */
    private fun importFromClipboard() {
        try {
            val result = curlParserService.importFromClipboard()
            
            when (result) {
                is ParseResult.Success -> {
                    // Apply the parsed configuration
                    populateFromRequest(result.data)
                    requestSource = RequestSource.MANUAL
                    toolbar.setRequestSource(requestSource)
                    toolbar.setModified(false)
                    originalRequest = null
                    
                    // Add URL to history
                    enhancedUrlField.addToHistory(result.data.url)
                    
                    // Ensure send button is updated based on validation status
                    validateForm()
                    
                    // Show success message
                    JOptionPane.showMessageDialog(
                        this,
                        HttpPalBundle.message("import.clipboard.success"),
                        HttpPalBundle.message("import.clipboard.title"),
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
                is ParseResult.Failure -> {
                    // Show error message
                    ErrorHandler.handleValidationErrors(
                        result.errors,
                        this,
                        HttpPalBundle.message("import.clipboard.error.title")
                    )
                }
            }
        } catch (e: Exception) {
            ErrorHandler.handleError(
                message = "Import from clipboard failed",
                cause = e,
                project = project,
                component = this
            )
        }
    }
    
    /**
     * Import request from Postman
     * Implements requirements 5.1, 5.2, 5.3, 5.4, 5.5
     */
    private fun importFromPostman() {
        try {
            val fileChooser = JFileChooser()
            fileChooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Postman Collection", "json")
            
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                val postmanService = project.getService(com.httppal.service.PostmanImportService::class.java)
                
                // Parse collection first
                val json = fileChooser.selectedFile.readText()
                val parseResult = postmanService.parsePostmanCollection(json)
                
                if (parseResult is com.httppal.service.ParseResult.Failure) {
                    ErrorHandler.handleValidationErrors(
                        parseResult.errors,
                        this,
                        HttpPalBundle.message("import.postman.error.title")
                    )
                    return
                }
                
                val collection = (parseResult as ParseResult.Success).data
                
                // Show preview dialog
                val dialog = PostmanImportDialog(project, collection) { options ->
                    // Import after user confirms in the dialog with selected options
                    SwingUtilities.invokeLater {
                        try {
                            val result = postmanService.importFromPostman(fileChooser.selectedFile.absolutePath, options)
                            
                            if (result is com.httppal.service.ImportResult && result.success) {
                                // Show success message
                                JOptionPane.showMessageDialog(
                                    this,
                                    HttpPalBundle.message("import.postman.success", result.importedCount),
                                    HttpPalBundle.message("import.postman.title"),
                                    JOptionPane.INFORMATION_MESSAGE
                                )
                            } else {
                                // Handle case where result is not ImportResult or success is false
                                val errorMessage = if (result is com.httppal.service.ImportResult) {
                                    result.errors.joinToString("\n")
                                } else {
                                    "Unknown error occurred during import"
                                }
                                ErrorHandler.handleValidationErrors(
                                    listOf(errorMessage),
                                    this,
                                    HttpPalBundle.message("import.postman.error.title")
                                )
                            }
                        } catch (e: Exception) {
                            ErrorHandler.handleError(
                                message = "Import from Postman failed",
                                cause = e,
                                project = project,
                                component = this
                            )
                        }
                    }
                }
                
                dialog.show()
            }
        } catch (e: Exception) {
            ErrorHandler.handleError(
                message = "Import from Postman failed",
                cause = e,
                project = project,
                component = this
            )
        }
    }
    
    /**
     * Detect modifications to the request
     * Implements requirement 11.5
     */
    private fun detectModifications() {
        if (originalRequest == null) {
            toolbar.setModified(false)
            return
        }
        
        val currentConfig = try {
            buildRequestConfig()
        } catch (e: Exception) {
            // If we can't build config, consider it modified
            toolbar.setModified(true)
            return
        }
        
        val isModified = currentConfig != originalRequest
        toolbar.setModified(isModified)
    }
    
    /**
     * Load request from history
     * Implements requirements 7.1, 7.2, 7.3
     */
    fun loadFromHistory(entry: RequestHistoryEntry) {
        // Extract RequestConfig from history entry
        val config = entry.request // Use the request property directly instead of reconstructing
        
        // Populate form
        populateFromRequest(config)
        
        // Set source
        requestSource = RequestSource.HISTORY
        toolbar.setRequestSource(requestSource)
        toolbar.setModified(false)
        
        // Save original request for modification detection
        originalRequest = config
        
        // Add URL to history
        enhancedUrlField.addToHistory(config.url)
    }
    
    /**
     * Save current request as favorite
     * Implements requirements 6.1, 6.2, 6.3, 6.4, 6.5
     */
    fun saveAsFavorite() {
        val currentConfig = getCurrentRequest()
        if (currentConfig == null) {
            ErrorHandler.handleValidationErrors(
                listOf(HttpPalBundle.message("error.configure.request.first")),
                this,
                HttpPalBundle.message("error.title.save.favorite")
            )
            return
        }
        
        // Show dialog to input name, folder and description
        val nameField = JTextField(20)
        val descriptionField = JTextArea(3, 20)
        val folderCombo = JComboBox<String>()
        
        descriptionField.lineWrap = true
        descriptionField.wrapStyleWord = true
        
        // Load folders
        val favoritesService = service<FavoritesService>()
        val folders = favoritesService.getAllFolders()
        folderCombo.addItem("") // Empty option for uncategorized
        folders.forEach { folder -> folderCombo.addItem(folder) }
        
        // Default name: METHOD + URL
        nameField.text = "${currentConfig.method.name} ${currentConfig.url}"
        
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.anchor = GridBagConstraints.WEST
        
        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JBLabel(HttpPalBundle.message("favorite.name.label")), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(nameField, gbc)
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel(HttpPalBundle.message("favorites.dialog.folder.label")), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(folderCombo, gbc)
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel(HttpPalBundle.message("favorite.description.label")), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0
        panel.add(JScrollPane(descriptionField), gbc)
        
        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            HttpPalBundle.message("favorite.save.title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        
        if (result == JOptionPane.OK_OPTION) {
            val name = nameField.text.trim().ifBlank { "${currentConfig.method.name} ${currentConfig.url}" }
            val description = descriptionField.text.trim().ifBlank { null }
            
            try {
                // Get FavoritesService
                val favoritesService = service<FavoritesService>()
                
                // Create favorite from current request
                val selectedFolder = folderCombo.selectedItem as? String
                val folder = if (selectedFolder.isNullOrBlank()) null else selectedFolder
                
                val favorite = favoritesService.createFavoriteFromRequest(
                    request = currentConfig,
                    name = name,
                    folder = folder
                )
                
                // Save favorite
                val saved = favoritesService.addFavorite(favorite)
                
                if (saved) {
                    // Show success message
                    JOptionPane.showMessageDialog(
                        this,
                        HttpPalBundle.message("favorite.save.success", name),
                        HttpPalBundle.message("favorite.save.title"),
                        JOptionPane.INFORMATION_MESSAGE
                    )
                    
                    // Log success
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.INFO,
                        "Favorite saved successfully",
                        mapOf("name" to name, "url" to currentConfig.url)
                    )
                } else {
                    // Show error message
                    ErrorHandler.handleValidationErrors(
                        listOf(HttpPalBundle.message("favorite.save.error", "Unknown error")),
                        this,
                        HttpPalBundle.message("error.title.save.favorite")
                    )
                }
            } catch (e: Exception) {
                ErrorHandler.handleError(
                    message = "Save favorite failed",
                    cause = e,
                    project = project,
                    component = this
                )
            }
        }
    }
    
    /**
     * Generate and fill mock data based on current endpoint schema
     * Implements Task 12.1 and 12.2: Generate mock data and fill form fields
     */
    private fun generateAndFillMockData() {
        val endpoint = currentEndpoint
        if (endpoint == null) {
            ErrorHandler.handleValidationErrors(
                listOf("No endpoint selected. Please select an endpoint first."),
                this,
                "Mock Data Generation Error"
            )
            return
        }
        
        try {
            // Generate mock data using MockDataGeneratorService
            val mockData = mockDataGeneratorService.generateMockRequest(endpoint, endpoint.schemaInfo)
            
            // 在 EDT 线程中更新 UI
            ApplicationManager.getApplication().invokeLater {
                // Populate path parameters panel
                pathParametersPanel.updateFromUrl(endpoint.path)
                pathParametersPanel.setParameters(mockData.pathParameters)
                
                // Populate query parameters panel
                queryParametersPanel.setParameters(mockData.queryParameters)
                
                // Update URL (applying path and query params)
                var url = endpoint.path
                url = pathParametersPanel.applyToUrl(url)
                url = queryParametersPanel.appendToUrl(url)
                enhancedUrlField.setText(url)
                
                // Fill headers
                val model = headersTable.model as DefaultTableModel
                model.rowCount = 0
                
                mockData.headers.forEach { (name, value) ->
                    model.addRow(arrayOf(name, value))
                }
                
                // Add empty row for new headers
                model.addRow(arrayOf("", ""))
                
                // 强制刷新表格
                headersTable.revalidate()
                headersTable.repaint()
                
                // Fill request body if available
                if (mockData.body != null && mockData.body!!.isNotBlank()) {
                    bodyTypeComboBox.selectedItem = "raw"
                    // Format JSON for better readability
                    val formattedBody = try {
                        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                        val jsonNode = mapper.readTree(mockData.body!!)
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode)
                    } catch (t: Throwable) {
                        mockData.body!!
                    }
                    
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        // Ensure standardized line separators (LF) for IntelliJ Document
                        val normalizedBody = com.intellij.openapi.util.text.StringUtil.convertLineSeparators(formattedBody)
                        bodyEditor.document.setText(normalizedBody)
                    }
                } else {
                    bodyTypeComboBox.selectedItem = "none"
                }
                
                // 强制刷新整个面板
                revalidate()
                repaint()
                
                // Show success feedback with details
                val detailsMessage = buildString {
                    append("Mock data generated successfully!\n")
                    if (mockData.pathParameters.isNotEmpty()) {
                        append("• Path params: ${mockData.pathParameters.size}\n")
                    }
                    if (mockData.queryParameters.isNotEmpty()) {
                        append("• Query params: ${mockData.queryParameters.size}\n")
                    }
                    if (mockData.headers.isNotEmpty()) {
                        append("• Headers: ${mockData.headers.size}\n")
                    }
                    if (mockData.body != null) {
                        append("• Request body: Generated")
                    }
                }
                
                com.httppal.util.VisualFeedbackHelper.showSuccessFeedback(mockDataButton as JComponent)
                com.httppal.util.VisualFeedbackHelper.showTemporaryStatus(
                    validationLabel,
                    detailsMessage.trim(),
                    3000,
                    Color(0, 150, 0)
                )
                
                // Validate form after filling
                validateForm()
                
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.INFO,
                    "Mock data generated and filled successfully",
                    mapOf(
                        "endpoint" to endpoint.path,
                        "method" to endpoint.method.name,
                        "hasBody" to (mockData.body != null),
                        "headerCount" to mockData.headers.size,
                        "pathParamCount" to mockData.pathParameters.size,
                        "queryParamCount" to mockData.queryParameters.size
                    )
                )
            }
        } catch (e: Exception) {
            ErrorHandler.handleError(
                message = "Failed to generate mock data",
                cause = e,
                project = project,
                component = this
            )
            
            ApplicationManager.getApplication().invokeLater {
                com.httppal.util.VisualFeedbackHelper.showErrorFeedback(
                    mockDataButton as JComponent,
                    "Failed to generate mock data: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Setup keyboard shortcuts for common actions
     * Implements requirements 10.1, 10.2, 10.3, 10.4, 10.5
     */
    private fun setupKeyboardShortcuts() {
        // Get the appropriate modifier key for the platform
        val menuShortcutKeyMask = if (SystemInfo.isMac) {
            java.awt.event.InputEvent.META_DOWN_MASK
        } else {
            java.awt.event.InputEvent.CTRL_DOWN_MASK
        }
        
        // Ctrl+N / Cmd+N: New request
        registerKeyboardAction(
            { createNewRequest() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, menuShortcutKeyMask),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        
        // Ctrl+Enter / Cmd+Enter: Send request
        registerKeyboardAction(
            { sendRequest() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, menuShortcutKeyMask),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        
        // Ctrl+S / Cmd+S: Save as favorite
        registerKeyboardAction(
            { saveAsFavorite() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, menuShortcutKeyMask),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        
        // Ctrl+Shift+V / Cmd+Shift+V: Import from clipboard
        registerKeyboardAction(
            { importFromClipboard() },
            KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_V,
                menuShortcutKeyMask or java.awt.event.InputEvent.SHIFT_DOWN_MASK
            ),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
        
        // Ctrl+Space: URL auto-complete (in URL field)
        enhancedUrlField.getTextField().registerKeyboardAction(
            { 
                // Trigger auto-complete manually
                val text = enhancedUrlField.getText()
                if (text.length >= 3) {
                    // The EnhancedUrlField will handle showing auto-complete
                    enhancedUrlField.getTextField().requestFocus()
                }
            },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_SPACE, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_FOCUSED
        )
        
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.INFO,
            "Keyboard shortcuts registered",
            mapOf(
                "platform" to if (SystemInfo.isMac) "Mac" else "Windows/Linux",
                "shortcuts" to listOf("Ctrl+N", "Ctrl+Enter", "Ctrl+S", "Ctrl+Shift+V", "Ctrl+Space")
            )
        )
    }
}