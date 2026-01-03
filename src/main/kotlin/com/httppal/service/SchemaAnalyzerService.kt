package com.httppal.service

import com.httppal.model.SchemaInfo
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse

/**
 * Service for analyzing OpenAPI schema definitions
 * 负责分析 OpenAPI schema 定义，提取详细的类型信息
 */
interface SchemaAnalyzerService {
    
    /**
     * 分析参数 schema
     * 
     * @param parameter OpenAPI 参数对象
     * @param components 组件定义（用于解析 $ref）
     * @return Schema 信息
     */
    fun analyzeParameterSchema(
        parameter: Parameter,
        components: Components?
    ): SchemaInfo
    
    /**
     * 分析请求体 schema
     * 
     * @param requestBody OpenAPI 请求体对象
     * @param components 组件定义
     * @return Schema 信息
     */
    fun analyzeRequestBodySchema(
        requestBody: RequestBody,
        components: Components?
    ): SchemaInfo
    
    /**
     * 分析响应 schema
     * 
     * @param response OpenAPI 响应对象
     * @param components 组件定义
     * @return Schema 信息映射（按 content type）
     */
    fun analyzeResponseSchema(
        response: ApiResponse,
        components: Components?
    ): Map<String, SchemaInfo>
    
    /**
     * 解析 schema 引用（$ref）
     * 
     * @param ref 引用字符串（例如：#/components/schemas/User）
     * @param components 组件定义
     * @return 解析后的 Schema，如果无法解析则返回 null
     */
    fun resolveSchemaReference(
        ref: String,
        components: Components?
    ): Schema<*>?
    
    /**
     * 递归分析嵌套 schema
     * 
     * @param schema OpenAPI Schema 对象
     * @param components 组件定义
     * @param depth 当前递归深度（防止无限递归）
     * @return Schema 信息
     */
    fun analyzeSchema(
        schema: Schema<*>,
        components: Components?,
        depth: Int = 0
    ): SchemaInfo
}
