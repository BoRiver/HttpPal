package com.httppal.graphql.ui

import com.httppal.graphql.model.*
import com.httppal.graphql.service.GraphQLSchemaService
import com.httppal.util.HttpPalBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * GraphQL Schema 浏览器 - 树形视图显示 schema 中的类型和字段
 */
class GraphQLSchemaExplorer(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(GraphQLSchemaExplorer::class.java)
    private val tree: Tree
    private val rootNode = DefaultMutableTreeNode("Schema")
    private val treeModel = DefaultTreeModel(rootNode)

    private var currentEndpoint: String? = null
    private var onFieldSelectedCallback: ((String) -> Unit)? = null

    init {
        // 创建树
        tree = Tree(treeModel)
        tree.isRootVisible = true
        tree.showsRootHandles = true

        // 添加双击监听器 - 双击字段时插入到查询编辑器
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    handleDoubleClick()
                }
            }
        })

        // 布局
        border = JBUI.Borders.empty(5)

        val scrollPane = JBScrollPane(tree)
        add(scrollPane, BorderLayout.CENTER)

        // 顶部标签
        val titleLabel = JBLabel(HttpPalBundle.message("graphql.schema.title"))
        titleLabel.border = JBUI.Borders.empty(0, 0, 5, 0)
        add(titleLabel, BorderLayout.NORTH)

        // 显示初始状态
        showEmptyState()
    }

    /**
     * 为指定端点加载 schema
     */
    fun loadSchema(endpoint: String) {
        this.currentEndpoint = endpoint

        val schemaService = service<GraphQLSchemaService>()

        // 先尝试从缓存获取
        val cachedSchema = schemaService.getCachedSchema(endpoint)
        if (cachedSchema != null) {
            displaySchema(cachedSchema)
            return
        }

        // 如果没有缓存，显示提示信息
        showLoadingState()

        // 在后台线程获取 schema
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val schema = schemaService.introspectSchema(endpoint)

                SwingUtilities.invokeLater {
                    if (schema != null) {
                        displaySchema(schema)
                    } else {
                        showErrorState()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to load schema", e)
                SwingUtilities.invokeLater {
                    showErrorState()
                }
            }
        }
    }

    /**
     * 显示 schema 内容
     */
    private fun displaySchema(schema: GraphQLSchema) {
        rootNode.removeAllChildren()
        rootNode.userObject = "Schema (${schema.types.size} types)"

        // 添加 Query 类型
        val queryType = schema.types.find { it.name == schema.queryType }
        if (queryType != null) {
            val queryNode = DefaultMutableTreeNode("Query")
            queryType.fields?.forEach { field ->
                val fieldNode = createFieldNode(field)
                queryNode.add(fieldNode)
            }
            rootNode.add(queryNode)
        }

        // 添加 Mutation 类型
        if (schema.mutationType != null) {
            val mutationType = schema.types.find { it.name == schema.mutationType }
            if (mutationType != null) {
                val mutationNode = DefaultMutableTreeNode("Mutation")
                mutationType.fields?.forEach { field ->
                    val fieldNode = createFieldNode(field)
                    mutationNode.add(fieldNode)
                }
                rootNode.add(mutationNode)
            }
        }

        // 添加 Subscription 类型
        if (schema.subscriptionType != null) {
            val subscriptionType = schema.types.find { it.name == schema.subscriptionType }
            if (subscriptionType != null) {
                val subscriptionNode = DefaultMutableTreeNode("Subscription")
                subscriptionType.fields?.forEach { field ->
                    val fieldNode = createFieldNode(field)
                    subscriptionNode.add(fieldNode)
                }
                rootNode.add(subscriptionNode)
            }
        }

        // 添加其他自定义类型（可选，用于浏览完整 schema）
        val customTypesNode = DefaultMutableTreeNode("Custom Types")
        schema.types
            .filter { it.kind == TypeKind.OBJECT &&
                     it.name != schema.queryType &&
                     it.name != schema.mutationType &&
                     it.name != schema.subscriptionType &&
                     !it.name.startsWith("__") // 跳过内部类型
            }
            .sortedBy { it.name }
            .forEach { type ->
                val typeNode = DefaultMutableTreeNode(type.name)
                type.fields?.forEach { field ->
                    typeNode.add(createFieldNode(field))
                }
                customTypesNode.add(typeNode)
            }

        if (customTypesNode.childCount > 0) {
            rootNode.add(customTypesNode)
        }

        // 刷新树
        treeModel.reload()

        // 默认展开 Query 节点
        if (rootNode.childCount > 0) {
            tree.expandRow(0) // 展开根节点
            tree.expandRow(1) // 展开 Query 节点
        }
    }

    /**
     * 创建字段节点
     */
    private fun createFieldNode(field: GraphQLField): DefaultMutableTreeNode {
        val fieldInfo = buildString {
            append(field.name)

            // 添加参数
            if (field.args.isNotEmpty()) {
                append("(")
                append(field.args.joinToString(", ") { arg ->
                    "${arg.name}: ${getTypeName(arg.type)}"
                })
                append(")")
            }

            // 添加返回类型
            append(": ${getTypeName(field.type)}")

            // 标记弃用
            if (field.isDeprecated) {
                append(" [已弃用]")
            }
        }

        val node = DefaultMutableTreeNode(FieldNodeData(field, fieldInfo))

        // 添加描述作为子节点（如果有）
        if (!field.description.isNullOrBlank()) {
            node.add(DefaultMutableTreeNode("📝 ${field.description}"))
        }

        return node
    }

    /**
     * 获取类型名称的简化表示
     */
    private fun getTypeName(type: GraphQLType): String {
        return when (type.kind) {
            TypeKind.NON_NULL -> "${getTypeName(type.ofType!!)}!"
            TypeKind.LIST -> "[${getTypeName(type.ofType!!)}]"
            else -> type.name
        }
    }

    /**
     * 处理双击事件
     */
    private fun handleDoubleClick() {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val userObject = selectedNode.userObject

        if (userObject is FieldNodeData) {
            val field = userObject.field
            val fieldText = buildFieldText(field)
            onFieldSelectedCallback?.invoke(fieldText)
        }
    }

    /**
     * 构建要插入的字段文本
     */
    private fun buildFieldText(field: GraphQLField): String {
        return buildString {
            append(field.name)

            // 如果有参数，添加参数占位符
            if (field.args.isNotEmpty()) {
                append("(")
                append(field.args.joinToString(", ") { arg ->
                    "${arg.name}: ${getDefaultValuePlaceholder(arg.type)}"
                })
                append(")")
            }

            // 如果返回对象类型，添加字段选择占位符
            if (field.type.kind == TypeKind.OBJECT ||
                (field.type.kind == TypeKind.NON_NULL && field.type.ofType?.kind == TypeKind.OBJECT) ||
                (field.type.kind == TypeKind.LIST && field.type.ofType?.kind == TypeKind.OBJECT)) {
                append(" {\n  # 在此添加字段\n}")
            }
        }
    }

    /**
     * 获取参数的默认值占位符
     */
    private fun getDefaultValuePlaceholder(type: GraphQLType): String {
        return when (type.kind) {
            TypeKind.SCALAR -> when (type.name) {
                "Int" -> "0"
                "Float" -> "0.0"
                "String" -> "\"\""
                "Boolean" -> "false"
                "ID" -> "\"id\""
                else -> "null"
            }
            TypeKind.NON_NULL -> getDefaultValuePlaceholder(type.ofType!!)
            TypeKind.LIST -> "[]"
            TypeKind.ENUM -> "ENUM_VALUE"
            else -> "null"
        }
    }

    /**
     * 显示空状态
     */
    private fun showEmptyState() {
        rootNode.removeAllChildren()
        rootNode.userObject = HttpPalBundle.message("graphql.schema.no.schema")
        treeModel.reload()
    }

    /**
     * 显示加载状态
     */
    private fun showLoadingState() {
        rootNode.removeAllChildren()
        rootNode.userObject = HttpPalBundle.message("graphql.schema.loading")
        treeModel.reload()
    }

    /**
     * 显示错误状态
     */
    private fun showErrorState() {
        rootNode.removeAllChildren()
        rootNode.userObject = HttpPalBundle.message("graphql.introspect.failed")
        treeModel.reload()
    }

    /**
     * 清空 schema
     */
    fun clear() {
        currentEndpoint = null
        showEmptyState()
    }

    /**
     * 设置字段选择回调
     */
    fun setOnFieldSelectedCallback(callback: (String) -> Unit) {
        this.onFieldSelectedCallback = callback
    }

    /**
     * 字段节点数据
     */
    private data class FieldNodeData(
        val field: GraphQLField,
        val displayText: String
    ) {
        override fun toString(): String = displayText
    }
}
