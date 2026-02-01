package com.httppal.graphql.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.httppal.graphql.model.GraphQLField
import com.httppal.graphql.model.GraphQLSchema
import com.httppal.graphql.model.GraphQLType
import com.httppal.graphql.model.TypeKind
import net.datafaker.Faker

/**
 * GraphQL Mock 数据生成器
 * 基于 schema 生成示例查询和变量
 */
class GraphQLMockGenerator(private val schema: GraphQLSchema) {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val faker = Faker()

    /**
     * 为指定类型生成示例查询
     */
    fun generateSampleQuery(typeName: String = "Query", maxDepth: Int = 2): String {
        val type = schema.types.find { it.name == typeName } ?: return ""

        if (type.fields.isNullOrEmpty()) {
            return "# 类型 $typeName 没有字段"
        }

        return buildString {
            val operationKeyword = when (typeName) {
                schema.queryType -> "query"
                schema.mutationType -> "mutation"
                schema.subscriptionType -> "subscription"
                else -> "query"
            }

            appendLine("$operationKeyword {")

            // 选择前 3 个字段生成示例
            type.fields.take(3).forEach { field ->
                append(generateFieldSelection(field, depth = 1, maxDepth = maxDepth))
            }

            append("}")
        }
    }

    /**
     * 为字段生成选择集
     */
    private fun generateFieldSelection(field: GraphQLField, depth: Int, maxDepth: Int): String {
        val indent = "  ".repeat(depth)

        return buildString {
            append(indent)
            append(field.name)

            // 添加参数（只在第一层添加）
            if (field.args.isNotEmpty() && depth == 1) {
                val argStrings = field.args.take(2).map { arg ->
                    "${arg.name}: ${generateArgumentPlaceholder(arg.type)}"
                }
                append("(${argStrings.joinToString(", ")})")
            }

            // 递归添加子字段
            val returnType = unwrapType(field.type)
            if (depth < maxDepth && shouldExpandType(returnType)) {
                val typeObj = schema.types.find { it.name == returnType.name }
                if (typeObj != null && !typeObj.fields.isNullOrEmpty()) {
                    appendLine(" {")
                    typeObj.fields.take(3).forEach { subField ->
                        append(generateFieldSelection(subField, depth + 1, maxDepth))
                    }
                    append(indent)
                    appendLine("}")
                } else {
                    // 标量字段，直接换行
                    appendLine()
                }
            } else {
                // 标量字段或达到最大深度，直接换行
                appendLine()
            }
        }
    }

    /**
     * 解包类型（去除 NON_NULL 和 LIST 包装）
     */
    private fun unwrapType(type: GraphQLType): GraphQLType {
        return when (type.kind) {
            TypeKind.NON_NULL, TypeKind.LIST -> type.ofType?.let { unwrapType(it) } ?: type
            else -> type
        }
    }

    /**
     * 判断是否应该展开类型（对象类型需要展开）
     */
    private fun shouldExpandType(type: GraphQLType): Boolean {
        return type.kind == TypeKind.OBJECT && !type.name.startsWith("__")
    }

    /**
     * 生成参数占位符
     */
    private fun generateArgumentPlaceholder(type: GraphQLType): String {
        return when (type.kind) {
            TypeKind.NON_NULL -> generateArgumentPlaceholder(type.ofType!!)
            TypeKind.LIST -> "[]"
            TypeKind.SCALAR -> when (type.name) {
                "Int" -> "1"
                "Float" -> "1.0"
                "String" -> "\"示例文本\""
                "Boolean" -> "true"
                "ID" -> "\"1\""
                else -> "null"
            }
            TypeKind.ENUM -> {
                val enumType = schema.types.find { it.name == type.name }
                val firstValue = enumType?.enumValues?.firstOrNull()?.name
                firstValue ?: "ENUM_VALUE"
            }
            else -> "null"
        }
    }

