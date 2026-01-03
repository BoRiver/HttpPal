package com.httppal.service.impl

import com.httppal.model.SchemaInfo
import com.httppal.service.SchemaAnalyzerService
import com.httppal.util.LoggingUtils
import com.httppal.util.PerformanceMonitor
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of SchemaAnalyzerService
 * 实现 OpenAPI schema 分析功能
 */
@Service(Service.Level.PROJECT)
class SchemaAnalyzerServiceImpl(private val project: Project) : SchemaAnalyzerService {
    
    // 引用缓存，避免重复解析相同的 $ref
    private val refCache = ConcurrentHashMap<String, Schema<*>>()
    
    // 最大递归深度，防止无限递归
    private val maxDepth = 10
    
    override fun analyzeParameterSchema(
        parameter: Parameter,
        components: Components?
    ): SchemaInfo {
        val schema = parameter.schema
        if (schema == null) {
            return SchemaInfo(
                type = "string",
                description = parameter.description,
                required = parameter.required ?: false
            )
        }
        
        val schemaInfo = analyzeSchema(schema, components, 0)
        
        // 合并参数级别的信息
        return schemaInfo.copy(
            description = parameter.description ?: schemaInfo.description,
            required = parameter.required ?: false,
            example = parameter.example ?: schemaInfo.example
        )
    }
    
    override fun analyzeRequestBodySchema(
        requestBody: RequestBody,
        components: Components?
    ): SchemaInfo {
        val content = requestBody.content
        if (content == null || content.isEmpty()) {
            return SchemaInfo(
                type = "object",
                description = requestBody.description,
                required = requestBody.required ?: false
            )
        }
        
        // 获取第一个 content type 的 schema
        val mediaType = content.values.firstOrNull()
        val schema = mediaType?.schema
        
        if (schema == null) {
            return SchemaInfo(
                type = "object",
                description = requestBody.description,
                required = requestBody.required ?: false
            )
        }
        
        val schemaInfo = analyzeSchema(schema, components, 0)
        
        return schemaInfo.copy(
            description = requestBody.description ?: schemaInfo.description,
            required = requestBody.required ?: false
        )
    }
    
    override fun analyzeResponseSchema(
        response: ApiResponse,
        components: Components?
    ): Map<String, SchemaInfo> {
        val result = mutableMapOf<String, SchemaInfo>()
        
        response.content?.forEach { (contentType, mediaType) ->
            val schema = mediaType.schema
            if (schema != null) {
                val schemaInfo = analyzeSchema(schema, components, 0)
                result[contentType] = schemaInfo.copy(
                    description = response.description ?: schemaInfo.description
                )
            }
        }
        
        return result
    }

    
    override fun resolveSchemaReference(
        ref: String,
        components: Components?
    ): Schema<*>? {
        // 检查缓存
        val cached = refCache[ref]
        if (cached != null) {
            return cached
        }
        
        if (components == null) {
            LoggingUtils.logWarning("Cannot resolve $ref without components: $ref")
            return null
        }
        
        // 解析引用路径（例如：#/components/schemas/User）
        val parts = ref.split("/")
        if (parts.size < 4 || parts[0] != "#" || parts[1] != "components" || parts[2] != "schemas") {
            LoggingUtils.logWarning("Invalid schema reference format: $ref")
            return null
        }
        
        val schemaName = parts[3]
        val schema = components.schemas?.get(schemaName)
        
        if (schema == null) {
            LoggingUtils.logWarning("Schema not found in components: $schemaName")
            return null
        }
        
        // 缓存结果
        refCache[ref] = schema
        
        return schema
    }
    
