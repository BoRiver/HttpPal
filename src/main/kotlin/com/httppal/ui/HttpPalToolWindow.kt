package com.httppal.ui

import com.httppal.model.*
import com.httppal.service.HttpPalService
import com.httppal.service.EndpointDiscoveryService
import com.httppal.service.RequestExecutionService
import com.httppal.settings.LayoutPersistenceImpl
import com.httppal.settings.UILayout
import com.httppal.util.ErrorHandler.handleError
import com.httppal.util.HttpPalBundle
import com.httppal.util.LoggingUtils
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Main tool window for HttpPal plugin with tabbed interface and request/response panels
 */
class HttpPalToolWindow(private val project: Project) {
    
    private val tabbedPane = JBTabbedPane()
    private val httpPalService = service<HttpPalService>()
    private val endpointDiscoveryService = project.service<EndpointDiscoveryService>()
    private val layoutPersistence = LayoutPersistenceImpl.getInstance(project)
    private val autoLoadManager = project.service<com.httppal.service.AutoLoadManager>()
    
    // UI Components
    private lateinit var requestConfigPanel: RequestConfigurationPanel
    private lateinit var responseDisplayPanel: ResponseDisplayPanel
    private lateinit var webSocketPanel: WebSocketPanel
    private lateinit var environmentSelectionPanel: EnvironmentSelectionPanel
    private lateinit var endpointTreePanel: EndpointTreePanel
    
    // Split panes for layout persistence
    private lateinit var mainSplitPane: JSplitPane
    private lateinit var requestResponseSplitPane: JSplitPane
    
    // UI State Management
    private var currentRequest: RequestConfig? = null
    private var currentResponse: HttpResponse? = null
    private var isRequestInProgress = false
    private var currentExecutionId: String? = null
    private var currentRequestJob: kotlinx.coroutines.Job? = null
    
    // UI Components for state management
    private val statusLabel = JBLabel("Ready")
    private val progressBar = JProgressBar()
    
    init {
        initializeComponents()
        setupUIState()
        loadAndApplyLayout()
        setupAutoLoadIntegration()
    }
    
    private fun initializeComponents() {
        // Request tab - main HTTP request interface with request/response sections
        val requestPanel = createRequestPanel()
        tabbedPane.addTab("Request", requestPanel)

        // GraphQL tab
        val graphQLPanel = createGraphQLPanel()
        tabbedPane.addTab("GraphQL", graphQLPanel)

        // WebSocket tab
        webSocketPanel = WebSocketPanel(project)
        tabbedPane.addTab("WebSocket", webSocketPanel)
        
        // History & Favorites tab (combined for better UX)
        val historyFavoritesPanel = createHistoryFavoritesPanel()
        tabbedPane.addTab("History & Favorites", historyFavoritesPanel)
        
        // Environments tab
        val environmentsPanel = createEnvironmentsPanel()
        tabbedPane.addTab("Environments", environmentsPanel)
        
        // Add navigation state management
        tabbedPane.addChangeListener { 
            updateUIState()
            saveCurrentLayout()
        }
    }
    
    private fun createRequestPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        
        // Top panel with environment selection and toolbar
        val topPanel = JPanel(BorderLayout())
        
        // Environment selection
        environmentSelectionPanel = EnvironmentSelectionPanel(project)
        environmentSelectionPanel.setOnEnvironmentChangeCallback { environment ->
            // Update request configuration panel when environment changes
            requestConfigPanel.onEnvironmentChanged(environment)
            // Update endpoint tree panel when environment changes (requirement 3.9)
            endpointTreePanel.onEnvironmentChanged(environment)
            statusLabel.text = if (environment != null) {
                HttpPalBundle.message("status.environment.switched", environment.name)
            } else {
                HttpPalBundle.message("status.environment.deactivated")
            }
        }
        topPanel.add(environmentSelectionPanel, BorderLayout.CENTER)
        
        // Toolbar with export actions
        val toolbar = createToolbar()
        topPanel.add(toolbar, BorderLayout.EAST)
        
        mainPanel.add(topPanel, BorderLayout.NORTH)
        
