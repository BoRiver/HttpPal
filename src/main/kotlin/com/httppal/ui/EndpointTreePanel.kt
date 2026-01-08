package com.httppal.ui

import com.httppal.model.DiscoveredEndpoint
import com.httppal.model.Environment
import com.httppal.model.HttpMethod
import com.httppal.model.RequestConfig
import com.httppal.service.AutoLoadManager
import com.httppal.service.EndpointChangeNotification
import com.httppal.service.EndpointDiscoveryService
import com.httppal.service.HttpPalService
import com.httppal.util.ErrorHandler
import com.httppal.util.HttpPalBundle
import com.httppal.util.LoggingUtils
import com.httppal.util.MapUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.ActionEvent
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
    internal val tree = Tree()  // 改为 internal 以便增强面板访问
    private val rootNode = DefaultMutableTreeNode("API Endpoints")
    private val treeModel = DefaultTreeModel(rootNode)
    
    // Callbacks
    private var onEndpointSelectedCallback: ((DiscoveredEndpoint) -> Unit)? = null
    private var onRefreshCallback: (() -> Unit)? = null
    
    // State
    private var allEndpoints: List<DiscoveredEndpoint> = emptyList() // 保存所有端点
    private var currentEndpoints: List<DiscoveredEndpoint> = emptyList() // 当前显示的端点（可能是筛选后的）
    private var isLoading = false
    
    // New UI components for view mode and filtering
    private lateinit var viewModeComboBox: JComboBox<ViewMode>
    private lateinit var filterTextField: JTextField
    private lateinit var clearFilterButton: JButton
    
    // Controller for managing view mode and filtering
    private lateinit var controller: EndpointTreeController
    private lateinit var viewModel: EndpointViewModel
    
    // Custom renderer for highlighting
    private val treeRenderer = EndpointTreeCellRenderer()
    
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
        // Initialize view model and controller
        viewModel = EndpointViewModel()
        controller = EndpointTreeController(project, viewModel, tree)
        
        setupUI()
        setupEventHandlers()
        setupControllerCallbacks()
        setupEndpointChangeListener()
        setupAutoLoadListeners()
        // Trigger auto-load when panel is created (ToolWindow opened)
        triggerAutoLoad()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(5)
        
        // Create top panel with title, view mode selector, filter, and refresh button
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.border = JBUI.Borders.empty(0, 0, 5, 0)
        
        // Title and refresh button row
        val titlePanel = JPanel(BorderLayout())
        
        val titleLabel = JBLabel(HttpPalBundle.message("endpoints.title"))
        titlePanel.add(titleLabel, BorderLayout.WEST)
        
        // Refresh button
        val refreshButton = JButton(HttpPalBundle.message("endpoints.refresh.button"))
        refreshButton.preferredSize = Dimension(80, 25)
        refreshButton.addActionListener { manualRefresh() }
        titlePanel.add(refreshButton, BorderLayout.EAST)
        
        topPanel.add(titlePanel)
        topPanel.add(Box.createVerticalStrut(5))
        
        // View mode selector and filter row
        val controlsPanel = JPanel(BorderLayout())
        controlsPanel.border = JBUI.Borders.empty(0, 0, 0, 0)
        
        // View mode selector (left side)
        val viewModePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        val viewModeLabel = JBLabel("View: ")
        viewModePanel.add(viewModeLabel)
        
        viewModeComboBox = JComboBox(ViewMode.values())
        viewModeComboBox.selectedItem = ViewMode.AUTO_DISCOVERY
        viewModeComboBox.toolTipText = "Select view mode for organizing endpoints"
        viewModeComboBox.addActionListener {
            val selectedMode = viewModeComboBox.selectedItem as ViewMode
            controller.switchViewMode(selectedMode)
        }
        viewModePanel.add(viewModeComboBox)
        
        controlsPanel.add(viewModePanel, BorderLayout.WEST)
        
        // Filter box (right side)
        val filterPanel = JPanel(BorderLayout())
        filterPanel.border = JBUI.Borders.empty(0, 5, 0, 0)
        
        filterTextField = JTextField()
        filterTextField.toolTipText = "Filter endpoints (supports multiple keywords)"
        filterTextField.putClientProperty("JTextField.placeholderText", "Filter endpoints...")
        
        // Add document listener for real-time filtering
        filterTextField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                applyFilterWithDebounce()
            }
            
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                applyFilterWithDebounce()
            }
            
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                applyFilterWithDebounce()
            }
        })
        
        filterPanel.add(filterTextField, BorderLayout.CENTER)
        
        // Clear filter button
        clearFilterButton = JButton("✕")
        clearFilterButton.toolTipText = "Clear filter"
        clearFilterButton.preferredSize = Dimension(25, 25)
        clearFilterButton.addActionListener {
            filterTextField.text = ""
            controller.clearFilter()
        }
        filterPanel.add(clearFilterButton, BorderLayout.EAST)
        
        controlsPanel.add(filterPanel, BorderLayout.CENTER)
        
        topPanel.add(controlsPanel)
        
        add(topPanel, BorderLayout.NORTH)
        
        // Create main content panel with CardLayout for switching between tree and loading
        val contentPanel = JPanel(CardLayout())
        
        // Tree panel
        val treePanel = JPanel(BorderLayout())
        tree.model = treeModel
        tree.isRootVisible = false // Hide root node for cleaner look
        tree.showsRootHandles = true
        tree.cellRenderer = treeRenderer
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
        
        // Setup keyboard shortcuts
        setupKeyboardShortcuts()
    }
    
    /**
     * Setup keyboard shortcuts for accessibility
     * Implements requirement 4.5: Keyboard shortcuts
     */
    private fun setupKeyboardShortcuts() {
        // Ctrl+Shift+V: Cycle through view modes
        val cycleViewModeAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                cycleViewMode()
            }
        }
        
        val cycleViewModeKeyStroke = KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_V,
            java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK
        )
        
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(cycleViewModeKeyStroke, "cycleViewMode")
        getActionMap().put("cycleViewMode", cycleViewModeAction)
        
        // Ctrl+F: Focus filter text field
        val focusFilterAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                filterTextField.requestFocusInWindow()
                filterTextField.selectAll()
            }
        }
        
        val focusFilterKeyStroke = KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_F,
            java.awt.event.InputEvent.CTRL_DOWN_MASK
        )
        
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(focusFilterKeyStroke, "focusFilter")
        getActionMap().put("focusFilter", focusFilterAction)
        
        // Escape: Clear filter when filter field is focused
        val clearFilterAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (filterTextField.isFocusOwner) {
                    filterTextField.text = ""
                    controller.clearFilter()
                }
            }
        }
        
        val escapeKeyStroke = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0)
        filterTextField.getInputMap(JComponent.WHEN_FOCUSED).put(escapeKeyStroke, "clearFilter")
        filterTextField.getActionMap().put("clearFilter", clearFilterAction)
        
        // Set accessible names and descriptions for screen readers
        viewModeComboBox.accessibleContext.accessibleName = "View Mode Selector"
        viewModeComboBox.accessibleContext.accessibleDescription = "Select how endpoints are organized in the tree"
        
        filterTextField.accessibleContext.accessibleName = "Filter Endpoints"
        filterTextField.accessibleContext.accessibleDescription = "Type to filter endpoints by keywords"
        
        clearFilterButton.accessibleContext.accessibleName = "Clear Filter"
        clearFilterButton.accessibleContext.accessibleDescription = "Clear the current filter"
        
        tree.accessibleContext.accessibleName = "Endpoints Tree"
        tree.accessibleContext.accessibleDescription = "Tree view of discovered API endpoints"
    }
    
    /**
     * Cycle through view modes
     */
    private fun cycleViewMode() {
        val currentMode = viewModeComboBox.selectedItem as ViewMode
        val nextMode = when (currentMode) {
            ViewMode.AUTO_DISCOVERY -> ViewMode.SWAGGER
            ViewMode.SWAGGER -> ViewMode.CLASS_METHOD
            ViewMode.CLASS_METHOD -> ViewMode.AUTO_DISCOVERY
        }
        viewModeComboBox.selectedItem = nextMode
        controller.switchViewMode(nextMode)
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
                
                // Record selected endpoint for position preservation
                controller.recordSelectedEndpoint(endpoint)
                
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
    
    /**
     * Setup controller callbacks for view mode and filter changes
     */
    private fun setupControllerCallbacks() {
        // Handle view mode changes
        controller.setOnViewModeChanged { newMode ->
            // Update the tree display based on the new view mode
            updateTreeForViewMode(newMode)
        }
        
        // Handle filter changes
        controller.setOnFilterChanged { filterText ->
            // Apply the filter to the current endpoints
            applyFilter(filterText)
        }
    }
    
    /**
     * Debounce filter application to avoid excessive updates
     */
    private var filterDebounceJob: kotlinx.coroutines.Job? = null
    
    private fun applyFilterWithDebounce() {
        filterDebounceJob?.cancel()
        filterDebounceJob = updateScope.launch {
            kotlinx.coroutines.delay(200) // 200ms debounce
            
            ApplicationManager.getApplication().invokeLater {
                val filterText = filterTextField.text
                controller.applyFilter(filterText)
            }
        }
    }
    
    /**
     * Apply filter to endpoints
     * Handles empty filter results
     * Always filters from allEndpoints to avoid cumulative filtering
     */
    private fun applyFilter(filterText: String) {
        // Update renderer with current filter text for highlighting
        treeRenderer.setFilterText(filterText)
        
        if (filterText.isBlank()) {
            // Show all endpoints - use allEndpoints instead of currentEndpoints
            currentEndpoints = allEndpoints
            viewModel.updateEndpoints(allEndpoints)
            updateTreeForViewMode(controller.getCurrentViewMode())
            updateStatusPanel("Found ${allEndpoints.size} endpoints")
            tree.repaint()
            return
        }
        
        // Get filtered endpoints from view model - always filter from allEndpoints
        viewModel.updateEndpoints(allEndpoints)
        val filteredEndpoints = viewModel.applyFilter(filterText, controller.getCurrentViewMode())
        
        // Handle empty filter results
        if (filteredEndpoints.isEmpty()) {
            // Clear tree and show "no matches" message
            rootNode.removeAllChildren()
            val noMatchesNode = DefaultMutableTreeNode("No endpoints match '$filterText'")
            rootNode.add(noMatchesNode)
            treeModel.reload()
            tree.expandPath(TreePath(rootNode.path))
            updateStatusPanel("No matches found for '$filterText'")
        } else {
            // Update currentEndpoints for display
            currentEndpoints = filteredEndpoints
            // Update view model with filtered endpoints
            viewModel.updateEndpoints(filteredEndpoints)
            // Update tree display
            updateTreeForViewMode(controller.getCurrentViewMode())
            updateStatusPanel("Filtered: ${filteredEndpoints.size} of ${allEndpoints.size} endpoints")
        }
        
        // Repaint tree to apply highlighting
        tree.repaint()
    }
    
    /**
     * Update tree display for the current view mode
     * Handles errors and falls back to Auto Discovery view if needed
     */
    private fun updateTreeForViewMode(viewMode: ViewMode) {
        try {
            // Update view model with current endpoints
            viewModel.updateEndpoints(currentEndpoints)
            
            // Check if we have enough metadata for the requested view mode
            if (!canSupportViewMode(viewMode)) {
                // Fall back to Auto Discovery view
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("HttpPal.Notifications")
                    .createNotification(
                        "View Mode Not Supported",
                        "Missing required metadata for ${viewMode.name} view. Falling back to Auto Discovery view.",
                        NotificationType.WARNING
                    )
                    .notify(project)
                
                // Switch combo box back to Auto Discovery
                viewModeComboBox.selectedItem = ViewMode.AUTO_DISCOVERY
                controller.switchViewMode(ViewMode.AUTO_DISCOVERY)
                return
            }
            
            // Get the tree structure for the current view mode
            val treeStructure = viewModel.getEndpointsForView(viewMode)
            
            // Update the tree display
            updateTreeWithStructure(treeStructure)
        } catch (e: Exception) {
            // Handle data transformation errors
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.ERROR,
                "Error updating tree for view mode: ${viewMode.name}",
                MapUtils.safeMapOf("error" to e.message)
            )
            
            // Show error notification
            NotificationGroupManager.getInstance()
                .getNotificationGroup("HttpPal.Notifications")
                .createNotification(
                    "View Update Error",
                    "Error organizing endpoints: ${e.message}. Displaying flat list.",
                    NotificationType.ERROR
                )
                .notify(project)
            
            // Fall back to flat list display
            displayEndpointsAsFlatList(currentEndpoints)
        }
    }
    
    /**
     * Check if the current endpoints support the requested view mode
     */
    private fun canSupportViewMode(viewMode: ViewMode): Boolean {
        return when (viewMode) {
            ViewMode.AUTO_DISCOVERY -> true // Always supported
            ViewMode.SWAGGER -> {
                // Check if we have any endpoints with Swagger metadata
                allEndpoints.any { it.tags.isNotEmpty() || it.operationId != null || it.summary != null }
            }
            ViewMode.CLASS_METHOD -> {
                // Check if we have class and method names
                allEndpoints.all { it.className.isNotBlank() && it.methodName.isNotBlank() }
            }
        }
    }
    
    /**
     * Display endpoints as a flat list when tree structure fails
     */
    private fun displayEndpointsAsFlatList(endpoints: List<DiscoveredEndpoint>) {
        rootNode.removeAllChildren()
        
        endpoints.sortedWith(
            compareBy<DiscoveredEndpoint> { it.method.ordinal }
                .thenBy { it.path }
        ).forEach { endpoint ->
            rootNode.add(DefaultMutableTreeNode(endpoint))
        }
        
        treeModel.reload()
        tree.expandPath(TreePath(rootNode.path))
    }
    
    /**
     * Update tree with a specific structure
     * Supports virtual scrolling for large datasets
     */
    private fun updateTreeWithStructure(structure: TreeNodeStructure) {
        // Clear existing nodes
        rootNode.removeAllChildren()
        
        // Determine if virtual scrolling should be enabled
        val totalEndpoints = currentEndpoints.size
        virtualScrollingEnabled = totalEndpoints > 100
        
        when (structure) {
            is TreeNodeStructure.AutoDiscoveryNode -> {
                // Update cache for virtual scrolling
                if (virtualScrollingEnabled) {
                    classEndpointsCache.clear()
                    classEndpointsCache.putAll(structure.groupedByClass)
                }
                
                // Display endpoints grouped by class
                structure.groupedByClass.forEach { (className, endpoints) ->
                    if (virtualScrollingEnabled) {
                        // Create virtualized node
                        val classNode = DefaultMutableTreeNode(ClassNodeData(className, endpoints.size, isVirtualized = true))
                        val placeholderNode = DefaultMutableTreeNode("Click to load ${endpoints.size} endpoints...")
                        classNode.add(placeholderNode)
                        rootNode.add(classNode)
                    } else {
                        // Create normal node
                        val classNode = DefaultMutableTreeNode(ClassNodeData(className, endpoints.size))
                        endpoints.sortedWith(
                            compareBy<DiscoveredEndpoint> { it.method.ordinal }
                                .thenBy { it.path }
                        ).forEach { endpoint ->
                            classNode.add(DefaultMutableTreeNode(endpoint))
                        }
                        rootNode.add(classNode)
                    }
                }
            }
            is TreeNodeStructure.SwaggerNode -> {
                // Display endpoints grouped by tags (two-level structure)
                // First level: tag or class name, Second level: endpoints
                structure.groupedByTags.forEach { (groupName, endpoints) ->
                    val groupNode = DefaultMutableTreeNode(TagNodeData(groupName, endpoints.size))
                    endpoints.sortedWith(
                        compareBy<DiscoveredEndpoint> { it.method.ordinal }
                            .thenBy { it.path }
                    ).forEach { endpoint ->
                        groupNode.add(DefaultMutableTreeNode(endpoint))
                    }
                    rootNode.add(groupNode)
                }
            }
            is TreeNodeStructure.ClassMethodNode -> {
                // Display endpoints grouped by class and method
                structure.groupedByClass.forEach { (className, methodsByClass) ->
                    val classNode = DefaultMutableTreeNode(ClassNodeData(className, methodsByClass.size))
                    methodsByClass.forEach { (methodName, endpoint) ->
                        val methodNode = DefaultMutableTreeNode("$methodName()")
                        methodNode.add(DefaultMutableTreeNode(endpoint))
                        classNode.add(methodNode)
                    }
                    rootNode.add(classNode)
                }
            }
        }
        
        // Refresh tree model
        treeModel.reload()
        
        // Expand root and first level nodes
        tree.expandPath(TreePath(rootNode.path))
        val maxNodesToExpand = if (virtualScrollingEnabled) 10 else minOf(10, rootNode.childCount)
        for (i in 0 until maxNodesToExpand) {
            val childNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
            tree.expandPath(TreePath(childNode.path))
        }
        
        // Setup virtual scrolling listener if needed
        if (virtualScrollingEnabled && structure is TreeNodeStructure.AutoDiscoveryNode) {
            setupVirtualScrollingListener()
        }
    }
    
    /**
     * Setup virtual scrolling listener for lazy loading
     */
    private fun setupVirtualScrollingListener() {
        // Remove existing listeners to avoid duplicates
        tree.treeExpansionListeners.forEach { listener ->
            if (listener.javaClass.name.contains("VirtualScrolling")) {
                tree.removeTreeExpansionListener(listener)
            }
        }
        
        // Add new listener
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
     * Implements requirement 3.8: Position preservation on auto-refresh
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
        
        // Get all current endpoints from discovery service
        val allEndpoints = endpointDiscoveryService.discoverEndpoints()
        // Selection restoration is handled in performEndpointsUpdate
        updateEndpointsDisplay(allEndpoints)
        
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
     * Implements requirement 3.7: Position preservation on manual refresh
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
                // Update UI on EDT thread (selection restoration is handled in performEndpointsUpdate)
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
     * Handles empty endpoint lists
     * Preserves selection after update
     */
    private fun performEndpointsUpdate(endpoints: List<DiscoveredEndpoint>) {
        // Update allEndpoints with the new full list
        allEndpoints = endpoints
        
        // If there's an active filter, re-apply it to the new endpoints
        val filterText = filterTextField.text
        if (filterText.isNotBlank()) {
            // Re-apply filter on new endpoints
            viewModel.updateEndpoints(allEndpoints)
            val filteredEndpoints = viewModel.applyFilter(filterText, controller.getCurrentViewMode())
            currentEndpoints = filteredEndpoints
        } else {
            // No filter active, show all endpoints
            currentEndpoints = allEndpoints
        }
        
        // Handle empty endpoint list
        if (currentEndpoints.isEmpty()) {
            rootNode.removeAllChildren()
            val noEndpointsNode = if (filterText.isNotBlank()) {
                DefaultMutableTreeNode("No endpoints match '$filterText'")
            } else {
                DefaultMutableTreeNode("No endpoints found")
            }
            rootNode.add(noEndpointsNode)
            treeModel.reload()
            tree.expandPath(TreePath(rootNode.path))
            updateStatusPanel(if (filterText.isNotBlank()) {
                "No matches found for '$filterText'"
            } else {
                "No endpoints discovered"
            })
            return
        }
        
        // Update view model with current endpoints (filtered or all)
        viewModel.updateEndpoints(currentEndpoints)
        
        // Update tree display based on current view mode
        updateTreeForViewMode(controller.getCurrentViewMode())
        
        // Try to restore selection after tree update
        val restored = controller.restoreSelection(currentEndpoints)
        if (restored) {
            // Find and select the matching endpoint in the tree
            val matchingEndpoint = controller.findMatchingEndpoint(currentEndpoints)
            if (matchingEndpoint != null) {
                selectEndpointInTree(matchingEndpoint)
            }
        }
        
        updateStatusPanel(if (filterText.isNotBlank()) {
            "Filtered: ${currentEndpoints.size} of ${allEndpoints.size} endpoints"
        } else {
            "Found ${allEndpoints.size} endpoints"
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
     * Select an endpoint in the tree
     * Implements requirement 3.2, 3.3, 3.4, 3.5: Position preservation
     */
    private fun selectEndpointInTree(endpoint: DiscoveredEndpoint) {
        // Find the endpoint in the tree
        findEndpointNode(rootNode, endpoint)?.let { endpointNode ->
            val path = TreePath(endpointNode.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }
    
    /**
     * Recursively find an endpoint node in the tree
     */
    private fun findEndpointNode(node: DefaultMutableTreeNode, endpoint: DiscoveredEndpoint): DefaultMutableTreeNode? {
        // Check if this node contains the endpoint
        if (node.userObject is DiscoveredEndpoint) {
            val nodeEndpoint = node.userObject as DiscoveredEndpoint
            if (nodeEndpoint.path == endpoint.path && nodeEndpoint.method == endpoint.method) {
                return node
            }
        }
        
        // Recursively search children
        for (i in 0 until node.childCount) {
            val childNode = node.getChildAt(i) as DefaultMutableTreeNode
            val found = findEndpointNode(childNode, endpoint)
            if (found != null) {
                return found
            }
        }
        
        return null
    }
    
    /**
     * Show notification when selection restoration fails
     * Implements requirement 3.6: Notification on restoration failure
     */
    private fun showSelectionRestorationFailedNotification() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("HttpPal.Notifications")
            .createNotification(
                "Selection Not Restored",
                "Previously selected endpoint no longer exists",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
    
    /**
     * Handle environment change
     * Implements requirement 3.9: Position preservation on environment change
     */
    fun onEnvironmentChanged(environment: Environment?) {
        // Refresh endpoints when environment changes
        // This may affect endpoint resolution and display
        val endpoints = endpointDiscoveryService.discoverEndpoints()
        // Selection restoration is handled in performEndpointsUpdate
        updateEndpointsDisplay(endpoints)
        
        updateStatusPanel("Environment changed: ${environment?.name ?: "None"}")
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
     * Get all discovered endpoints (unfiltered)
     */
    fun getAllEndpoints(): List<DiscoveredEndpoint> {
        return allEndpoints.toList()
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
                val jumpToSourceAction = JMenuItem(HttpPalBundle.message("context.jump.to.source"))
                jumpToSourceAction.addActionListener {
                    navigateToSource(userObject)
                }
                popup.add(jumpToSourceAction)
                popup.addSeparator()
                
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
     * Navigate to the source code of the endpoint
     */
    private fun navigateToSource(endpoint: DiscoveredEndpoint) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(endpoint.sourceFile)
        if (virtualFile == null) {
            // Show notification if file not found
            NotificationGroupManager.getInstance()
                .getNotificationGroup("HttpPal.Notifications")
                .createNotification(
                    "Source Not Found",
                    "Source file not found: ${endpoint.sourceFile}",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }
        
        ApplicationManager.getApplication().invokeLater {
            try {
                // Open file and navigate to line
                val fileEditorManager = FileEditorManager.getInstance(project)
                val editor = fileEditorManager.openTextEditor(
                    OpenFileDescriptor(project, virtualFile, endpoint.lineNumber - 1, 0),
                    true
                )
                
                if (editor != null) {
                    // Scroll to center and make line visible
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            } catch (e: Exception) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("HttpPal.Notifications")
                    .createNotification(
                        "Navigation Error",
                        "Failed to navigate to source: ${e.message}",
                        NotificationType.ERROR
                    )
                    .notify(project)
            }
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
     * Data class for tag/group nodes in Swagger view
     */
    private data class TagNodeData(
        val tagName: String,
        val endpointCount: Int
    ) {
        override fun toString(): String {
            return "$tagName ($endpointCount endpoints)"
        }
    }
    
    /**
     * Custom tree cell renderer for endpoints with highlighting support
     */
    private inner class EndpointTreeCellRenderer : DefaultTreeCellRenderer() {
        
        private var currentFilterText: String = ""
        
        fun setFilterText(filterText: String) {
            currentFilterText = filterText
        }
        
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
                    
                    // 根据当前视图模式决定显示内容
                    val displayText = when (controller.getCurrentViewMode()) {
                        ViewMode.SWAGGER -> {
                            // Swagger 视图：优先显示 summary，其次 description，最后才是 path
                            val description = when {
                                !userObject.summary.isNullOrBlank() -> userObject.summary
                                !userObject.description.isNullOrBlank() -> userObject.description
                                else -> userObject.path
                            }
                            "$sourcePrefix${userObject.method.name} $description"
                        }
                        else -> {
                            // 其他视图：显示 path
                            "$sourcePrefix${userObject.method.name} ${userObject.path}"
                        }
                    }
                    
                    // Apply highlighting if filter is active
                    if (currentFilterText.isNotBlank() && !sel) {
                        text = highlightText(displayText, currentFilterText)
                    } else {
                        text = displayText
                    }
                    
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
                    val displayText = userObject.toString()
                    
                    // Apply highlighting if filter is active
                    if (currentFilterText.isNotBlank() && !sel) {
                        text = highlightText(displayText, currentFilterText)
                    } else {
                        text = displayText
                    }
                    
                    icon = createFolderIcon(Color(0, 100, 0)) // Green folder icon for class nodes
                    toolTipText = "Controller class: ${userObject.className}"
                    
                    // Customize appearance for class nodes
                    if (!sel) {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD) // Bold for class nodes
                        foreground = Color(0, 100, 0) // Dark green for class nodes
                    }
                }
                is TagNodeData -> {
                    val displayText = userObject.toString()
                    
                    // Apply highlighting if filter is active
                    if (currentFilterText.isNotBlank() && !sel) {
                        text = highlightText(displayText, currentFilterText)
                    } else {
                        text = displayText
                    }
                    
                    icon = createTagIcon()
                    toolTipText = "Tag/Group: ${userObject.tagName}"
                    
                    // Customize appearance for tag nodes
                    if (!sel) {
                        font = font.deriveFont(font.style or java.awt.Font.BOLD) // Bold for tag nodes
                        foreground = Color(0, 0, 180) // Blue for tag nodes
                    }
                }
                is String -> {
                    // Handle special node types (Tag nodes, Method nodes, etc.)
                    val displayText = userObject
                    
                    // Apply highlighting if filter is active
                    if (currentFilterText.isNotBlank() && !sel) {
                        text = highlightText(displayText, currentFilterText)
                    } else {
                        text = displayText
                    }
                    
                    // Determine icon and tooltip based on text content
                    when {
                        displayText.startsWith("Tag: ") -> {
                            icon = createTagIcon()
                            toolTipText = "Swagger/OpenAPI tag grouping"
                            if (!sel) {
                                font = font.deriveFont(font.style or java.awt.Font.BOLD)
                                foreground = Color(0, 0, 180) // Blue for tags
                            }
                        }
                        displayText.endsWith("()") -> {
                            icon = createMethodNodeIcon()
                            toolTipText = "Method: $displayText"
                            if (!sel) {
                                foreground = Color(100, 50, 150) // Purple for methods
                            }
                        }
                        else -> {
                            icon = null
                            toolTipText = null
                        }
                    }
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
        
        /**
         * Create folder icon for class nodes
         */
        private fun createFolderIcon(color: Color): Icon {
            return object : Icon {
                override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                    g?.let {
                        it.color = color
                        // Draw folder shape
                        it.fillRect(x + 2, y + 6, 12, 8)
                        it.fillRect(x + 2, y + 4, 6, 2)
                    }
                }
                
                override fun getIconWidth(): Int = 16
                override fun getIconHeight(): Int = 16
            }
        }
        
        /**
         * Create tag icon for Swagger tag nodes
         */
        private fun createTagIcon(): Icon {
            return object : Icon {
                override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                    g?.let {
                        it.color = Color(0, 0, 180)
                        // Draw tag shape (like a label)
                        val xPoints = intArrayOf(x + 2, x + 12, x + 14, x + 12, x + 2)
                        val yPoints = intArrayOf(y + 4, y + 4, y + 8, y + 12, y + 12)
                        it.fillPolygon(xPoints, yPoints, 5)
                        
                        // Draw hole in tag
                        it.color = Color.WHITE
                        it.fillOval(x + 4, y + 7, 2, 2)
                    }
                }
                
                override fun getIconWidth(): Int = 16
                override fun getIconHeight(): Int = 16
            }
        }
        
        /**
         * Create method node icon for Class/Method view
         */
        private fun createMethodNodeIcon(): Icon {
            return object : Icon {
                override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
                    g?.let {
                        it.color = Color(100, 50, 150)
                        // Draw method symbol (M)
                        it.fillRect(x + 3, y + 4, 2, 8)
                        it.fillRect(x + 5, y + 5, 2, 2)
                        it.fillRect(x + 7, y + 4, 2, 8)
                        it.fillRect(x + 9, y + 5, 2, 2)
                        it.fillRect(x + 11, y + 4, 2, 8)
                    }
                }
                
                override fun getIconWidth(): Int = 16
                override fun getIconHeight(): Int = 16
            }
        }
        
        /**
         * Highlight matching text in the display string
         * Uses HTML to apply background color to matching keywords
         */
        private fun highlightText(text: String, filterText: String): String {
            if (filterText.isBlank()) {
                return text
            }
            
            // Parse keywords from filter text
            val keywords = filterText.trim()
                .split("\\s+".toRegex())
                .filter { it.isNotEmpty() }
            
            if (keywords.isEmpty()) {
                return text
            }
            
            // Build HTML with highlighted keywords
            var highlightedText = text
            keywords.forEach { keyword ->
                // Case-insensitive replacement with HTML highlighting
                val regex = Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
                highlightedText = regex.replace(highlightedText) { matchResult ->
                    "<span style='background-color: #FFFF00; color: #000000;'>${matchResult.value}</span>"
                }
            }
            
            return "<html>$highlightedText</html>"
        }
    }
}