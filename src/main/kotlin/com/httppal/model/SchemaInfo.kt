package com.httppal.model

/**
 * 扩展的 schema 信息模型，包含所有必要的元数据
 * 用于表示 OpenAPI schema 定义的详细信息
 */
data class SchemaInfo(
    val type: String,                          // 数据类型：string、number、integer、boolean、object、array
    val format: String? = null,                // 格式约束：email、uuid、date-time 等
    val description: String? = null,           // 描述信息
    val example: Any? = null,                  // 示例值
    val default: Any? = null,                  // 默认值
    val enum: List<Any>? = null,               // 枚举值
    val required: Boolean = false,             // 是否必需
    
    // 数值约束
    val minimum: Number? = null,
    val maximum: Number? = null,
    val exclusiveMinimum: Boolean = false,
    val exclusiveMaximum: Boolean = false,
    val multipleOf: Number? = null,
    
    // 字符串约束
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    
    // 数组约束
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val uniqueItems: Boolean = false,
    val items: SchemaInfo? = null,             // 数组元素 schema
    
    // 对象约束
    val properties: Map<String, SchemaInfo>? = null,  // 对象属性
    val requiredProperties: List<String>? = null,     // 必需属性列表
    val additionalProperties: Boolean = true,
    
    // 组合 schema
    val allOf: List<SchemaInfo>? = null,
    val oneOf: List<SchemaInfo>? = null,
    val anyOf: List<SchemaInfo>? = null,
    
    // 引用信息
    val ref: String? = null                    // $ref 引用路径
)
