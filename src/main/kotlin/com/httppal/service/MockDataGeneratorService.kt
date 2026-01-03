package com.httppal.service

import com.httppal.model.DiscoveredEndpoint
import com.httppal.model.MockDataConfig
import com.httppal.model.SchemaInfo

/**
 * Service for generating mock data based on OpenAPI schemas
 * 负责基于 OpenAPI schema 生成符合约束的 mock 数据
 */
interface MockDataGeneratorService {
    
    /**
     * 为端点生成完整的 mock 请求数据
     * 
     * @param endpoint 端点信息
     * @param schemaInfo Schema 信息（可选，如果提供则用于生成请求体）
     * @return Mock 数据配置
     */
    fun generateMockRequest(
        endpoint: DiscoveredEndpoint,
        schemaInfo: SchemaInfo? = null
    ): MockDataConfig
    
    /**
     * 基于 schema 生成单个值
     * 
     * @param schema Schema 信息
     * @return 生成的值（可能是基础类型、Map 或 List）
     */
    fun generateValueForSchema(schema: SchemaInfo): Any?
    
    /**
     * 生成符合格式约束的值
     * 
     * @param type 数据类型（string、number、integer、boolean）
     * @param format 格式约束（email、uuid、date-time 等）
     * @param constraints 其他约束（min、max、pattern 等）
     * @return 生成的值
     */
    fun generateFormattedValue(
        type: String,
        format: String?,
        constraints: Map<String, Any>
    ): Any?
    
    /**
     * 生成对象类型的 mock 数据
     * 
     * @param properties 属性定义
     * @param required 必需属性列表
     * @return 生成的对象（Map）
     */
    fun generateObjectData(
        properties: Map<String, SchemaInfo>,
        required: List<String>
    ): Map<String, Any?>
    
    /**
     * 生成数组类型的 mock 数据
     * 
     * @param itemSchema 数组元素 schema
     * @param minItems 最小元素数
     * @param maxItems 最大元素数
     * @return 生成的数组
     */
    fun generateArrayData(
        itemSchema: SchemaInfo,
        minItems: Int?,
        maxItems: Int?
    ): List<Any?>
}