        // Create main split pane with endpoint tree on the left
        mainSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        mainSplitPane.resizeWeight = 0.25 // Give 25% to endpoint tree
        
        // Add listener to save layout when split pane is adjusted
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { 
            saveCurrentLayout()
        }
        
        // Left side: Endpoint discovery tree
        endpointTreePanel = EndpointTreePanel(project)
        endpointTreePanel.setOnEndpointSelectedCallback { endpoint ->
            populateRequestForm(endpoint)
        }
        endpointTreePanel.setOnRefreshCallback {
            statusLabel.text = "Endpoints refreshed"
        }
        mainSplitPane.leftComponent = endpointTreePanel
        
        // Right side: Request/Response split pane
        requestResponseSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        requestResponseSplitPane.resizeWeight = 0.6 // Give more space to request configuration
        
        // Add listener to save layout when split pane is adjusted
        requestResponseSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            saveCurrentLayout()
        }
        
        // Request Configuration Section
        val requestConfigPanel = createRequestConfigurationSection()
        requestResponseSplitPane.topComponent = requestConfigPanel
        
        // Response Display Section
        val responseDisplayPanel = createResponseDisplaySection()
        requestResponseSplitPane.bottomComponent = responseDisplayPanel
        
        mainSplitPane.rightComponent = requestResponseSplitPane
        
        mainPanel.add(mainSplitPane, BorderLayout.CENTER)
        
        // Status bar at bottom
        val statusPanel = createStatusPanel()
        mainPanel.add(statusPanel, BorderLayout.SOUTH)
        
        return mainPanel
    }
    
    private fun createRequestConfigurationSection(): JComponent {
        requestConfigPanel = RequestConfigurationPanel(project)
        
        // Set up callback for sending requests
        requestConfigPanel.setOnSendRequestCallback { request ->
            sendRequest(request)
        }
        
        // Set up callback for concurrent execution results
        requestConfigPanel.setOnConcurrentExecutionCallback { result ->
            displayConcurrentExecutionResults(result)
        }
        
        // Set up callback for cancelling requests
        // Implements requirement 3.4: Handle request cancellation
        requestConfigPanel.setOnCancelRequestCallback {
            cancelCurrentRequest()
        }
        
        return requestConfigPanel
    }
    
    private fun createResponseDisplaySection(): JComponent {
        responseDisplayPanel = ResponseDisplayPanel()
        return responseDisplayPanel
    }

    private fun createGraphQLPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        // Top panel with environment selection
        val topPanel = JPanel(BorderLayout())
        val graphQLEnvSelectionPanel = EnvironmentSelectionPanel(project)
        topPanel.add(graphQLEnvSelectionPanel, BorderLayout.CENTER)
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Three-column layout: Schema Explorer | Request Editor | Response Viewer
        val leftSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        leftSplitPane.resizeWeight = 0.2

        // Left: Schema Explorer
        val schemaExplorer = com.httppal.graphql.ui.GraphQLSchemaExplorer(project)

        // Middle: GraphQL request panel
        val graphQLRequestPanel = com.httppal.graphql.ui.GraphQLPanel(project)

        // Right: GraphQL response panel
        val graphQLResponsePanel = com.httppal.graphql.ui.GraphQLResponsePanel(project)

        // Set up callbacks
        graphQLRequestPanel.setOnExecuteCallback { response ->
            graphQLResponsePanel.displayResponse(response)
        }

        graphQLRequestPanel.setOnIntrospectCallback { endpoint ->
            introspectGraphQLSchema(endpoint, schemaExplorer)
        }

        // Bidirectional sync: Schema Explorer → Query Editor
        schemaExplorer.setOnQueryUpdatedCallback { query ->
            graphQLRequestPanel.setQuerySilently(query)
        }

        // Bidirectional sync: Query Editor → Schema Explorer (with debouncing)
        var syncJob: kotlinx.coroutines.Job? = null
        graphQLRequestPanel.getQueryEditor().addDocumentListener { queryText ->
            // Cancel previous sync job
            syncJob?.cancel()
            // Schedule new sync with 500ms debounce
            syncJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                kotlinx.coroutines.delay(500)
                SwingUtilities.invokeLater {
                    schemaExplorer.syncFromQuery(queryText)
                }
            }
        }

        leftSplitPane.leftComponent = schemaExplorer
        leftSplitPane.rightComponent = graphQLRequestPanel

        val mainSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        mainSplitPane.resizeWeight = 0.6
        mainSplitPane.leftComponent = leftSplitPane
        mainSplitPane.rightComponent = graphQLResponsePanel

        mainPanel.add(mainSplitPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun introspectGraphQLSchema(endpoint: String, schemaExplorer: com.httppal.graphql.ui.GraphQLSchemaExplorer) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val schemaService = service<com.httppal.graphql.service.GraphQLSchemaService>()
                val schema = schemaService.introspectSchema(endpoint)

                SwingUtilities.invokeLater {
                    if (schema != null) {
                        statusLabel.text = "Schema 获取成功 (${schema.types.size} 个类型)"
                        schemaExplorer.loadSchema(endpoint)
                        JOptionPane.showMessageDialog(
                            getContent(),
                            "Schema 获取成功！\n找到 ${schema.types.size} 个类型。",
                            "成功",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    } else {
                        statusLabel.text = "Schema 获取失败"
                        JOptionPane.showMessageDialog(
                            getContent(),
                            "Schema introspected types found.",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    } /*else {
                        statusLabel.text = "Schema introspection failed"
                        JOptionPane.showMessageDialog(
                            getContent(),
                            "Failed to introspect schema. Check the endpoint URL and try again.",
                            "Introspection Failed",
                            JOptionPane.WARNING_MESSAGE
                        )
                    }*/
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Schema introspection error"
                    JOptionPane.showMessageDialog(
                        getContent(),
                        "Error during introspection: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }


    
    private fun createToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT))
        toolbar.border = JBUI.Borders.empty(5)
        
        // Export current request button
        val exportCurrentButton = JButton(HttpPalBundle.message("button.export.current"))
        exportCurrentButton.toolTipText = HttpPalBundle.message("tooltip.export.current")
        exportCurrentButton.addActionListener {
            val currentRequest = requestConfigPanel.getCurrentRequest()
            if (currentRequest != null) {
                showJMeterExportDialog(listOf(currentRequest))
            } else {
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    HttpPalBundle.message("error.configure.request.first"),
                    HttpPalBundle.message("dialog.no.requests.export.title")
                )
            }
        }
        toolbar.add(exportCurrentButton)
        
        // Export favorites button
        val exportFavoritesButton = JButton(HttpPalBundle.message("button.export.favorites"))
        exportFavoritesButton.toolTipText = HttpPalBundle.message("tooltip.export.favorites")
        exportFavoritesButton.addActionListener {
            val favorites = httpPalService.getFavorites()
            if (favorites.isNotEmpty()) {
                showJMeterExportDialog(favorites.map { it.request })
            } else {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    HttpPalBundle.message("error.no.favorites"),
                    HttpPalBundle.message("dialog.no.favorites.export.title")
                )
            }
        }
        toolbar.add(exportFavoritesButton)
        
        return toolbar
    }
    
    private fun createStatusPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)
        
        // Status label on the left
        panel.add(statusLabel, BorderLayout.WEST)
        
        // Progress bar on the right (initially hidden)
        progressBar.isVisible = false
        panel.add(progressBar, BorderLayout.EAST)
        
        return panel
    }
    

    
    private fun createHistoryFavoritesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Create tabbed pane for history and favorites
        val historyFavoritesTabs = JBTabbedPane()
        
        // History panel
        val historyPanel = HistoryPanel(project, statusLabel)
        historyPanel.setOnLoadRequestCallback { entry ->
            // Switch to Request tab and load the request
            tabbedPane.selectedIndex = 0
            requestConfigPanel.loadFromHistory(entry)
            statusLabel.text = "Loaded from history: ${entry.request.method} ${entry.request.url}"
        }
        historyFavoritesTabs.addTab("History", historyPanel)
        
        // Favorites panel
        val favoritesPanel = FavoritesPanel(project)
        favoritesPanel.setOnLoadRequestCallback { favorite ->
            // Switch to Request tab and load the request
            tabbedPane.selectedIndex = 0
            requestConfigPanel.populateFromRequest(favorite.request)
            statusLabel.text = "Loaded from favorites: ${favorite.name}"
        }
        favoritesPanel.setOnAddCurrentCallback {
            // Add current request to favorites
            val currentRequest = requestConfigPanel.getCurrentRequest()
            if (currentRequest != null) {
                showAddFavoriteDialog(currentRequest, favoritesPanel)
            } else {
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    HttpPalBundle.message("error.configure.request.first"),
                    HttpPalBundle.message("dialog.no.requests.export.title")
                )
            }
        }
        historyFavoritesTabs.addTab("Favorites", favoritesPanel)
        
        panel.add(historyFavoritesTabs, BorderLayout.CENTER)
        return panel
    }
    
    private fun showAddFavoriteDialog(request: com.httppal.model.RequestConfig, favoritesPanel: FavoritesPanel) {
        val nameField = JBTextField()
        val folderCombo = JComboBox<String>()
        
        // Load folders
        val favoritesService = service<com.httppal.service.FavoritesService>()
        val folders = favoritesService.getAllFolders()
        folderCombo.addItem("")
        folders.forEach { folderCombo.addItem(it) }
        
        val panel = JPanel(GridLayout(2, 2, 5, 5))
        panel.add(JBLabel(HttpPalBundle.message("favorites.dialog.name.label")))
        panel.add(nameField)
        panel.add(JBLabel(HttpPalBundle.message("favorites.dialog.folder.label")))
        panel.add(folderCombo)
        
        val result = JOptionPane.showConfirmDialog(
            null,
            panel,
            HttpPalBundle.message("favorites.dialog.add.title"),
            JOptionPane.OK_CANCEL_OPTION
        )
        
        if (result == JOptionPane.OK_OPTION) {
            val name = nameField.text
            if (name.isBlank()) {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    HttpPalBundle.message("favorites.dialog.name.required"),
                    HttpPalBundle.message("error.title.validation")
                )
                return
            }
            
            try {
                val folder = folderCombo.selectedItem as? String
                val favorite = favoritesService.createFavoriteFromRequest(
                    request,
                    name,
                    if (folder.isNullOrBlank()) null else folder
                )
                favoritesService.addFavorite(favorite)
                favoritesPanel.refresh()
                
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    HttpPalBundle.message("success.favorite.added", name),
                    HttpPalBundle.message("dialog.success")
                )
            } catch (e: Exception) {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    HttpPalBundle.message("error.favorite.add.failed", e.message ?: "Unknown error"),
                    HttpPalBundle.message("error.title.general")
                )
            }
        }
    }
    
    private fun createEnvironmentsPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        
        // Title
        val titleLabel = JBLabel(HttpPalBundle.message("toolwindow.environment.title"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, BorderLayout.NORTH)
        
        // Environment selection panel (same as in request tab)
        val envSelectionPanel = EnvironmentSelectionPanel(project)
        envSelectionPanel.setOnEnvironmentChangeCallback { environment ->
            // Update request configuration panel when environment changes
            requestConfigPanel.onEnvironmentChanged(environment)
            statusLabel.text = if (environment != null) {
                HttpPalBundle.message("status.environment.switched", environment.name)
            } else {
                HttpPalBundle.message("status.environment.deactivated")
            }
        }
        panel.add(envSelectionPanel, BorderLayout.CENTER)
        
        // Info panel
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        infoPanel.border = JBUI.Borders.empty(20, 0, 0, 0)
        
        val infoLabel = JBLabel("<html>" + HttpPalBundle.message("toolwindow.environment.info") + "</html>")
        infoLabel.foreground = Color.GRAY
        infoPanel.add(infoLabel)
        
        panel.add(infoPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun setupUIState() {
        updateUIState()
    }
    
    private fun updateUIState() {
        // Update status based on current state
        when {
            isRequestInProgress -> {
                statusLabel.text = "Request in progress..."
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
            }
            currentResponse != null -> {
                statusLabel.text = "Response received (${currentResponse?.statusCode})"
                progressBar.isVisible = false
            }
            else -> {
                statusLabel.text = HttpPalBundle.message("status.ready")
                progressBar.isVisible = false
            }
        }
    }
    
    /**
     * Send HTTP request with async UI updates
     * Implements requirement 3.1: Time-consuming operations in background threads
     * Implements requirement 3.4: Add progress indicators
     */
    private fun sendRequest(request: RequestConfig) {
        // Store the current request
        currentRequest = request
        
        // Generate execution ID for tracking
        currentExecutionId = "exec_${System.currentTimeMillis()}"
        
        // Notify request panel that request has started
        requestConfigPanel.notifyRequestStarted(currentExecutionId)
        
        // Show loading state in response panel (on EDT)
        com.httppal.util.AsyncUIHelper.invokeLater {
            showResponseLoading()
        }
        
        // Execute request in background thread
        // Implements requirement 3.1: All time-consuming operations in background threads
        com.httppal.util.AsyncUIHelper.executeWithProgress(
            project = project,
            title = "Sending HTTP Request",
            canBeCancelled = true,
            backgroundTask = { indicator ->
                indicator.text = "Sending ${request.method} request to ${request.url}"
                indicator.fraction = 0.3
                
                // Execute the actual request
                val requestExecutionService = service<RequestExecutionService>()
                val response = kotlinx.coroutines.runBlocking {
                    requestExecutionService.executeRequest(request)
                }
                
                indicator.fraction = 1.0
                response
            },
            onSuccess = { response ->
                // Update UI on EDT thread
                // Implements requirement 3.1: Use invokeLater to update UI
                displayResponse(response)
                requestConfigPanel.notifyRequestCompleted()
                currentExecutionId = null
            },
            onError = { error ->
                // Handle error on EDT thread
                com.httppal.util.AsyncUIHelper.invokeLater {
                    hideRequestProgress()
                    requestConfigPanel.notifyRequestCompleted()
                    statusLabel.text = "Request failed: ${error.message}"
                    handleError("Request Execution Failed", error, project)
                    currentExecutionId = null
                }
            }
        )
    }
    
    /**
     * Cancel the current request
     * Implements requirement 3.4: Implement cancel logic
     */
    private fun cancelCurrentRequest() {
        // Cancel the request using the execution service
        currentExecutionId?.let { executionId ->
            val requestExecutionService = service<RequestExecutionService>()
            val cancelled = requestExecutionService.cancelExecution(executionId)
            
            if (cancelled) {
                com.httppal.util.AsyncUIHelper.invokeLater {
                    hideRequestProgress()
                    requestConfigPanel.notifyRequestCompleted()
                statusLabel.text = HttpPalBundle.message("status.request.cancelled")
                    responseDisplayPanel.clearResponse()
                    
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.INFO,
                        "Request cancelled by user",
                        mapOf("executionId" to executionId)
                    )
                }
            }
        }
        
        // Cancel the coroutine job if it exists
        currentRequestJob?.cancel()
        currentRequestJob = null
        currentExecutionId = null
    }
    
    /**
     * Setup integration with AutoLoadManager
     * Implements requirement 1.1: Trigger auto-load when ToolWindow opens
     * Implements requirement 1.3: Display loading state
     * Implements requirement 1.4: Update UI after load completion
     * Implements requirement 1.5: Handle loading errors
     * Implements requirement 11: No-endpoint usability
     */
    private fun setupAutoLoadIntegration() {
        // Register loading state listener to show/hide loading indicators
        autoLoadManager.addLoadingStateListener(object : com.httppal.service.AutoLoadManager.LoadingStateListener {
            override fun onLoadingStarted(message: String) {
                // Show loading state in endpoint tree panel
                endpointTreePanel.showLoadingState(message)
                statusLabel.text = message
                progressBar.isVisible = true
                progressBar.isIndeterminate = true
            }
            
            override fun onLoadingCompleted() {
                // Hide loading state
                endpointTreePanel.hideLoadingState()
                progressBar.isVisible = false
                statusLabel.text = "Ready"
            }
            
            override fun onLoadingFailed(error: String) {
                // Hide loading state and show empty state (not error)
                // Implements requirement 11: Plugin usable without endpoints
                endpointTreePanel.showEmptyState()
                progressBar.isVisible = false
                statusLabel.text = HttpPalBundle.message("status.no.endpoints")
                statusLabel.foreground = Color.GRAY // Informational, not error
                
                // Log error but don't show error dialog
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.WARN,
                    "Endpoint discovery failed: $error",
                    mapOf("error" to error)
                )
            }
        })
        
        // Register endpoint load listener to update UI
        autoLoadManager.addEndpointLoadListener(object : com.httppal.service.AutoLoadManager.EndpointLoadListener {
            override fun onEndpointsLoaded(endpoints: List<com.httppal.model.DiscoveredEndpoint>) {
                // Endpoints are already updated in EndpointTreePanel via its own listener
                // Just update status
                if (endpoints.isEmpty()) {
                    // Show empty state if no endpoints found
                    endpointTreePanel.showEmptyState()
                    statusLabel.text = HttpPalBundle.message("status.no.endpoints")
                    statusLabel.foreground = Color.GRAY
                } else {
                    statusLabel.text = "Loaded ${endpoints.size} endpoints"
                    statusLabel.foreground = Color.BLACK
                }
            }
            
            override fun onEndpointsLoadFailed(error: Throwable) {
                // Show empty state instead of error
                endpointTreePanel.showEmptyState()
                statusLabel.text = HttpPalBundle.message("status.no.endpoints")
                statusLabel.foreground = Color.GRAY
            }
        })
        
        // Trigger auto-load when ToolWindow is initialized
        // This happens when the ToolWindow is first opened
        if (!autoLoadManager.hasLoadedEndpoints()) {
            autoLoadManager.startAutoLoad()
        }
    }
    
    fun getContent(): JComponent {
        return tabbedPane
    }
    
    // Public methods for external interaction and state management
    
    /**
     * Populate the request form with endpoint information
     * Implements requirement 3.2: populate request form when endpoint is selected
     */
    fun populateRequestForm(endpoint: DiscoveredEndpoint) {
        // Switch to Request tab
        tabbedPane.selectedIndex = 0
        
        // Populate the request configuration panel
        requestConfigPanel.populateFromEndpoint(endpoint)
        
        statusLabel.text = "Endpoint loaded: ${endpoint.method} ${endpoint.path}"
        updateUIState()
    }
    
    /**
     * Show loading indicator during request execution
     * Implements requirement 3.4: display loading indicator
     */
    fun showRequestProgress() {
        isRequestInProgress = true
        updateUIState()
    }
    
    /**
     * Hide loading indicator and show response
     * Implements requirement 3.4: maintain responsive interaction
     */
    fun hideRequestProgress() {
        isRequestInProgress = false
        updateUIState()
    }
    
    /**
     * Navigate to specific tab programmatically
     */
    fun navigateToTab(tabName: String) {
        val tabIndex = when (tabName.lowercase()) {
            "request" -> 0
            "websocket" -> 1
            "history", "favorites" -> 2
            "environments" -> 3
            else -> 0
        }
        tabbedPane.selectedIndex = tabIndex
    }
    
    /**
     * Get current UI state for external components
     */
    fun getCurrentState(): Map<String, Any> {
        return mapOf(
            "currentTab" to tabbedPane.selectedIndex,
            "isRequestInProgress" to isRequestInProgress,
            "hasResponse" to (currentResponse != null)
        )
    }
    
    /**
     * Display HTTP response in the response panel
     * Implements requirements 7.1, 7.2, 7.3, 7.4
     */
    fun displayResponse(response: HttpResponse) {
        currentResponse = response
        responseDisplayPanel.displayResponse(response)
        hideRequestProgress()
    }
    
    /**
     * Clear the response display
     */
    fun clearResponse() {
        currentResponse = null
        responseDisplayPanel.clearResponse()
    }
    
    /**
     * Show loading state in response panel
     */
    fun showResponseLoading() {
        responseDisplayPanel.showLoadingState()
        showRequestProgress()
    }
    
    /**
     * Get the WebSocket panel for external interaction
     */
    fun getWebSocketPanel(): WebSocketPanel {
        return webSocketPanel
    }
    
    /**
     * Get the endpoint tree panel for external interaction
     */
    fun getEndpointTreePanel(): EndpointTreePanel {
        return endpointTreePanel
    }
    
    /**
     * Display concurrent execution results
     * Implements requirements 8.4, 8.5: display aggregated results and performance statistics
     */
    fun displayConcurrentExecutionResults(result: ConcurrentExecutionResult) {
        // Update status to show completion
        statusLabel.text = "Concurrent execution completed: ${result.getSummary()}"
        
        // The results are already displayed in the concurrent execution panel
        // We could add additional handling here if needed
        
        // Add to history if needed
        // This could be enhanced to save concurrent execution results to history
        
        //hideRequestProgress()
        requestConfigPanel.notifyRequestCompleted()
    }
    
    /**
     * Navigate to WebSocket tab and optionally set URL
     */
    fun openWebSocketConnection(url: String? = null) {
        navigateToTab("websocket")
        url?.let { webSocketPanel.setWebSocketUrl(it) }
    }
    
    /**
     * Show JMeter export dialog for multiple requests
     * Implements requirements 11.1, 11.2, 11.5: export functionality with UI integration
     */
    fun showJMeterExportDialog(requests: List<RequestConfig> = emptyList()) {
        val requestsToExport = if (requests.isNotEmpty()) {
            requests
        } else {
            // Get current request if available
            val currentRequest = requestConfigPanel.getCurrentRequest()
            if (currentRequest != null) {
                listOf(currentRequest)
            } else {
                // Get favorites as fallback
                httpPalService.getFavorites().map { it.request }
            }
        }
        
        if (requestsToExport.isEmpty()) {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    HttpPalBundle.message("error.no.requests.export"),
                    HttpPalBundle.message("dialog.no.requests.available.title")
                )
            return
        }
        
        // Get current environment
        val currentEnvironment = httpPalService.getCurrentEnvironment()
        
        // Show export dialog
        val dialog = JMeterExportDialog(project, requestsToExport, currentEnvironment)
        dialog.show()
    }
    
    /**
     * Get current request configuration for external access
     */
    fun getCurrentRequest(): RequestConfig? {
        return requestConfigPanel.getCurrentRequest()
    }
    
    // Layout Persistence Methods
    
    /**
     * Load saved layout and apply it to UI components
     * Implements requirement 3.5: restore layout on startup
     */
    private fun loadAndApplyLayout() {
        val savedLayout = layoutPersistence.loadLayout()
        if (savedLayout != null) {
            applyLayout(savedLayout)
        } else {
            // Apply default layout if no saved layout exists
            applyLayout(UILayout.default())
        }
    }
    
    /**
     * Apply layout configuration to UI components
     * Implements requirement 3.5: apply saved layout to UI components
     */
    private fun applyLayout(layout: UILayout) {
        // Apply split pane positions
        layout.splitPanePositions["mainSplitPane"]?.let { position ->
            mainSplitPane.dividerLocation = position
        }
        
        layout.splitPanePositions["requestResponseSplitPane"]?.let { position ->
            requestResponseSplitPane.dividerLocation = position
        }
        
        // Apply selected tab
        if (layout.selectedTab in 0 until tabbedPane.tabCount) {
            tabbedPane.selectedIndex = layout.selectedTab
        }
        
        // Column widths can be applied here if we have tables with configurable columns
        // Currently not implemented as the UI doesn't have such tables yet
    }
    
    /**
     * Save current layout configuration
     * Implements requirement 3.5: save layout on adjustment
     */
    private fun saveCurrentLayout() {
        val currentLayout = UILayout(
            splitPanePositions = mapOf(
                "mainSplitPane" to mainSplitPane.dividerLocation,
                "requestResponseSplitPane" to requestResponseSplitPane.dividerLocation
            ),
            selectedTab = tabbedPane.selectedIndex,
            columnWidths = emptyMap() // Can be extended when we have configurable columns
        )
        
        layoutPersistence.saveLayout(currentLayout)
    }
    
    /**
     * Reset layout to default values
     * Public method for external access (e.g., from settings or actions)
     */
    fun resetLayout() {
        layoutPersistence.resetLayout()
        applyLayout(UILayout.default())
    }
}