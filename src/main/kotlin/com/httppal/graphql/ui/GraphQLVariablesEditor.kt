package com.httppal.graphql.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Editor component for GraphQL variables (JSON format).
 */
class GraphQLVariablesEditor(private val project: Project) : JPanel(BorderLayout()) {

    private val editor: EditorEx

    init {
        editor = createEditor()
        add(editor.component, BorderLayout.CENTER)
    }

    private fun createEditor(): EditorEx {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument("")
        val newEditor = editorFactory.createEditor(document, project) as EditorEx

        // Configure editor settings
        val settings = newEditor.settings
        settings.isLineNumbersShown = true
        settings.isAutoCodeFoldingEnabled = true
        settings.isFoldingOutlineShown = true
        settings.isAllowSingleLogicalLineFolding = false
        settings.isRightMarginShown = false
        settings.additionalLinesCount = 3

        // Use JSON syntax highlighting
        val jsonFileType = FileTypeManager.getInstance().getFileTypeByExtension("json")
        val highlighterFactory = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
        newEditor.highlighter = highlighterFactory.createEditorHighlighter(project, jsonFileType)

        return newEditor
    }

    /**
     * Get the current variables text.
     */
    fun getText(): String {
        return editor.document.text
    }

    /**
     * Set the variables text.
     */
    fun setText(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            // Normalize line separators to \n for IntelliJ Platform
            editor.document.setText(text.replace("\r\n", "\n"))
        }
    }

    /**
     * Clear the variables text.
     */
    fun clear() {
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("")
        }
    }

    /**
     * Dispose the editor when the panel is no longer needed.
     */
    fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
