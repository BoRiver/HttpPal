package com.httppal.graphql.ui

import com.httppal.graphql.model.GraphQLField
import com.httppal.graphql.model.GraphQLSchema
import com.httppal.graphql.model.TypeKind
import com.httppal.graphql.service.GraphQLSchemaService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import javax.swing.DefaultListModel
import javax.swing.JPopupMenu
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * GraphQL 自动补全提供器
 * 基于 schema 提供字段补全
 */
class GraphQLCompletionProvider(
    private val project: Project,
    private val endpoint: String
) {

    private val logger = Logger.getInstance(GraphQLCompletionProvider::class.java)
    private var schema: GraphQLSchema? = null

    init {
        loadSchema()
    }

    /**
     * 加载 schema
     */
    private fun loadSchema() {
        val schemaService = service<GraphQLSchemaService>()
        schema = schemaService.getCachedSchema(endpoint)
    }

    /**
     * 获取当前上下文的补全建议
     * 这是一个简化实现，只支持顶层字段补全
     */
    fun getCompletions(text: String, cursorPosition: Int): List<CompletionItem> {
        val currentSchema = schema ?: return emptyList()

        val completions = mutableListOf<CompletionItem>()

        try {
            // 分析查询类型（query、mutation、subscription）
            val queryType = detectQueryType(text)

            // 获取相应的类型
            val typeName = when (queryType) {
                "query" -> currentSchema.queryType
                "mutation" -> currentSchema.mutationType
                "subscription" -> currentSchema.subscriptionType
                else -> currentSchema.queryType
            }

            val type = currentSchema.types.find { it.name == typeName }
            if (type != null && type.fields != null) {
                // 添加所有字段作为补全项
                type.fields.forEach { field ->
                    completions.add(
                        CompletionItem(
                            name = field.name,
                            description = field.description ?: "",
                            insertText = buildInsertText(field),
                            detail = "${field.name}: ${getTypeName(field.type)}"
                        )
                    )
                }
            }

        } catch (e: Exception) {
            logger.warn("Failed to get completions", e)
        }

        return completions
    }

    /**
     * 检测查询类型
     */
    private fun detectQueryType(text: String): String {
        return when {
            text.contains("mutation", ignoreCase = true) -> "mutation"
            text.contains("subscription", ignoreCase = true) -> "subscription"
            else -> "query"
        }
    }

    /**
     * 构建插入文本
     */
    private fun buildInsertText(field: GraphQLField): String {
        return buildString {
            append(field.name)

            // 添加参数
            if (field.args.isNotEmpty()) {
                append("(")
                append(field.args.joinToString(", ") { arg ->
                    "${arg.name}: "
                })
                append(")")
            }

            // 如果返回对象，添加字段选择
            val returnType = unwrapType(field.type)
            if (returnType.kind == TypeKind.OBJECT) {
                append(" {\n  \n}")
            }
        }
    }

    /**
     * 解包类型
     */
    private fun unwrapType(type: com.httppal.graphql.model.GraphQLType): com.httppal.graphql.model.GraphQLType {
        return when (type.kind) {
            TypeKind.NON_NULL, TypeKind.LIST -> type.ofType?.let { unwrapType(it) } ?: type
            else -> type
        }
    }

    /**
     * 获取类型名称
     */
    private fun getTypeName(type: com.httppal.graphql.model.GraphQLType): String {
        return when (type.kind) {
            TypeKind.NON_NULL -> "${getTypeName(type.ofType!!)}!"
            TypeKind.LIST -> "[${getTypeName(type.ofType!!)}]"
            else -> type.name
        }
    }

    /**
     * 补全项
     */
    data class CompletionItem(
        val name: String,
        val description: String,
        val insertText: String,
        val detail: String
    ) {
        override fun toString(): String = "$name - $detail"
    }
}
