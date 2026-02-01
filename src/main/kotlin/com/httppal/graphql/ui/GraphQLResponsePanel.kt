package com.httppal.graphql.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.httppal.graphql.model.GraphQLResponse
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel

/**
 * Panel for displaying GraphQL responses with separate tabs for data and errors.
 */
class GraphQLResponsePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tabbedPane = JBTabbedPane()
    private val dataEditor: EditorEx
    private val errorsEditor: EditorEx
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    // Status label
    private val statusLabel = JBLabel("")

    init {
        dataEditor = createResponseEditor()
        errorsEditor = createResponseEditor()

        // Add tabs
        tabbedPane.addTab("Data", dataEditor.component)
        tabbedPane.addTab("Errors", errorsEditor.component)

        // Status panel at the top
        val statusPanel = JPanel(BorderLayout())
        statusPanel.add(statusLabel, BorderLayout.WEST)

        add(statusPanel, BorderLayout.NORTH)
        add(tabbedPane, BorderLayout.CENTER)

        // Initially show the Data tab
        tabbedPane.selectedIndex = 0
    }

    private fun createResponseEditor(): EditorEx {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
        val editor = editorFactory.createEditor(document, project) as EditorEx

        // Configure editor settings for read-only display
        val settings = editor.settings
        settings.isLineNumbersShown = true
        settings.isAutoCodeFoldingEnabled = true
        settings.isFoldingOutlineShown = true
        settings.isAllowSingleLogicalLineFolding = false
        settings.isRightMarginShown = false
        settings.additionalLinesCount = 3

        // Set read-only
        editor.document.setReadOnly(true)

        // Use JSON syntax highlighting
        val jsonFileType = FileTypeManager.getInstance().getFileTypeByExtension("json")
        val highlighterFactory = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
        editor.highlighter = highlighterFactory.createEditorHighlighter(project, jsonFileType)

        return editor
    }

    /**
     * Display a GraphQL response.
     */
    fun displayResponse(response: GraphQLResponse) {
        // Update status label
        if (response.isSuccessful()) {
            statusLabel.text = "Success"
            statusLabel.foreground = JBColor(Color(0, 128, 0), Color(0, 200, 0))
        } else {
            statusLabel.text = "Errors"
            statusLabel.foreground = JBColor.RED
        }

        // Display data
        val dataText = if (response.data != null) {
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response.data)
            } catch (e: Exception) {
                "Failed to format data: ${e.message}\n\nRaw: ${response.data}"
            }
        } else {
            "(No data)"
        }

        WriteCommandAction.runWriteCommandAction(project) {
            dataEditor.document.setReadOnly(false)
            // Normalize line separators to \n for IntelliJ Platform
            dataEditor.document.setText(dataText.replace("\r\n", "\n"))
            dataEditor.document.setReadOnly(true)
        }

        // Display errors
        val errorsText = if (response.errors != null && response.errors.isNotEmpty()) {
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response.errors)
            } catch (e: Exception) {
                response.errors.joinToString("\n\n") { error ->
                    buildString {
                        append("Message: ${error.message}")
                        if (error.locations != null) {
                            append("\nLocations: ${error.locations}")
                        }
                        if (error.path != null) {
                            append("\nPath: ${error.path.joinToString(" → ")}")
                        }
                        if (error.extensions != null) {
                            append("\nExtensions: ${error.extensions}")
                        }
                    }
                }
            }
        } else {
            "(No errors)"
        }

        WriteCommandAction.runWriteCommandAction(project) {
            errorsEditor.document.setReadOnly(false)
            // Normalize line separators to \n for IntelliJ Platform
            errorsEditor.document.setText(errorsText.replace("\r\n", "\n"))
            errorsEditor.document.setReadOnly(true)
        }

        // Auto-switch to errors tab if there are errors
        if (response.hasErrors()) {
            tabbedPane.selectedIndex = 1 // Switch to Errors tab
        } else {
            tabbedPane.selectedIndex = 0 // Stay on Data tab
        }
    }

    /**
     * Clear the response display.
     */
    fun clear() {
        statusLabel.text = ""
        WriteCommandAction.runWriteCommandAction(project) {
            dataEditor.document.setReadOnly(false)
            dataEditor.document.setText("")
            dataEditor.document.setReadOnly(true)
            errorsEditor.document.setReadOnly(false)
            errorsEditor.document.setText("")
            errorsEditor.document.setReadOnly(true)
        }
        tabbedPane.selectedIndex = 0
    }

    /**
     * Dispose the editors when the panel is no longer needed.
     */
    fun dispose() {
        EditorFactory.getInstance().releaseEditor(dataEditor)
        EditorFactory.getInstance().releaseEditor(errorsEditor)
    }
}
