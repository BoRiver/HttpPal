package com.httppal.graphql.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Editor component for GraphQL queries with syntax highlighting.
 * Attempts to use GraphQL plugin for syntax highlighting, falls back to JSON.
 */
class GraphQLQueryEditor(private val project: Project) : JPanel(BorderLayout()) {

    private val editor: EditorEx
    private var completionCallback: ((String, Int) -> Unit)? = null
    private val documentListeners = mutableListOf<(String) -> Unit>()
    private var isSilentUpdate = false

    init {
        editor = createEditor()
        add(editor.component, BorderLayout.CENTER)

        // Add keyboard listener for auto-completion
        setupCompletionTrigger()

        // Add document listener for text changes
        setupDocumentListener()
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

        // Try to use GraphQL syntax highlighting
        applySyntaxHighlighting(newEditor)

        return newEditor
    }

    private fun applySyntaxHighlighting(editor: EditorEx) {
        // First, try to detect GraphQL plugin
        val graphqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("graphql")

        val fileTypeToUse = if (graphqlFileType != FileTypes.UNKNOWN && graphqlFileType != PlainTextFileType.INSTANCE) {
            // GraphQL plugin is installed, use it
            graphqlFileType
        } else {
            // Fall back to JSON syntax highlighting
            FileTypeManager.getInstance().getFileTypeByExtension("json")
        }

        // Apply syntax highlighting
        val highlighterFactory = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance()
        editor.highlighter = highlighterFactory.createEditorHighlighter(project, fileTypeToUse)
    }

    /**
     * 设置自动补全触发器
     */
    private fun setupCompletionTrigger() {
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Ctrl+Space 触发自动补全
                if (e.keyCode == KeyEvent.VK_SPACE && e.isControlDown) {
                    val text = editor.document.text
                    val offset = editor.caretModel.offset
                    completionCallback?.invoke(text, offset)
                    e.consume()
                }
            }
        })
    }

    /**
     * Setup document listener to notify when text changes.
     */
    private fun setupDocumentListener() {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!isSilentUpdate) {
                    val text = editor.document.text
                    documentListeners.forEach { it(text) }
                }
            }
        })
    }

    /**
     * Add a document change listener.
     */
    fun addDocumentListener(listener: (String) -> Unit) {
        documentListeners.add(listener)
    }

    /**
     * Remove a document change listener.
     */
    fun removeDocumentListener(listener: (String) -> Unit) {
        documentListeners.remove(listener)
    }

    /**
     * 设置自动补全回调
     */
    fun setCompletionCallback(callback: (String, Int) -> Unit) {
        this.completionCallback = callback
    }

    /**
     * Get the current query text.
     */
    fun getText(): String {
        return editor.document.text
    }

    /**
     * Set the query text.
     */
    fun setText(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            // Normalize line separators to \n for IntelliJ Platform
            editor.document.setText(text.replace("\r\n", "\n"))
        }
    }

    /**
     * Set the query text silently without triggering document listeners.
     * Used for syncing from schema explorer to avoid loops.
     */
    fun setTextSilently(text: String) {
        isSilentUpdate = true
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                // Normalize line separators to \n for IntelliJ Platform
                editor.document.setText(text.replace("\r\n", "\n"))
            }
        } finally {
            isSilentUpdate = false
        }
    }

    /**
     * Clear the query text.
     */
    fun clear() {
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.setText("")
        }
    }

    /**
     * Insert text at the current cursor position.
     */
    fun insertTextAtCursor(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val caretModel = editor.caretModel
            val offset = caretModel.offset
            // Normalize line separators to \n for IntelliJ Platform
            editor.document.insertString(offset, text.replace("\r\n", "\n"))
            caretModel.moveToOffset(offset + text.replace("\r\n", "\n").length)
        }
    }

    /**
     * Dispose the editor when the panel is no longer needed.
     */
    fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