    /**
     * 为输入类型生成示例变量
     */
    fun generateSampleVariables(inputTypeName: String): Map<String, Any> {
        val inputType = schema.types.find { it.name == inputTypeName }
            ?: return emptyMap()

        if (inputType.inputFields.isNullOrEmpty()) {
            return emptyMap()
        }

        val variables = mutableMapOf<String, Any>()

        inputType.inputFields.forEach { inputField ->
            val value = generateMockValue(inputField.type, inputField.name)
            if (value != null) {
                variables[inputField.name] = value
            }
        }

        return variables
    }

    /**
     * 生成 mock 值
     */
    private fun generateMockValue(type: GraphQLType, fieldName: String): Any? {
        return when (type.kind) {
            TypeKind.NON_NULL -> generateMockValue(type.ofType!!, fieldName)
            TypeKind.LIST -> listOf(generateMockValue(type.ofType!!, fieldName))
            TypeKind.SCALAR -> generateScalarMockValue(type.name, fieldName)
            TypeKind.ENUM -> {
                val enumType = schema.types.find { it.name == type.name }
                enumType?.enumValues?.firstOrNull()?.name ?: "UNKNOWN"
            }
            TypeKind.INPUT_OBJECT -> {
                val inputType = schema.types.find { it.name == type.name }
                if (inputType != null && !inputType.inputFields.isNullOrEmpty()) {
                    val nested = mutableMapOf<String, Any?>()
                    inputType.inputFields.forEach { field ->
                        nested[field.name] = generateMockValue(field.type, field.name)
                    }
                    nested
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * 根据字段名生成合适的标量 mock 值
     */
    private fun generateScalarMockValue(typeName: String, fieldName: String): Any {
        val lowerFieldName = fieldName.lowercase()

        return when (typeName) {
            "Int" -> when {
                lowerFieldName.contains("id") -> faker.number().numberBetween(1, 1000)
                lowerFieldName.contains("age") -> faker.number().numberBetween(18, 80)
                lowerFieldName.contains("count") -> faker.number().numberBetween(1, 100)
                lowerFieldName.contains("year") -> faker.number().numberBetween(2000, 2024)
                else -> faker.number().numberBetween(1, 100)
            }

            "Float" -> when {
                lowerFieldName.contains("price") || lowerFieldName.contains("cost") ->
                    faker.number().randomDouble(2, 10, 1000)
                lowerFieldName.contains("rate") || lowerFieldName.contains("percent") ->
                    faker.number().randomDouble(2, 0, 100)
                else -> faker.number().randomDouble(2, 0, 100)
            }

            "String" -> when {
                lowerFieldName.contains("email") -> faker.internet().emailAddress()
                lowerFieldName.contains("name") -> faker.name().fullName()
                lowerFieldName.contains("firstname") || lowerFieldName == "first" -> faker.name().firstName()
                lowerFieldName.contains("lastname") || lowerFieldName == "last" -> faker.name().lastName()
                lowerFieldName.contains("username") || lowerFieldName.contains("login") -> faker.name().username()
                lowerFieldName.contains("phone") || lowerFieldName.contains("mobile") -> faker.phoneNumber().phoneNumber()
                lowerFieldName.contains("address") -> faker.address().fullAddress()
                lowerFieldName.contains("city") -> faker.address().city()
                lowerFieldName.contains("country") -> faker.address().country()
                lowerFieldName.contains("url") || lowerFieldName.contains("website") -> faker.internet().url()
                lowerFieldName.contains("title") -> faker.book().title()
                lowerFieldName.contains("description") || lowerFieldName.contains("content") ->
                    faker.lorem().sentence(10)
                lowerFieldName.contains("code") -> faker.code().asin()
                else -> faker.lorem().word()
            }

            "Boolean" -> faker.bool().bool()

            "ID" -> faker.idNumber().valid()

            else -> "mock_value"
        }
    }

    /**
     * 生成变量的 JSON 字符串
     */
    fun generateSampleVariablesJson(inputTypeName: String): String {
        val variables = generateSampleVariables(inputTypeName)
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(variables)
    }
}
