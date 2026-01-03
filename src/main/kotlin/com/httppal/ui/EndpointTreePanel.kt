package com.httppal.ui

import com.httppal.model.DiscoveredEndpoint
import com.httppal.model.HttpMethod
import com.httppal.model.RequestConfig
import com.httppal.service.AutoLoadManager
import com.httppal.service.EndpointChangeNotification
import com.httppal.service.EndpointDiscoveryService
import com.httppal.service.HttpPalService
import com.httppal.util.ErrorHandler
import com.httppal.util.HttpPalBundle
import com.httppal.util.LoggingUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Panel displaying discovered endpoints in a tree structure
 * Implements requirements 1.1, 1.3, 1.4, 2.5: 
 * - Auto-load endpoints on ToolWindow open
 * - Display loading indicators
 * - Auto-update endpoint list
 * - Register endpoint change listeners
 */
class EndpointTreePanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val endpointDiscoveryService = project.service<EndpointDiscoveryService>()
    private val autoLoadManager = project.service<AutoLoadManager>()
    private val tree = Tree()
    private val rootNode = DefaultMutableTreeNode("API Endpoints")
    private val treeModel = DefaultTreeModel(rootNode)
    
    // Callbacks
    private var onEndpointSelectedCallback: ((DiscoveredEndpoint) -> Unit)? = null
    private var onRefreshCallback: (() -> Unit)? = null
    
    // State
    private var currentEndpoints: List<DiscoveredEndpoint> = emptyList()
    private var isLoading = false
    
    // Performance optimization: Cache for lazy loading
    private val classEndpointsCache = mutableMapOf<String, List<DiscoveredEndpoint>>()
    private var virtualScrollingEnabled = false
    
    // Performance optimization: Debounce tree updates to avoid excessive redraws
    private var updateDebounceJob: kotlinx.coroutines.Job? = null
    private val updateScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    
    // UI components for loading state
    private lateinit var loadingPanel: JPanel
    private lateinit var loadingLabel: JBLabel
    private lateinit var loadingProgressBar: JProgressBar
    private lateinit var statusLabel: JBLabel
    
    init {
        setupUI()
        setupEventHandlers()
        setupEndpointChangeListener()
        setupAutoLoadListeners()
        // Trigger auto-load when panel is created (ToolWindow opened)
        triggerAutoLoad()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(5)
        
        // Title panel
        val titlePanel = JPanel(BorderLayout())
        titlePanel.border = JBUI.Borders.empty(0, 0, 5, 0)
        
        val titleLabel = JBLabel(HttpPalBundle.message("endpoints.title"))
        //titleLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size * 1.3f)
        titlePanel.add(titleLabel, BorderLayout.WEST)
        
        // Refresh button
        val refreshButton = JButton(HttpPalBundle.message("endpoints.refresh.button"))
        refreshButton.preferredSize = Dimension(80, 25)
        refreshButton.addActionListener { manualRefresh() }
        titlePanel.add(refreshButton, BorderLayout.EAST)
        
        add(titlePanel, BorderLayout.NORTH)
        
        // Create main content panel with CardLayout for switching between tree and loading
        val contentPanel = JPanel(CardLayout())
        
        // Tree panel
        val treePanel = JPanel(BorderLayout())
        tree.model = treeModel
        tree.isRootVisible = false // Hide root node for cleaner look
        tree.showsRootHandles = true
        tree.cellRenderer = EndpointTreeCellRenderer()
        //tree.font = UIUtil.getLabelFont()
        // Improve tree appearance
        tree.border = JBUI.Borders.empty(5)
        
        // Customize selection colors to match IntelliJ theme
        /*tree.putClientProperty("JTree.selectionBackground", JBColor.BLUE)
        tree.putClientProperty("JTree.selectionForeground", JBColor.BLACK)*/
        
        // Expand root by default
        tree.expandPath(TreePath(rootNode.path))
        
        // Tree scroll pane
        val scrollPane = JBScrollPane(tree)
        scrollPane.preferredSize = Dimension(300, 400)
        scrollPane.border = JBUI.Borders.empty(5)
        treePanel.add(scrollPane, BorderLayout.CENTER)
        
        contentPanel.add(treePanel, "tree")
        
        // Loading panel
        loadingPanel = createLoadingPanel()
        contentPanel.add(loadingPanel, "loading")
        
        add(contentPanel, BorderLayout.CENTER)
        
        // Status panel
        val statusPanel = createStatusPanel()
        add(statusPanel, BorderLayout.SOUTH)
    }
    
    /**
     * Create loading panel with progress indicator
     * Implements requirement 1.3: Display loading indicator
     */
    private fun createLoadingPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(50)
        
        // Loading icon
        val loadingIcon = JBLabel("⏳")
        loadingIcon.font = loadingIcon.font.deriveFont(36f)
        loadingIcon.alignmentX = Component.CENTER_ALIGNMENT
        panel.add(loadingIcon)
        
        panel.add(Box.createVerticalStrut(15))
        
        // Loading label
        loadingLabel = JBLabel("Discovering API endpoints...")
        loadingLabel.font = loadingLabel.font.deriveFont(Font.BOLD, 14f)
        loadingLabel.alignmentX = Component.CENTER_ALIGNMENT
        panel.add(loadingLabel)
        
        panel.add(Box.createVerticalStrut(10))
        
        // Progress bar
        loadingProgressBar = JProgressBar()
        loadingProgressBar.isIndeterminate = true
        loadingProgressBar.maximumSize = Dimension(200, 8)
        loadingProgressBar.alignmentX = Component.CENTER_ALIGNMENT
        loadingProgressBar.border = JBUI.Borders.empty(5, 20, 5, 20)
        panel.add(loadingProgressBar)
        
        // Sub-label
        val subLabel = JBLabel("Scanning project files for API endpoints")
        subLabel.font = subLabel.font.deriveFont(11f)
        subLabel.alignmentX = Component.CENTER_ALIGNMENT
        subLabel.foreground = Color.GRAY
        panel.add(subLabel)
        
        return panel
    }
    
    private fun createStatusPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = JBUI.Borders.empty(5, 0, 0, 0)
        
        statusLabel = JBLabel(HttpPalBundle.message("status.ready"))
        statusLabel.foreground = Color.GRAY
        statusLabel.font = statusLabel.font.deriveFont(10f)
        panel.add(statusLabel)
        
        return panel
    }
    
    private fun setupEventHandlers() {
        // Double-click to select endpoint with visual feedback
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y)
                    if (path != null) {
                        val node = path.lastPathComponent as DefaultMutableTreeNode
                        val userObject = node.userObject
                        
                        if (userObject is DiscoveredEndpoint) {
                            // Set selection and scroll to make it visible
                            tree.selectionPath = path
                            tree.scrollPathToVisible(path)
                            
                            // Provide immediate feedback
                            // Implements requirement 3.1: Provide visual feedback within 100ms
                            updateStatusPanel("Loading endpoint: ${userObject.method} ${userObject.path}")
                            
                            // Flash the status label to provide visual feedback
                            com.httppal.util.VisualFeedbackHelper.flashComponent(
                                statusLabel, 
                                com.intellij.ui.JBColor(Color(173, 216, 230), Color(70, 130, 180)),
                                150
                            )
                            
                            onEndpointSelectedCallback?.invoke(userObject)
                        }
                    }
                }
            }
            
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }
            
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }
        })
        
        // Single-click selection for preview with immediate feedback
        tree.addTreeSelectionListener { event ->
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            if (node != null && node.userObject is DiscoveredEndpoint) {
                val endpoint = node.userObject as DiscoveredEndpoint
                // Implements requirement 3.1: Provide visual feedback within 100ms
                updateStatusPanel("Selected: ${endpoint.method} ${endpoint.path}")
                
                // Flash the status label to draw attention
                com.httppal.util.VisualFeedbackHelper.flashComponent(
                    statusLabel, 
                    com.intellij.ui.JBColor(Color(173, 216, 230), Color(70, 130, 180)),
                    150
                )
            } else {
                updateStatusPanel("Ready")
            }
        }
    }
    
    private fun setupEndpointChangeListener() {
        // Register as endpoint change listener
        // Implements requirement 2.5: Register endpoint change listener
        endpointDiscoveryService.addEndpointChangeListener { endpoints ->
            // Auto-update endpoint list when changes are detected
            // Implements requirement 2.5: Auto-update endpoint list
            ApplicationManager.getApplication().invokeLater {
                updateEndpointsDisplay(endpoints)
            }
        }
    }
    
    /**
     * Setup listeners for AutoLoadManager events
     * Implements requirements 1.3, 1.4: Display loading state and update UI after load
     */
    private fun setupAutoLoadListeners() {
        // Listen for loading state changes
        autoLoadManager.addLoadingStateListener(object : AutoLoadManager.LoadingStateListener {
            override fun onLoadingStarted(message: String) {
                showLoadingState(message)
            }
            
            override fun onLoadingCompleted() {
                hideLoadingState()
            }
            
            override fun onLoadingFailed(error: String) {
                hideLoadingState()
                updateStatusPanel("Error: $error")
            }
        })
        
        // Listen for endpoint load events
        autoLoadManager.addEndpointLoadListener(object : AutoLoadManager.EndpointLoadListener {
            override fun onEndpointsLoaded(endpoints: List<DiscoveredEndpoint>) {
                updateEndpointsDisplay(endpoints)
            }
            
            override fun onEndpointsLoadFailed(error: Throwable) {
                updateStatusPanel("Failed to load endpoints: ${error.message}")
            }
        })
    }
    
    /**
     * Trigger auto-load when ToolWindow opens
     * Implements requirement 1.1: Auto-load endpoints on ToolWindow open
     */
    private fun triggerAutoLoad() {
        // Only trigger if not already loaded
        if (!autoLoadManager.hasLoadedEndpoints()) {
            autoLoadManager.startAutoLoad()
        } else {
            // If already loaded, just refresh the display with current endpoints
            val endpoints = endpointDiscoveryService.discoverEndpoints()
            updateEndpointsDisplay(endpoints)
        }
    }
    
    /**
     * Show loading state
     * Implements requirement 1.3: Display loading indicator
     */
    fun showLoadingState(message: String = "Loading endpoints...") {
        isLoading = true
        loadingLabel.text = message
        
        // Switch to loading panel
        if (componentCount > 1) {
            val contentPanel = getComponent(1) as? JPanel
            if (contentPanel != null && contentPanel.layout is CardLayout) {
                val cardLayout = contentPanel.layout as CardLayout
                cardLayout.show(contentPanel, "loading")
            }
        }
        
        updateStatusPanel(message)
    }
    
    /**
     * Hide loading state
     * Implements requirement 1.4: Update UI after load completion
     */
    fun hideLoadingState() {
        isLoading = false
        
        // Switch back to tree panel
        if (componentCount > 1) {
            val contentPanel = getComponent(1) as? JPanel
            if (contentPanel != null && contentPanel.layout is CardLayout) {
                val cardLayout = contentPanel.layout as CardLayout
                cardLayout.show(contentPanel, "tree")
            }
        }
    }
    
    /**
     * Show empty state when no endpoints are found
     * Implements requirement 11: No-endpoint usability
     */
    fun showEmptyState() {
        isLoading = false
        
        // Create empty state panel
        val emptyPanel = JPanel()
        emptyPanel.layout = BoxLayout(emptyPanel, BoxLayout.Y_AXIS)
        emptyPanel.border = JBUI.Borders.empty(20)
        
        // Icon
        val iconLabel = JBLabel("🔍")
        iconLabel.font = iconLabel.font.deriveFont(48f)
        iconLabel.alignmentX = Component.CENTER_ALIGNMENT
        iconLabel.foreground = Color.GRAY
        emptyPanel.add(iconLabel)
        emptyPanel.add(Box.createVerticalStrut(20))
        
        // Message
        val messageLabel = JBLabel(HttpPalBundle.message("endpoints.empty.message"))
        messageLabel.font = messageLabel.font.deriveFont(Font.BOLD, 14f)
        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        messageLabel.foreground = Color.GRAY
        emptyPanel.add(messageLabel)
        emptyPanel.add(Box.createVerticalStrut(15))
        
        // Tips
        val tipsLabel = JBLabel("<html><center>" + HttpPalBundle.message("endpoints.empty.tips") + "</center></html>")
        tipsLabel.alignmentX = Component.CENTER_ALIGNMENT
        tipsLabel.foreground = Color(128, 128, 128) // Light gray
        emptyPanel.add(tipsLabel)
        emptyPanel.add(Box.createVerticalStrut(20))
        
        // Refresh button
        val refreshButton = JButton(HttpPalBundle.message("endpoints.empty.refresh"))
        refreshButton.alignmentX = Component.CENTER_ALIGNMENT
        refreshButton.addActionListener { manualRefresh() }
        emptyPanel.add(refreshButton)
        
        // Learn more button
        val learnMoreButton = JButton(HttpPalBundle.message("endpoints.empty.learn.more"))
        learnMoreButton.alignmentX = Component.CENTER_ALIGNMENT
        learnMoreButton.addActionListener {
            // Open documentation or help
            val helpUrl = "https://github.com/your-repo/httppal/wiki/Endpoint-Discovery"
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI(helpUrl))
            } catch (e: Exception) {
                // Fallback: show message
                JOptionPane.showMessageDialog(this, "Please visit: $helpUrl")
            }
        }
        emptyPanel.add(Box.createVerticalStrut(10))
        emptyPanel.add(learnMoreButton)
        
        // Replace tree with empty panel
        removeAll()
        add(emptyPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }
    
    /**
     * Auto-update endpoints based on change notification
     * Implements requirement 2.5: Auto-update endpoint list
     * Implements requirement 2.2, 2.3: Preserve selection state
     */
    private fun autoUpdateEndpoints(notification: EndpointChangeNotification) {
        if (!notification.hasChanges()) {
            return
        }
        
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.INFO,
            "Auto-updating endpoints",
            mapOf(
                "added" to notification.addedEndpoints.size,
                "modified" to notification.modifiedEndpoints.size,
                "removed" to notification.removedEndpoints.size
            )
        )
        
        // Save currently selected endpoint to restore after update
        val selectedEndpoint = getSelectedEndpoint()
        
        // Get all current endpoints from discovery service
        val allEndpoints = endpointDiscoveryService.discoverEndpoints()
        updateEndpointsDisplay(allEndpoints)
        
        // Try to restore selection if the endpoint still exists
        if (selectedEndpoint != null) {
            restoreSelection(selectedEndpoint)
        }
        
        // Update status with change summary
        val changesSummary = buildString {
            if (notification.addedEndpoints.isNotEmpty()) {
                append("+${notification.addedEndpoints.size} ")
            }
            if (notification.modifiedEndpoints.isNotEmpty()) {
                append("~${notification.modifiedEndpoints.size} ")
            }
            if (notification.removedEndpoints.isNotEmpty()) {
                append("-${notification.removedEndpoints.size} ")
            }
            append("endpoints updated")
        }
        updateStatusPanel(changesSummary)
        
        // Notify callback
        onRefreshCallback?.invoke()
    }
    
    /**
     * Manual refresh triggered by user with async UI updates
     * Implements requirement 3.1: Time-consuming operations in background threads
     * Implements requirement 3.4: Add progress indicators
     */
    private fun manualRefresh() {
        // Show loading state immediately
        showLoadingState("Refreshing endpoints...")
        
        // Execute refresh in background
        com.httppal.util.AsyncUIHelper.executeInBackground(
            backgroundTask = {
                // Trigger manual refresh
                autoLoadManager.manualRefresh()
                // Get updated endpoints
                endpointDiscoveryService.discoverEndpoints()
            },
            onComplete = { endpoints ->
                // Update UI on EDT thread
                updateEndpointsDisplay(endpoints)
                hideLoadingState()
                updateStatusPanel("Refresh completed: ${endpoints.size} endpoints found")
            },
            onError = { error ->
                // Handle error on EDT thread
                hideLoadingState()
                updateStatusPanel("Refresh failed: ${error.message}")
                ErrorHandler.handleError(
                    message = "Endpoint Refresh Failed",
                    cause = error,
                    project = project,
                    component = this
                )
            }
        )
    }
    
    
    private fun updateEndpointsDisplay(endpoints: List<DiscoveredEndpoint>) {
        // Performance optimization: Debounce updates to avoid excessive redraws
        updateDebounceJob?.cancel()
        updateDebounceJob = updateScope.launch {
            kotlinx.coroutines.delay(100) // 100ms debounce
            
            // Update UI on EDT thread
            ApplicationManager.getApplication().invokeLater {
                performEndpointsUpdate(endpoints)
            }
        }
    }
    
    /**
     * Perform the actual endpoints update
     * Separated from updateEndpointsDisplay for debouncing
     */
    private fun performEndpointsUpdate(endpoints: List<DiscoveredEndpoint>) {
        currentEndpoints = endpoints
        
        // Clear existing nodes
        rootNode.removeAllChildren()
        
        if (endpoints.isEmpty()) {
            val noEndpointsNode = DefaultMutableTreeNode("No endpoints found")
            rootNode.add(noEndpointsNode)
            updateStatusPanel("No endpoints discovered")
        } else {
            // Check if we need virtual scrolling
            // Implements requirement 3.2: Enable virtual scrolling for > 100 endpoints
            virtualScrollingEnabled = endpoints.size > 100
            
            if (virtualScrollingEnabled) {
                enableVirtualScrolling(endpoints)
            } else {
                displayEndpointsNormally(endpoints)
            }
        }
        
        // Refresh tree model
        treeModel.reload()
        
        // Expand all class nodes (but limit to avoid performance issues)
        val maxNodesToExpand = if (virtualScrollingEnabled) 10 else rootNode.childCount
        for (i in 0 until minOf(maxNodesToExpand, rootNode.childCount)) {
            val classNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            tree.expandPath(TreePath(classNode.path))
        }
        
        // Expand root
        tree.expandPath(TreePath(rootNode.path))
    }
    
    /**
     * Display endpoints normally (without virtual scrolling)
     * Performance optimization: Cache class endpoints for faster access
     */
    private fun displayEndpointsNormally(endpoints: List<DiscoveredEndpoint>) {
        // Group endpoints by class (requirement 1.5)
        val endpointsByClass = endpoints.groupBy { it.className }
        
        // Performance optimization: Cache class endpoints
        classEndpointsCache.clear()
        classEndpointsCache.putAll(endpointsByClass)
        
        endpointsByClass.forEach { (className, classEndpoints) ->
            val classNode = DefaultMutableTreeNode(ClassNodeData(className, classEndpoints.size))
            
            // Sort endpoints by method and path for better organization
            val sortedEndpoints = classEndpoints.sortedWith(
                compareBy<DiscoveredEndpoint> { it.method.ordinal }
                    .thenBy { it.path }
            )
            
            sortedEndpoints.forEach { endpoint ->
                val endpointNode = DefaultMutableTreeNode(endpoint)
                classNode.add(endpointNode)
            }
            
            rootNode.add(classNode)
        }
        
        updateStatusPanel("Found ${endpoints.size} endpoints in ${endpointsByClass.size} classes")
    }
    
    /**
     * Enable virtual scrolling for large endpoint lists
     * Implements requirement 3.2: Virtual scrolling for > 100 endpoints
     * Performance optimization: Lazy loading of endpoints on demand
     */
    private fun enableVirtualScrolling(endpoints: List<DiscoveredEndpoint>) {
        // Group endpoints by class
        val endpointsByClass = endpoints.groupBy { it.className }
        
        // Performance optimization: Cache class endpoints for lazy loading
        classEndpointsCache.clear()
        classEndpointsCache.putAll(endpointsByClass)
        
        // For virtual scrolling, we'll show a simplified view
        // Show class nodes with endpoint counts, but only load endpoints on demand
        endpointsByClass.forEach { (className, classEndpoints) ->
            val classNode = DefaultMutableTreeNode(
                ClassNodeData(className, classEndpoints.size, isVirtualized = true)
            )
            
            // Add a placeholder node to show that endpoints can be expanded
            val placeholderNode = DefaultMutableTreeNode(
                "Click to load ${classEndpoints.size} endpoints..."
            )
            classNode.add(placeholderNode)
            
            rootNode.add(classNode)
        }
        
        updateStatusPanel(
            "Found ${endpoints.size} endpoints in ${endpointsByClass.size} classes (virtual scrolling enabled)"
        )
        
        // Add tree expansion listener to load endpoints on demand
        // Performance optimization: Only load visible endpoints
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode
                if (node != null && node.userObject is ClassNodeData) {
                    val classData = node.userObject as ClassNodeData
                    if (classData.isVirtualized && node.childCount == 1) {
                        // Lazy load endpoints for this class
                        loadEndpointsForClassLazy(node, classData.className)
                    }
                }
            }
            
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                // Performance optimization: Unload endpoints to save memory
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode
                if (node != null && node.userObject is ClassNodeData) {
                    val classData = node.userObject as ClassNodeData
                    if (classData.isVirtualized && node.childCount > 1) {
                        // Unload endpoints (replace with placeholder)
                        node.removeAllChildren()
                        val placeholderNode = DefaultMutableTreeNode(
                            "Click to load ${classData.endpointCount} endpoints..."
                        )
                        node.add(placeholderNode)
                        treeModel.reload(node)
                    }
                }
            }
        })
    }
    
    /**
     * Load endpoints for a specific class with lazy loading
     * Performance optimization for requirement 3.2
     */
    private fun loadEndpointsForClassLazy(classNode: DefaultMutableTreeNode, className: String) {
        // Remove placeholder
        classNode.removeAllChildren()
        
        // Get endpoints for this class from cache
        val classEndpoints = classEndpointsCache[className] ?: emptyList()
        
        // Sort endpoints
        val sortedEndpoints = classEndpoints.sortedWith(
            compareBy<DiscoveredEndpoint> { it.method.ordinal }
                .thenBy { it.path }
        )
        
        // Add endpoint nodes
        sortedEndpoints.forEach { endpoint ->
            val endpointNode = DefaultMutableTreeNode(endpoint)
            classNode.add(endpointNode)
        }
        
        // Reload the node
        treeModel.reload(classNode)
    }
    
    private fun updateStatusPanel(message: String) {
        statusLabel.text = message
    }
    
    /**
     * Set callback for endpoint selection
     * Implements requirement 3.2: endpoint selection and form filling
     */
    fun setOnEndpointSelectedCallback(callback: (DiscoveredEndpoint) -> Unit) {
        onEndpointSelectedCallback = callback
    }
    
    /**
     * Set callback for refresh completion
     */
    fun setOnRefreshCallback(callback: () -> Unit) {
        onRefreshCallback = callback
    }
    
    /**
     * Get currently selected endpoint
     */
    fun getSelectedEndpoint(): DiscoveredEndpoint? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return if (node != null && node.userObject is DiscoveredEndpoint) {
            node.userObject as DiscoveredEndpoint
        } else {
            null
        }
    }
    
    /**
     * Restore selection to a specific endpoint after tree update
     * Implements requirement 2.2, 2.3: Maintain selection state
     */
    private fun restoreSelection(endpoint: DiscoveredEndpoint) {
        // Find the endpoint in the tree
        for (i in 0 until rootNode.childCount) {
            val classNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            for (j in 0 until classNode.childCount) {
                val endpointNode = classNode.getChildAt(j) as DefaultMutableTreeNode
                if (endpointNode.userObject is DiscoveredEndpoint) {
                    val nodeEndpoint = endpointNode.userObject as DiscoveredEndpoint
                    // Match by path and method
                    if (nodeEndpoint.path == endpoint.path && nodeEndpoint.method == endpoint.method) {
                        val path = TreePath(endpointNode.path)
                        tree.selectionPath = path
                        tree.scrollPathToVisible(path)
                        return
                    }
                }
            }
        }
    }
    
    /**
     * Get all discovered endpoints
     */
    fun getAllEndpoints(): List<DiscoveredEndpoint> {
        return currentEndpoints.toList()
    }
    
    /**
     * Filter endpoints by search term
     */
    fun filterEndpoints(searchTerm: String) {
        if (searchTerm.isBlank()) {
            updateEndpointsDisplay(currentEndpoints)
            return
        }
        
        val filteredEndpoints = currentEndpoints.filter { endpoint ->
            endpoint.path.contains(searchTerm, ignoreCase = true) ||
            endpoint.method.name.contains(searchTerm, ignoreCase = true) ||
            endpoint.className.contains(searchTerm, ignoreCase = true) ||
            endpoint.methodName.contains(searchTerm, ignoreCase = true)
        }
        
        updateEndpointsDisplay(filteredEndpoints)
        updateStatusPanel("Filtered: ${filteredEndpoints.size} of ${currentEndpoints.size} endpoints")
    }
    
    /**
     * Expand all nodes in the tree
     */
    fun expandAll() {
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }
    
    /**
     * Collapse all nodes in the tree
     */
    fun collapseAll() {
        for (i in tree.rowCount - 1 downTo 1) {
            tree.collapseRow(i)
        }
    }
    
    /**
     * Show context menu for endpoint actions
     */
    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as DefaultMutableTreeNode
        
        val popup = JPopupMenu()
        
        when (val userObject = node.userObject) {
            is DiscoveredEndpoint -> {
                // Single endpoint actions
                val loadAction = JMenuItem(HttpPalBundle.message("context.load.request"))
                loadAction.addActionListener {
                    onEndpointSelectedCallback?.invoke(userObject)
                }
                popup.add(loadAction)
                
                val exportAction = JMenuItem(HttpPalBundle.message("context.export.jmeter"))
                exportAction.addActionListener {
                    exportEndpointToJMeter(userObject)
                }
                popup.add(exportAction)
            }
            is ClassNodeData -> {
                // Class-level actions
                val exportAllAction = JMenuItem(HttpPalBundle.message("context.export.all"))
                exportAllAction.addActionListener {
                    val classEndpoints = getEndpointsForClass(userObject.className)
                    exportEndpointsToJMeter(classEndpoints)
                }
                popup.add(exportAllAction)
            }
            else -> {
                // Root or other nodes
                if (currentEndpoints.isNotEmpty()) {
                    val exportAllAction = JMenuItem(HttpPalBundle.message("context.export.all"))
                    exportAllAction.addActionListener {
                        exportEndpointsToJMeter(currentEndpoints)
                    }
                    popup.add(exportAllAction)
                }
            }
        }
        
        if (popup.componentCount > 0) {
            popup.show(tree, e.x, e.y)
        }
    }
    
    /**
     * Export a single endpoint to JMeter
     */
    private fun exportEndpointToJMeter(endpoint: DiscoveredEndpoint) {
        val requestConfig = convertEndpointToRequestConfig(endpoint)
        showJMeterExportDialog(listOf(requestConfig))
    }
    
    /**
     * Export multiple endpoints to JMeter
     */
    private fun exportEndpointsToJMeter(endpoints: List<DiscoveredEndpoint>) {
        val requestConfigs = endpoints.map { convertEndpointToRequestConfig(it) }
        showJMeterExportDialog(requestConfigs)
    }
    
    /**
     * Convert discovered endpoint to request configuration
     */
    private fun convertEndpointToRequestConfig(endpoint: DiscoveredEndpoint): com.httppal.model.RequestConfig {
        return com.httppal.model.RequestConfig(
            method = endpoint.method,
            url = endpoint.path,
            headers = emptyMap(), // Will be filled by user or environment
            body = null,
            timeout = java.time.Duration.ofSeconds(30),
            followRedirects = true
        )
    }
    
    /**
     * Show JMeter export dialog
     */
    private fun showJMeterExportDialog(requests: List<RequestConfig>) {
        // Get HttpPal service for current environment
        val httpPalService = service<HttpPalService>()
        val currentEnvironment = httpPalService.getCurrentEnvironment()
        
        // Show export dialog
        val dialog = JMeterExportDialog(project, requests, currentEnvironment)
        dialog.show()
    }
    
    /**
     * Get endpoints for a specific class
     */
    private fun getEndpointsForClass(className: String): List<DiscoveredEndpoint> {
        return currentEndpoints.filter { it.className == className }
    }
    
    /**
     * Data class for class nodes in the tree
     */
    private data class ClassNodeData(
        val className: String,
        val endpointCount: Int,
        val isVirtualized: Boolean = false
    ) {
        override fun toString(): String {
            val shortClassName = className.substringAfterLast('.')
            val virtualIndicator = if (isVirtualized) " [Virtual]" else ""
            return "$shortClassName ($endpointCount endpoints)$virtualIndicator"
        }
    }
    
    /**
     * Custom tree cell renderer for endpoints
     */
    private class EndpointTreeCellRenderer : DefaultTreeCellRenderer() {
        
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            
            val node = value as DefaultMutableTreeNode
            val userObject = node.userObject
            
            when (userObject) {
                is DiscoveredEndpoint -> {
                    // 根据来源添加前缀标识
                    val sourcePrefix = when (userObject.source) {
                        com.httppal.model.EndpointSource.OPENAPI -> "[OpenAPI] "
                        com.httppal.model.EndpointSource.CODE_SCAN -> "[Code] "
                        com.httppal.model.EndpointSource.DISCOVERED -> "[Code] "
                        com.httppal.model.EndpointSource.MANUAL -> "[Manual] "
                    }
                    
                    text = "$sourcePrefix${userObject.method.name} ${userObject.path}"
                    icon = getMethodIcon(userObject.method, userObject.source)
                    toolTipText = buildEndpointTooltip(userObject)
                    
                    // Customize appearance for endpoint nodes
                    if (!sel) { // Only customize non-selected items
                        foreground = when (userObject.method) {
                            HttpMethod.GET -> Color(41, 121, 255) // Blue for GET
                            HttpMethod.POST -> Color(220, 90, 10) // Orange for POST
                            HttpMethod.PUT -> Color(255, 150, 0) // Orange for PUT
                            HttpMethod.DELETE -> Color(220, 50, 50) // Red for DELETE
                            HttpMethod.PATCH -> Color(180, 80, 220) // Purple for PATCH
                            HttpMethod.HEAD -> Color(100, 100, 100) // Gray for HEAD
                            HttpMethod.OPTIONS -> Color(80, 180, 80) // Green for OPTIONS
                        }
                    }
                }
                is ClassNodeData -> {
                    text = userObject.toString()
                    icon = getMethodIcon(HttpMethod.GET) // Use generic icon for class nodes
                    toolTipText = "Controller class: ${userObject.className}"
                    
                    // Customize appearance for class nodes
                    if (!sel) {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD) // Bold for class nodes
                        foreground = Color(0, 100, 0) // Dark green for class nodes
                    }
                }
                is String -> {
                    text = userObject
                    icon = null
                    toolTipText = null
                }
                else -> {
                    text = userObject.toString()
                    icon = null
                    toolTipText = null
                }
            }
            
            return this
        }
        
        private fun getMethodIcon(method: HttpMethod, source: com.httppal.model.EndpointSource = com.httppal.model.EndpointSource.CODE_SCAN): Icon? {
            // Create improved colored icons for different HTTP methods
            return when (method) {
                HttpMethod.GET -> createMethodIcon(Color(41, 121, 255), source) // Better blue
                HttpMethod.POST -> createMethodIcon(Color(220, 90, 10), source) // Better orange
                HttpMethod.PUT -> createMethodIcon(Color(255, 150, 0), source) // Better orange
                HttpMethod.DELETE -> createMethodIcon(Color(220, 50, 50), source) // Better red
                HttpMethod.PATCH -> createMethodIcon(Color(180, 80, 220), source) // Better purple
                HttpMethod.HEAD -> createMethodIcon(Color(100, 100, 100), source) // Gray
                HttpMethod.OPTIONS -> createMethodIcon(Color(80, 180, 80), source) // Green
            }
        }
        
        private fun createMethodIcon(color: Color, source: com.httppal.model.EndpointSource = com.httppal.model.EndpointSource.CODE_SCAN): Icon {
            return object : Icon {
                override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                    g?.let {
                        // Draw filled rectangle as background
                        it.color = Color(245, 245, 245) // Light gray background
                        it.fillRect(x, y, iconWidth, iconHeight)
                        
                        // Draw method abbreviation
                        it.color = color
                        ///it.font = it.font.deriveFont(13f)
                        
                        // Get method initial
                        val methodInitial = when (color) {
                            Color(41, 121, 255) -> "G" // GET
                            Color(220, 90, 10) -> "P" // POST
                            Color(255, 150, 0) -> "U" // PUT
                            Color(220, 50, 50) -> "D" // DELETE
                            Color(180, 80, 220) -> "A" // PATCH (P is already used)
                            Color(100, 100, 100) -> "H" // HEAD
                            else -> "O" // OPTIONS
                        }
                        
                        // Center the text
                        val fm = it.fontMetrics
                        val width = fm.stringWidth(methodInitial)
                        val height = fm.height
                        val textX = x + (iconWidth - width) / 2
                        val textY = y + (iconHeight - height) / 2 + fm.ascent
                        
                        it.drawString(methodInitial, textX, textY)
                    }
                }
                
                override fun getIconWidth(): Int = 16
                override fun getIconHeight(): Int = 16
            }
        }
        
        private fun buildEndpointTooltip(endpoint: DiscoveredEndpoint): String {
            val tooltip = StringBuilder()
            tooltip.append("<html><div style='padding: 5px;'>")
            tooltip.append("<b style='color: #2979FF;'>${endpoint.method.name}</b> ")
            tooltip.append("<span style='color: #333;'>${endpoint.path}</span><br/>")
            
            // 显示来源
            val sourceText = when (endpoint.source) {
                com.httppal.model.EndpointSource.OPENAPI -> "OpenAPI File"
                com.httppal.model.EndpointSource.CODE_SCAN -> "Code Scan"
                com.httppal.model.EndpointSource.DISCOVERED -> "Code Scan"
                com.httppal.model.EndpointSource.MANUAL -> "Manual"
            }
            tooltip.append("<div style='margin-top: 8px;'><b>Source:</b> $sourceText</div>")
            
            // 显示 summary（如果有）
            if (endpoint.summary != null) {
                tooltip.append("<div style='margin-top: 4px;'><b>Summary:</b> ${endpoint.summary}</div>")
            }
            
            // 显示 operationId（如果有）
            if (endpoint.operationId != null) {
                tooltip.append("<div style='margin-top: 4px;'><b>Operation ID:</b> ${endpoint.operationId}</div>")
            }
            
            // 显示 tags（如果有）
            if (endpoint.tags.isNotEmpty()) {
                tooltip.append("<div style='margin-top: 4px;'><b>Tags:</b> ${endpoint.tags.joinToString(", ")}</div>")
            }
            
            tooltip.append("<div style='margin-top: 4px;'><b>Class:</b> ${endpoint.className}</div>")
            tooltip.append("<div style='margin-top: 4px;'><b>Method:</b> ${endpoint.methodName}</div>")
            tooltip.append("<div style='margin-top: 4px;'><b>File:</b> ${endpoint.sourceFile.substringAfterLast('/')}</div>")
            
            if (endpoint.lineNumber > 0) {
                tooltip.append("<div style='margin-top: 4px;'><b>Line:</b> ${endpoint.lineNumber}</div>")
            }
            
            if (endpoint.parameters.isNotEmpty()) {
                tooltip.append("<div style='margin-top: 8px;'><b>Parameters:</b></div>")
                endpoint.parameters.forEach { param ->
                    val paramInfo = StringBuilder()
                    paramInfo.append("• ${param.getDisplayName()}")
                    if (param.description != null) {
                        paramInfo.append(" - ${param.description}")
                    }
                    if (param.example != null) {
                        paramInfo.append(" (e.g., ${param.example})")
                    }
                    tooltip.append("<div style='margin-left: 10px; margin-top: 2px;'>$paramInfo</div>")
                }
            }
            
            tooltip.append("</div></html>")
            return tooltip.toString()
        }
    }
}