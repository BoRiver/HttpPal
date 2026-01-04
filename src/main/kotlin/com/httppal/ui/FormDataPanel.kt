package com.httppal.ui

import com.httppal.util.HttpPalBundle
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Panel for editing multipart/form-data with support for multiple file uploads.
 * Similar to Postman's form-data body type.
 */
class FormDataPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val tableModel = FormDataTableModel()
    private val table = JBTable(tableModel)
    
    // Callback when form data changes
    var onFormDataChanged: ((List<FormDataEntry>) -> Unit)? = null
    
    init {
        setupUI()
        setupTable()
    }
    
    private fun setupUI() {
        border = JBUI.Borders.empty(5, 0)
        
        // Header with info
        val headerPanel = JPanel(BorderLayout())
        headerPanel.isOpaque = false
        headerPanel.border = JBUI.Borders.empty(0, 5, 5, 5)
        val infoLabel = JLabel("Add form fields and files for multipart/form-data requests")
        infoLabel.foreground = com.intellij.ui.JBColor.GRAY
        infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC, 11f)
        headerPanel.add(infoLabel, BorderLayout.WEST)
        add(headerPanel, BorderLayout.NORTH)
        
        // Table - use flexible height
        val scrollPane = JBScrollPane(table)
        scrollPane.preferredSize = Dimension(-1, 200)
        scrollPane.minimumSize = Dimension(-1, 120)
        scrollPane.border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 1, 0, 1, 0)
        add(scrollPane, BorderLayout.CENTER)
        
        // Control buttons
        val controlPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5))
        
        val addTextButton = JButton("Add Text")
        addTextButton.addActionListener { addTextRow() }
        
        val addFileButton = JButton("Add File")
        addFileButton.addActionListener { addFileRow() }
        
        val removeButton = JButton(HttpPalBundle.message("button.remove.selected"))
        removeButton.addActionListener { removeSelectedRows() }
        
        val clearButton = JButton(HttpPalBundle.message("button.clear.all"))
        clearButton.addActionListener { clearAll() }
        
        controlPanel.add(addTextButton)
        controlPanel.add(addFileButton)
        controlPanel.add(Box.createHorizontalStrut(10))
        controlPanel.add(removeButton)
        controlPanel.add(clearButton)
        
        add(controlPanel, BorderLayout.SOUTH)
    }
    
    private fun setupTable() {
        table.fillsViewportHeight = true
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.rowHeight = 30
        
        // Configure columns
        val columnModel = table.columnModel
        columnModel.getColumn(0).preferredWidth = 40   // Enabled
        columnModel.getColumn(0).maxWidth = 50
        columnModel.getColumn(1).preferredWidth = 120  // Key
        columnModel.getColumn(2).preferredWidth = 200  // Value
        columnModel.getColumn(3).preferredWidth = 80   // Type
        columnModel.getColumn(4).preferredWidth = 80   // Action (file chooser button)
        columnModel.getColumn(4).maxWidth = 100
        
        // Checkbox renderer for enabled column
        columnModel.getColumn(0).cellRenderer = CheckboxRenderer()
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(JCheckBox())
        
        // Type combo renderer and editor
        columnModel.getColumn(3).cellRenderer = TypeRenderer()
        columnModel.getColumn(3).cellEditor = TypeEditor()
        
        // Action button for file selection
        columnModel.getColumn(4).cellRenderer = ActionButtonRenderer()
        columnModel.getColumn(4).cellEditor = ActionButtonEditor()
        
        // Add initial empty row
        tableModel.addRow(FormDataEntry(true, "", "", FormDataType.TEXT))
    }
    
    /**
     * Get all enabled form data entries
     */
    fun getFormData(): List<FormDataEntry> {
        return tableModel.getEnabledEntries()
    }
    
    /**
     * Get all form data entries including disabled
     */
    fun getAllFormData(): List<FormDataEntry> {
        return tableModel.getAllEntries()
    }
    
    /**
     * Set form data entries
     */
    fun setFormData(entries: List<FormDataEntry>) {
        tableModel.clear()
        entries.forEach { entry ->
            tableModel.addRow(entry)
        }
        if (tableModel.rowCount == 0) {
            tableModel.addRow(FormDataEntry(true, "", "", FormDataType.TEXT))
        }
        onFormDataChanged?.invoke(getFormData())
    }
    
    /**
     * Check if any file entries exist
     */
    fun hasFiles(): Boolean {
        return tableModel.getAllEntries().any { it.type == FormDataType.FILE && it.value.isNotBlank() }
    }
    
    /**
     * Get list of file paths
     */
    fun getFilePaths(): List<String> {
        return tableModel.getEnabledEntries()
            .filter { it.type == FormDataType.FILE && it.value.isNotBlank() }
            .map { it.value }
    }
    
    /**
     * Validate all file entries exist
     */
    fun validateFiles(): List<String> {
        val errors = mutableListOf<String>()
        tableModel.getEnabledEntries()
            .filter { it.type == FormDataType.FILE && it.value.isNotBlank() }
            .forEach { entry ->
                val file = File(entry.value)
                if (!file.exists()) {
                    errors.add("File not found: ${entry.value}")
                } else if (!file.canRead()) {
                    errors.add("Cannot read file: ${entry.value}")
                }
            }
        return errors
    }
    
    private fun addTextRow() {
        tableModel.addRow(FormDataEntry(true, "", "", FormDataType.TEXT))
    }
    
    private fun addFileRow() {
        tableModel.addRow(FormDataEntry(true, "", "", FormDataType.FILE))
    }
    
    private fun removeSelectedRows() {
        val selectedRows = table.selectedRows.sortedDescending()
        selectedRows.forEach { row ->
            tableModel.removeRow(row)
        }
        if (tableModel.rowCount == 0) {
            tableModel.addRow(FormDataEntry(true, "", "", FormDataType.TEXT))
        }
    }
    
    private fun clearAll() {
        tableModel.clear()
        tableModel.addRow(FormDataEntry(true, "", "", FormDataType.TEXT))
        onFormDataChanged?.invoke(getFormData())
    }
    
    private fun fireFormDataChanged() {
        onFormDataChanged?.invoke(getFormData())
    }
    
    private fun selectFile(rowIndex: Int) {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, true)
            .withTitle("Select File(s)")
            .withDescription("Choose one or more files to upload")
        
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            if (files.isNotEmpty()) {
                if (files.size == 1) {
                    // Single file - update current row
                    tableModel.setValueAt(files[0].path, rowIndex, 2)
                } else {
                    // Multiple files - update current row with first file, add new rows for rest
                    tableModel.setValueAt(files[0].path, rowIndex, 2)
                    val baseKey = tableModel.getValueAt(rowIndex, 1)?.toString() ?: "file"
                    
                    files.drop(1).forEachIndexed { index, file ->
                        val key = if (baseKey.isNotBlank()) "${baseKey}[${index + 1}]" else "file[${index + 1}]"
                        tableModel.addRow(FormDataEntry(true, key, file.path, FormDataType.FILE))
                    }
                }
                fireFormDataChanged()
            }
        }
    }
    
    /**
     * Form data entry type
     */
    enum class FormDataType {
        TEXT, FILE
    }
    
    /**
     * Form data entry
     */
    data class FormDataEntry(
        var enabled: Boolean = true,
        var key: String = "",
        var value: String = "",
        var type: FormDataType = FormDataType.TEXT,
        var contentType: String? = null
    )
    
    /**
     * Table model for form data
     */
    private inner class FormDataTableModel : AbstractTableModel() {
        private val columns = arrayOf("", "Key", "Value", "Type", "")
        private val data = mutableListOf<FormDataEntry>()
        
        override fun getRowCount(): Int = data.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> Boolean::class.java
                3 -> FormDataType::class.java
                else -> String::class.java
            }
        }
        
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            // Column 2 (value) is only editable for TEXT type
            if (columnIndex == 2) {
                return data[rowIndex].type == FormDataType.TEXT
            }
            return true
        }
        
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val row = data[rowIndex]
            return when (columnIndex) {
                0 -> row.enabled
                1 -> row.key
                2 -> if (row.type == FormDataType.FILE && row.value.isNotBlank()) {
                    File(row.value).name  // Show just filename for files
                } else {
                    row.value
                }
                3 -> row.type
                4 -> if (row.type == FormDataType.FILE) "Browse..." else ""
                else -> null
            }
        }
        
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val row = data[rowIndex]
            when (columnIndex) {
                0 -> row.enabled = aValue as? Boolean ?: true
                1 -> row.key = aValue?.toString() ?: ""
                2 -> row.value = aValue?.toString() ?: ""
                3 -> {
                    val newType = aValue as? FormDataType ?: FormDataType.TEXT
                    if (newType != row.type) {
                        row.type = newType
                        row.value = ""  // Clear value when switching type
                    }
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
            fireFormDataChanged()
        }
        
        fun addRow(entry: FormDataEntry) {
            data.add(entry)
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
        
        fun getEnabledEntries(): List<FormDataEntry> {
            return data.filter { it.enabled && it.key.isNotBlank() }
        }
        
        fun getAllEntries(): List<FormDataEntry> {
            return data.filter { it.key.isNotBlank() }.toList()
        }
    }
    
    /**
     * Checkbox renderer
     */
    private class CheckboxRenderer : TableCellRenderer {
        private val checkbox = JCheckBox()
        
        init {
            checkbox.horizontalAlignment = SwingConstants.CENTER
        }
        
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            checkbox.isSelected = value as? Boolean ?: false
            checkbox.background = if (isSelected) table?.selectionBackground else table?.background
            return checkbox
        }
    }
    
    /**
     * Type dropdown renderer
     */
    private class TypeRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            text = (value as? FormDataType)?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Text"
            return component
        }
    }
    
    /**
     * Type dropdown editor
     */
    private class TypeEditor : DefaultCellEditor(JComboBox(FormDataType.values())) {
        init {
            val combo = component as JComboBox<*>
            combo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    text = (value as? FormDataType)?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Text"
                    return this
                }
            }
        }
    }
    
    /**
     * Action button renderer for file selection
     */
    private inner class ActionButtonRenderer : TableCellRenderer {
        private val button = JButton("Browse...")
        private val emptyLabel = JLabel("")
        
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val entry = tableModel.getAllEntries().getOrNull(row)
            return if (entry?.type == FormDataType.FILE) {
                button.apply {
                    background = if (isSelected) table?.selectionBackground else table?.background
                }
            } else {
                emptyLabel
            }
        }
    }
    
    /**
     * Action button editor for file selection
     */
    private inner class ActionButtonEditor : AbstractCellEditor(), TableCellEditor {
        private val button = JButton("Browse...")
        private var currentRow = -1
        
        init {
            button.addActionListener {
                if (currentRow >= 0) {
                    selectFile(currentRow)
                }
                fireEditingStopped()
            }
        }
        
        override fun getTableCellEditorComponent(
            table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            currentRow = row
            return button
        }
        
        override fun getCellEditorValue(): Any = ""
    }
}
