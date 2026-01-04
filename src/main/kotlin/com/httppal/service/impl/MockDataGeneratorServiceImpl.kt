package com.httppal.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.httppal.model.*
import com.httppal.service.MockDataGeneratorService
import com.httppal.util.LoggingUtils
import com.httppal.util.PerformanceMonitor
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import net.datafaker.Faker
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.random.Random

/**
 * Implementation of MockDataGeneratorService using Datafaker
 * 使用 Datafaker 实现 mock 数据生成
 */
@Service(Service.Level.PROJECT)
class MockDataGeneratorServiceImpl(private val project: Project) : MockDataGeneratorService {
    
    private val faker = Faker()
    private val objectMapper = ObjectMapper()
    private val random = Random.Default
    
    override fun generateMockRequest(
        endpoint: DiscoveredEndpoint,
        schemaInfo: SchemaInfo?
    ): MockDataConfig {
        return PerformanceMonitor.measure(
            operation = "Mock Data Generation",
            threshold = 500L, // 500ms threshold for mock generation
            details = mapOf(
                "endpoint" to endpoint.path,
                "method" to endpoint.method.name,
                "hasSchema" to (schemaInfo != null),
                "paramCount" to endpoint.parameters.size
            )
        ) {
            val pathParams = mutableMapOf<String, String>()
            val queryParams = mutableMapOf<String, String>()
            val headers = mutableMapOf<String, String>()
            var body: String? = null
            
            // 生成参数数据
            endpoint.parameters.forEach { param ->
                val value = generateValueForParameter(param)
                
                when (param.type) {
                    ParameterType.PATH -> pathParams[param.name] = value
                    ParameterType.QUERY -> queryParams[param.name] = value
                    ParameterType.HEADER -> headers[param.name] = value
                    ParameterType.BODY -> {
                        // 如果有 schemaInfo，使用它生成 body
                        body = if (schemaInfo != null) {
                            val bodyData = generateValueForSchema(schemaInfo)
                            objectMapper.writeValueAsString(bodyData)
                        } else {
                            generateDefaultBody(param)
                        }
                    }
                }
            }
            
            MockDataConfig(
                pathParameters = pathParams,
                queryParameters = queryParams,
                headers = headers,
                body = body
            )
        }
    }
    
    private fun generateValueForParameter(param: EndpointParameter): String {
        // 优先使用示例值
        if (param.example != null) {
            return param.example
        }
        
        // 使用默认值
        if (param.defaultValue != null) {
            return param.defaultValue
        }
        
        // 根据数据类型生成
        return when (param.dataType?.lowercase()) {
            "integer", "int", "long" -> random.nextInt(1, 100).toString()
            "number", "float", "double" -> random.nextDouble(1.0, 100.0).toString()
            "boolean" -> random.nextBoolean().toString()
            else -> faker.lorem().word()
        }
    }
    
    private fun generateDefaultBody(param: EndpointParameter): String {
        // If we have a fully qualified type, try to resolve it and generate deep mock data
        if (!param.qualifiedType.isNullOrBlank() && param.qualifiedType != "java.lang.Object") {
             val psiClass = JavaPsiFacade.getInstance(project).findClass(
                 param.qualifiedType,
                 GlobalSearchScope.allScope(project)
             )
             
             if (psiClass != null) {
                 val mockData = generateFromPsiClass(psiClass, 0)
                 if (mockData != null) {
                     return try {
                         objectMapper.writeValueAsString(mockData) ?: "{}"
                     } catch (e: Exception) {
                         "{}"
                     }
                 }
             }
        }
        
        return when (param.dataType?.lowercase()) {
            "object" -> "{}"
            "array" -> "[]"
            else -> "\"\""
        }
    }
    
    private fun generateFromPsiClass(psiClass: PsiClass, depth: Int): Any? {
        if (depth > 5) return null // Prevent infinite recursion
        
        val result = mutableMapOf<String, Any?>()
        
        // Iterate over fields including inherited ones (if we want robust support, allFields is good)
        val fields = psiClass.allFields
        
        for (field in fields) {
            if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) {
                continue
            }
            
            val fieldName = field.name
            val type = field.type
            
            result[fieldName] = generateValueForType(type, depth)
        }
        
