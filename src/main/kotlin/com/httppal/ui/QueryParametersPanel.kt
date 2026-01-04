package com.httppal.ui

import com.httppal.util.HttpPalBundle
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.net.URLDecoder
import java.net.URLEncoder
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Panel for editing query parameters in a Postman-style table format.
 * Supports:
 * - Key-value pairs with enable/disable checkboxes
 * - Bi-directional sync with URL
 * - URL encoding/decoding
 */
class QueryParametersPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val tableModel = QueryParametersTableModel()
    private val table = JBTable(tableModel)
    
    // Callback when parameters change
    var onParametersChanged: ((Map<String, String>) -> Unit)? = null
    
    // Flag to prevent recursive updates
    private var isUpdatingFromUrl = false
    
    init {
        setupUI()
        setupTable()
    }
    
    private fun setupUI() {
        border = JBUI.Borders.empty(5, 0)
        
        // Table scroll pane
        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(-1, 150)
        scrollPane.minimumSize = Dimension(-1, 100)
        scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0)
        add(scrollPane, BorderLayout.CENTER)
        
        // Control buttons panel
        val controlPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        
        val addButton = JButton(HttpPalBundle.message("button.add.header"))
        addButton.addActionListener { addRow() }
        
        val removeButton = JButton(HttpPalBundle.message("button.remove.selected"))
        removeButton.addActionListener { removeSelectedRows() }
        
        val clearButton = JButton(HttpPalBundle.message("button.clear.all"))
        clearButton.addActionListener { clearAll() }
        
        controlPanel.add(addButton)
        controlPanel.add(removeButton)
        controlPanel.add(clearButton)
        
        add(controlPanel, BorderLayout.SOUTH)
    }
    
    private fun setupTable() {
        table.fillsViewportHeight = true
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.rowHeight = 28
        
        // Configure column widths
        val columnModel = table.columnModel
        columnModel.getColumn(0).preferredWidth = 40   // Enabled checkbox
        columnModel.getColumn(0).maxWidth = 50
        columnModel.getColumn(1).preferredWidth = 150  // Key
        columnModel.getColumn(2).preferredWidth = 200  // Value
        columnModel.getColumn(3).preferredWidth = 150  // Description
        
        // Checkbox renderer and editor for enabled column
        columnModel.getColumn(0).cellRenderer = CheckboxRenderer()
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(JCheckBox())
        
        // Add initial empty row
        tableModel.addEmptyRow()
    }
    
    /**
     * Get current query parameters (only enabled ones)
     */
    fun getParameters(): Map<String, String> {
        return tableModel.getEnabledParameters()
    }
    
    /**
     * Get all parameters including disabled ones
     */
    fun getAllParameters(): List<QueryParameter> {
        return tableModel.getAllParameters()
    }
    
    /**
     * Set parameters from a map
     */
    fun setParameters(params: Map<String, String>) {
        tableModel.clear()
        params.forEach { (key, value) ->
            tableModel.addRow(QueryParameter(true, key, value, ""))
        }
        tableModel.addEmptyRow()
        fireParametersChanged()
    }
    
    /**
     * Set parameters from a list (preserves enabled state and description)
     */
    fun setParametersList(params: List<QueryParameter>) {
        tableModel.clear()
        params.forEach { param ->
            tableModel.addRow(param)
        }
        tableModel.addEmptyRow()
        fireParametersChanged()
    }

    /**
     * Merge parameters list into existing parameters (updates description, default values)
     */
    fun mergeParametersList(params: List<QueryParameter>) {
        params.forEach { param ->
            tableModel.updateOrAddParameter(param)
        }
        // Ensure there is always an empty row at end
        if (tableModel.rowCount == 0 || tableModel.getAllParameters().isNotEmpty()) {
             // Simple fix: Remove empty rows then add one.
             tableModel.removeEmptyRows()
             tableModel.addEmptyRow()
        }
        fireParametersChanged()
    }
    
    /**
     * Parse query parameters from URL and populate table
     */
    fun syncFromUrl(url: String) {
        if (isUpdatingFromUrl) return
        isUpdatingFromUrl = true
        
        try {
            val queryStart = url.indexOf('?')
            if (queryStart == -1) {
                tableModel.clear()
                tableModel.addEmptyRow()
                return
            }
            
            val queryString = url.substring(queryStart + 1)
            val params = parseQueryString(queryString)
            
            tableModel.clear()
            params.forEach { (key, value) ->
                tableModel.addRow(QueryParameter(true, key, value, ""))
            }
            tableModel.addEmptyRow()
        } finally {
            isUpdatingFromUrl = false
        }
    }
    
    /**
     * Generate query string from current parameters
     */
    fun toQueryString(): String {
        val params = getParameters()
        if (params.isEmpty()) return ""
        
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }
    
    /**
     * Append query parameters to a URL
     */
    fun appendToUrl(baseUrl: String): String {
        val queryString = toQueryString()
        if (queryString.isEmpty()) return baseUrl
        
        // Remove existing query string if present
        val baseWithoutQuery = if (baseUrl.contains("?")) {
            baseUrl.substring(0, baseUrl.indexOf("?"))
        } else {
            baseUrl
        }
        
        return "$baseWithoutQuery?$queryString"
    }
    
    private fun parseQueryString(queryString: String): List<Pair<String, String>> {
        if (queryString.isBlank()) return emptyList()
        
        return queryString.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val key = try {
                    URLDecoder.decode(parts[0], "UTF-8")
                } catch (e: Exception) {
                    parts[0]
                }
                val value = if (parts.size > 1) {
                    try {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } catch (e: Exception) {
                        parts[1]
                    }
                } else ""
                Pair(key, value)
            } else null
        }
    }
    
    private fun addRow() {
        tableModel.addEmptyRow()
    }
    
    private fun removeSelectedRows() {
        val selectedRows = table.selectedRows.sortedDescending()
        selectedRows.forEach { row ->
            tableModel.removeRow(row)
        }
        if (tableModel.rowCount == 0) {
            tableModel.addEmptyRow()
        }
    }
    
    private fun clearAll() {
        tableModel.clear()
        tableModel.addEmptyRow()
        fireParametersChanged()
    }
    
    private fun fireParametersChanged() {
        if (!isUpdatingFromUrl) {
            onParametersChanged?.invoke(getParameters())
        }
    }
    
    /**
     * Data class for query parameter
     */
    data class QueryParameter(
        var enabled: Boolean = true,
        var key: String = "",
        var value: String = "",
        var description: String = ""
    )
    
    /**
     * Table model for query parameters
     */
    private inner class QueryParametersTableModel : AbstractTableModel() {
        private val columns = arrayOf("", "Key", "Value", "Description")
        private val data = mutableListOf<QueryParameter>()
        
        override fun getRowCount(): Int = data.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) Boolean::class.java else String::class.java
        }
        
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
        
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val row = data[rowIndex]
            return when (columnIndex) {
                0 -> row.enabled
                1 -> row.key
                2 -> row.value
                3 -> row.description
                else -> null
            }
        }
        
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val row = data[rowIndex]
            when (columnIndex) {
                0 -> row.enabled = aValue as? Boolean ?: true
                1 -> row.key = aValue?.toString() ?: ""
                2 -> row.value = aValue?.toString() ?: ""
                3 -> row.description = aValue?.toString() ?: ""
            }
            fireTableCellUpdated(rowIndex, columnIndex)
            
            // Auto-add new row if last row is being edited with content
            if (rowIndex == data.size - 1 && row.key.isNotBlank()) {
                addEmptyRow()
            }
            
            fireParametersChanged()
        }
        
        fun addEmptyRow() {
            data.add(QueryParameter())
            fireTableRowsInserted(data.size - 1, data.size - 1)
        }
        
        fun addRow(param: QueryParameter) {
            data.add(param)
            fireTableRowsInserted(data.size - 1, data.size - 1)
        }
        
        fun removeRow(rowIndex: Int) {
            if (rowIndex in data.indices) {
                data.removeAt(rowIndex)
                fireTableRowsDeleted(rowIndex, rowIndex)
            }
        }
        
        fun clear() {
            val size = data.size
            data.clear()
            if (size > 0) {
                fireTableRowsDeleted(0, size - 1)
            }
        }
        
        fun removeEmptyRows() {
            val toRemove = mutableListOf<Int>()
            for (i in 0 until data.size) {
                 if (data[i].key.isBlank() && data[i].value.isBlank() && data[i].description.isBlank()) {
                     toRemove.add(i)
                 }
            }
            toRemove.sortedDescending().forEach { removeRow(it) }
        }

        fun updateOrAddParameter(param: QueryParameter) {
            val existingIndex = data.indexOfFirst { it.key == param.key }
            if (existingIndex != -1) {
                // Enrich existing
                val existing = data[existingIndex]
                existing.description = if (existing.description.isBlank()) param.description else existing.description
                existing.value = if (existing.value.isBlank()) param.value else existing.value
                fireTableRowsUpdated(existingIndex, existingIndex)
            } else {
                data.add(param)
                fireTableRowsInserted(data.size - 1, data.size - 1)
            }
        }
        
        fun getEnabledParameters(): Map<String, String> {
            return data
                .filter { it.enabled && it.key.isNotBlank() }
                .associate { it.key to it.value }
        }
        
        fun getAllParameters(): List<QueryParameter> {
            return data.filter { it.key.isNotBlank() }.toList()
        }
    }
    
    /**
     * Checkbox renderer for the enabled column
     */
    private class CheckboxRenderer : TableCellRenderer {
        private val checkbox = JCheckBox()
        
        init {
            checkbox.horizontalAlignment = SwingConstants.CENTER
        }
        
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            checkbox.isSelected = value as? Boolean ?: false
            checkbox.background = if (isSelected) table?.selectionBackground else table?.background
            return checkbox
        }
    }
}
