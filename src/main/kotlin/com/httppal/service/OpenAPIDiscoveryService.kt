package com.httppal.service

import com.httppal.model.DiscoveredEndpoint
import com.httppal.model.ParseError
import com.intellij.openapi.vfs.VirtualFile

/**
 * Service for discovering and parsing OpenAPI 3.0 specification files
 * 负责发现和解析 OpenAPI 规范文件
 */
interface OpenAPIDiscoveryService {
    
    /**
     * 发现项目中的所有 OpenAPI 文件
     * 支持的文件命名模式：
     * - openapi.yaml / openapi.yml / openapi.json
     * - swagger.yaml / swagger.yml / swagger.json
     * - *-openapi.yaml / *-openapi.json
     * - *-swagger.yaml / *-swagger.json
     * 
     * @return OpenAPI 文件的虚拟文件列表
     */
    fun discoverOpenAPIFiles(): List<VirtualFile>
    
    /**
     * 解析 OpenAPI 文件并提取端点
     * 此方法在后台线程执行，不会阻塞 UI
     * 
     * @param file OpenAPI 规范文件
     * @return 发现的端点列表
     */
    fun parseOpenAPIFile(file: VirtualFile): List<DiscoveredEndpoint>
    
    /**
     * 解析所有 OpenAPI 文件
     * 此方法会自动发现并解析项目中的所有 OpenAPI 文件
     * 
     * @return 所有发现的端点列表
     */
    fun parseAllOpenAPIFiles(): List<DiscoveredEndpoint>
    
    /**
     * 注册文件变更监听器
     * 当 OpenAPI 文件被修改时，自动触发重新解析
     */
    fun registerFileChangeListener()
    
    /**
     * 清除缓存并强制重新解析
     * 用于手动刷新功能
     */
    fun clearCacheAndRefresh()
    
    /**
     * 获取解析错误信息
     * 
     * @param file OpenAPI 文件
     * @return 错误信息列表，如果解析成功则返回空列表
     */
    fun getParseErrors(file: VirtualFile): List<ParseError>
}