        return result
    }

    private fun generateValueForType(type: PsiType, depth: Int): Any? {
         val canonicalText = type.canonicalText
         
         // Handle basic types
         when (canonicalText) {
             "java.lang.String", "String" -> return faker.lorem().word()
             "java.lang.Integer", "int", "Integer" -> return random.nextInt(1, 100)
             "java.lang.Long", "long", "Long" -> return random.nextLong(1, 1000)
             "java.lang.Boolean", "boolean", "Boolean" -> return random.nextBoolean()
             "java.lang.Double", "double", "Double" -> return random.nextDouble(1.0, 100.0)
             "java.util.Date", "java.sql.Date", "Date" -> return LocalDate.now().toString()
             "java.math.BigDecimal" -> return random.nextDouble(1.0, 1000.0)
         }
         
         // Handle Lists/Collections
         if (type is PsiClassType) {
             val resolveResult = type.resolve()
             if (resolveResult != null) {
                 val qName = resolveResult.qualifiedName
                 if (qName != null) {
                      if (qName.startsWith("java.util.List") || qName.startsWith("java.util.Set") || qName.startsWith("java.util.Collection")) {
                          // Try to get generic type
                          val parameters = type.parameters
                          if (parameters.isNotEmpty()) {
                              val genericType = parameters[0]
                              val item = generateValueForType(genericType, depth + 1)
                              return if (item != null) listOf(item) else emptyList<Any>()
                          }
                          return emptyList<Any>()
                      }
                      if (qName.startsWith("java.util.Map")) {
                          return mapOf("key" to "value")
                      }
                     // Recursive POJO
                     if (!qName.startsWith("java.")) {
                         return generateFromPsiClass(resolveResult, depth + 1)
                     }
                 }
                 

             }
         }
         
         // Handle Arrays
         if (type is PsiArrayType) {
             val item = generateValueForType(type.componentType, depth + 1)
             return if (item != null) listOf(item) else emptyList<Any>()
         }
         
         return null
    }

    
    override fun generateValueForSchema(schema: SchemaInfo): Any? {
        // 优先使用示例值
        if (schema.example != null) {
            return schema.example
        }
        
        // 处理枚举
        if (schema.enum != null && schema.enum.isNotEmpty()) {
            return schema.enum.random()
        }
        
        // 根据类型生成
        return when (schema.type.lowercase()) {
            "string" -> generateStringValue(schema)
            "integer", "int" -> generateIntegerValue(schema)
            "number" -> generateNumberValue(schema)
            "boolean" -> random.nextBoolean()
            "object" -> generateObjectValue(schema)
            "array" -> generateArrayValue(schema)
            else -> null
        }
    }
    
    private fun generateStringValue(schema: SchemaInfo): String {
        val format = schema.format
        
        // 根据格式生成
        val value = when (format?.lowercase()) {
            "email" -> faker.internet().emailAddress()
            "uuid" -> UUID.randomUUID().toString()
            "uri", "url" -> faker.internet().url()
            "date" -> LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            "date-time" -> LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)
            "ipv4" -> faker.internet().ipV4Address()
            "ipv6" -> faker.internet().ipV6Address()
            "hostname" -> faker.internet().domainName()
            "password" -> faker.internet().password(8, 16)
            else -> {
                // 根据约束生成
                val minLength = schema.minLength ?: 5
                val maxLength = schema.maxLength ?: 20
                val length = random.nextInt(minLength, maxLength.coerceAtLeast(minLength + 1))
                faker.lorem().characters(length)
            }
        }
        
        // 检查 pattern 约束（简单验证）
        if (schema.pattern != null) {
            try {
                val regex = Regex(schema.pattern)
                if (!regex.matches(value)) {
                    LoggingUtils.logWarning("Generated value does not match pattern: ${schema.pattern}")
                }
            } catch (e: Exception) {
                LoggingUtils.logWarning("Invalid regex pattern: ${schema.pattern}")
            }
        }
        
        return value
    }
    
    private fun generateIntegerValue(schema: SchemaInfo): Int {
        val min = schema.minimum?.toInt() ?: 1
        val max = schema.maximum?.toInt() ?: 100
        
        // 处理 exclusive
        val actualMin = if (schema.exclusiveMinimum) min + 1 else min
        val actualMax = if (schema.exclusiveMaximum) max - 1 else max
        
        var value = random.nextInt(actualMin, actualMax + 1)
        
        // 处理 multipleOf
        if (schema.multipleOf != null) {
            val multiple = schema.multipleOf.toInt()
            value = (value / multiple) * multiple
        }
        
        return value
    }
    
    private fun generateNumberValue(schema: SchemaInfo): Double {
        val min = schema.minimum?.toDouble() ?: 1.0
        val max = schema.maximum?.toDouble() ?: 100.0
        
        // 处理 exclusive
        val actualMin = if (schema.exclusiveMinimum) min + 0.01 else min
        val actualMax = if (schema.exclusiveMaximum) max - 0.01 else max
        
        var value = random.nextDouble(actualMin, actualMax)
        
        // 处理 multipleOf
        if (schema.multipleOf != null) {
            val multiple = schema.multipleOf.toDouble()
            value = (value / multiple).toInt() * multiple
        }
        
        return value
    }
    
    private fun generateObjectValue(schema: SchemaInfo): Map<String, Any?> {
        val properties = schema.properties ?: return emptyMap()
        val required = schema.requiredProperties ?: emptyList()
        
        return generateObjectData(properties, required)
    }
    
    private fun generateArrayValue(schema: SchemaInfo): List<Any?> {
        val itemSchema = schema.items ?: SchemaInfo(type = "string")
        val minItems = schema.minItems
        val maxItems = schema.maxItems
        
        return generateArrayData(itemSchema, minItems, maxItems)
    }

    
    override fun generateFormattedValue(
        type: String,
        format: String?,
        constraints: Map<String, Any>
    ): Any? {
        val schema = SchemaInfo(
            type = type,
            format = format,
            minimum = constraints["minimum"] as? Number,
            maximum = constraints["maximum"] as? Number,
            minLength = constraints["minLength"] as? Int,
            maxLength = constraints["maxLength"] as? Int,
            pattern = constraints["pattern"] as? String
        )
        
        return generateValueForSchema(schema)
    }
    
    override fun generateObjectData(
        properties: Map<String, SchemaInfo>,
        required: List<String>
    ): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        // 只生成必需属性
        properties.forEach { (name, propSchema) ->
            if (required.contains(name)) {
                result[name] = generateValueForSchema(propSchema)
            }
        }
        
        return result
    }
    
    override fun generateArrayData(
        itemSchema: SchemaInfo,
        minItems: Int?,
        maxItems: Int?
    ): List<Any?> {
        // 默认生成 1-3 个元素
        val min = minItems ?: 1
        val max = maxItems ?: 3
        
        val count = random.nextInt(min, max.coerceAtLeast(min) + 1)
        
        return (0 until count).map {
            generateValueForSchema(itemSchema)
        }
    }
}