    override fun analyzeSchema(
        schema: Schema<*>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        // 检查递归深度
        if (depth > maxDepth) {
            LoggingUtils.logWarning("Maximum recursion depth reached while analyzing schema")
            return SchemaInfo(
                type = "object",
                description = "Maximum recursion depth reached"
            )
        }
        
        // 处理 $ref 引用
        if (schema.`$ref` != null) {
            val resolvedSchema = resolveSchemaReference(schema.`$ref`, components)
            if (resolvedSchema != null) {
                return analyzeSchema(resolvedSchema, components, depth + 1).copy(
                    ref = schema.`$ref`
                )
            } else {
                return SchemaInfo(
                    type = "object",
                    description = "Unresolved reference: ${schema.`$ref`}",
                    ref = schema.`$ref`
                )
            }
        }
        
        // 处理组合 schema
        if (schema.allOf != null && schema.allOf.isNotEmpty()) {
            return handleAllOf(schema.allOf, components, depth)
        }
        
        if (schema.oneOf != null && schema.oneOf.isNotEmpty()) {
            return handleOneOf(schema.oneOf, components, depth)
        }
        
        if (schema.anyOf != null && schema.anyOf.isNotEmpty()) {
            return handleAnyOf(schema.anyOf, components, depth)
        }
        
        // 基础类型分析
        val type = schema.type ?: "object"
        
        return when (type) {
            "object" -> analyzeObjectSchema(schema, components, depth)
            "array" -> analyzeArraySchema(schema, components, depth)
            else -> analyzePrimitiveSchema(schema)
        }
    }

    
    private fun analyzePrimitiveSchema(schema: Schema<*>): SchemaInfo {
        return SchemaInfo(
            type = schema.type ?: "string",
            format = schema.format,
            description = schema.description,
            example = schema.example,
            default = schema.default,
            enum = schema.enum?.toList(),
            minimum = schema.minimum,
            maximum = schema.maximum,
            exclusiveMinimum = schema.exclusiveMinimum ?: false,
            exclusiveMaximum = schema.exclusiveMaximum ?: false,
            multipleOf = schema.multipleOf,
            minLength = schema.minLength,
            maxLength = schema.maxLength,
            pattern = schema.pattern
        )
    }
    
    private fun analyzeObjectSchema(
        schema: Schema<*>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        val properties = mutableMapOf<String, SchemaInfo>()
        
        schema.properties?.forEach { (name, propSchema) ->
            properties[name] = analyzeSchema(propSchema, components, depth + 1)
        }
        
        return SchemaInfo(
            type = "object",
            format = schema.format,
            description = schema.description,
            example = schema.example,
            default = schema.default,
            properties = properties,
            requiredProperties = schema.required?.toList() ?: emptyList(),
            additionalProperties = schema.additionalProperties != false
        )
    }
    
    private fun analyzeArraySchema(
        schema: Schema<*>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        val itemsSchema = schema.items
        val items = if (itemsSchema != null) {
            analyzeSchema(itemsSchema, components, depth + 1)
        } else {
            SchemaInfo(type = "object")
        }
        
        return SchemaInfo(
            type = "array",
            format = schema.format,
            description = schema.description,
            example = schema.example,
            default = schema.default,
            items = items,
            minItems = schema.minItems,
            maxItems = schema.maxItems,
            uniqueItems = schema.uniqueItems ?: false
        )
    }
    
    private fun handleAllOf(
        allOfSchemas: List<Schema<*>>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        // 合并所有 schema 的属性
        val mergedProperties = mutableMapOf<String, SchemaInfo>()
        val mergedRequired = mutableListOf<String>()
        var mergedDescription: String? = null
        
        allOfSchemas.forEach { schema ->
            val analyzed = analyzeSchema(schema, components, depth + 1)
            
            analyzed.properties?.let { mergedProperties.putAll(it) }
            analyzed.requiredProperties?.let { mergedRequired.addAll(it) }
            if (mergedDescription == null) {
                mergedDescription = analyzed.description
            }
        }
        
        return SchemaInfo(
            type = "object",
            description = mergedDescription,
            properties = mergedProperties,
            requiredProperties = mergedRequired.distinct(),
            allOf = allOfSchemas.map { analyzeSchema(it, components, depth + 1) }
        )
    }
    
    private fun handleOneOf(
        oneOfSchemas: List<Schema<*>>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        // oneOf 表示只能匹配其中一个 schema
        // 我们返回第一个作为默认，并保留所有选项
        val analyzed = oneOfSchemas.map { analyzeSchema(it, components, depth + 1) }
        val first = analyzed.firstOrNull() ?: SchemaInfo(type = "object")
        
        return first.copy(
            oneOf = analyzed
        )
    }
    
    private fun handleAnyOf(
        anyOfSchemas: List<Schema<*>>,
        components: Components?,
        depth: Int
    ): SchemaInfo {
        // anyOf 表示可以匹配一个或多个 schema
        // 我们返回第一个作为默认，并保留所有选项
        val analyzed = anyOfSchemas.map { analyzeSchema(it, components, depth + 1) }
        val first = analyzed.firstOrNull() ?: SchemaInfo(type = "object")
        
        return first.copy(
            anyOf = analyzed
        )
    }
    
    fun clearCache() {
        refCache.clear()
    }
}
