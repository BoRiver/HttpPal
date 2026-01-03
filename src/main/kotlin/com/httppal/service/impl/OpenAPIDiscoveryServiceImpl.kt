package com.httppal.service.impl

import com.httppal.model.*
import com.httppal.service.OpenAPIDiscoveryService
import com.httppal.util.ErrorHandler
import com.httppal.util.LoggingUtils
import com.httppal.util.PerformanceMonitor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.messages.MessageBusConnection
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of OpenAPIDiscoveryService using swagger-parser
 * 使用 swagger-parser 实现 OpenAPI 文件的发现和解析
 */
@Service(Service.Level.PROJECT)
class OpenAPIDiscoveryServiceImpl(private val project: Project) : OpenAPIDiscoveryService {
    
    // 缓存解析结果
    private val fileCache = ConcurrentHashMap<String, CachedResult>()
    
    // 缓存解析错误
    private val errorCache = ConcurrentHashMap<String, List<ParseError>>()
    
    // 文件变更监听器连接
    private var messageBusConnection: MessageBusConnection? = null
    
    // 防抖计时器
    private val debouncedParse = mutableMapOf<String, Long>()
    private val debounceDelay = 300L // 300ms
    
    data class CachedResult(
        val endpoints: List<DiscoveredEndpoint>,
        val timestamp: Long,
        val fileHash: String
    )
    
    override fun discoverOpenAPIFiles(): List<VirtualFile> {
        return ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val files = mutableListOf<VirtualFile>()
            val scope = GlobalSearchScope.projectScope(project)
            
            // 支持的文件名模式
            val patterns = listOf(
                "openapi.yaml", "openapi.yml", "openapi.json",
                "swagger.yaml", "swagger.yml", "swagger.json"
            )
            
            patterns.forEach { pattern ->
                val foundFiles = FilenameIndex.getVirtualFilesByName(pattern, scope)
                files.addAll(foundFiles)
            }
            
            // 查找带前缀的文件（*-openapi.yaml 等）
            val allYamlFiles = FilenameIndex.getVirtualFilesByName("*.yaml", scope) +
                              FilenameIndex.getVirtualFilesByName("*.yml", scope) +
                              FilenameIndex.getVirtualFilesByName("*.json", scope)
            
            allYamlFiles.forEach { file ->
                val name = file.name.lowercase()
                if ((name.contains("openapi") || name.contains("swagger")) && !files.contains(file)) {
                    files.add(file)
                }
            }
            
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.INFO,
                "Discovered OpenAPI files",
                mapOf<String, Any>("fileCount" to files.size)
            )
            
            files
        }
    }

    
    override fun parseOpenAPIFile(file: VirtualFile): List<DiscoveredEndpoint> {
        val filePath = file.path
        
        // 检查缓存
        val fileHash = calculateFileHash(file)
        val cached = fileCache[filePath]
        if (cached != null && cached.fileHash == fileHash) {
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.DEBUG,
                "Using cached OpenAPI parse result",
                mapOf<String, Any>("file" to file.name)
            )
            return cached.endpoints
        }
        
        return PerformanceMonitor.measure(
            operation = "OpenAPI File Parsing",
            threshold = 2000L, // 2 seconds threshold for parsing
            details = mapOf("file" to file.name, "size" to file.length)
        ) {
            ErrorHandler.withErrorHandling(
                "Parse OpenAPI file",
                project
            ) {
                val endpoints = parseOpenAPIFileInternal(file)
                
                // 缓存结果
                fileCache[filePath] = CachedResult(
                    endpoints = endpoints,
                    timestamp = System.currentTimeMillis(),
                    fileHash = fileHash
                )
                
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.INFO,
                    "Parsed OpenAPI file",
                    mapOf<String, Any>(
                        "file" to file.name,
                        "endpointCount" to endpoints.size
                    )
                )
                
                endpoints
            } ?: emptyList()
        }
    }
    
    private fun parseOpenAPIFileInternal(file: VirtualFile): List<DiscoveredEndpoint> {
        val endpoints = mutableListOf<DiscoveredEndpoint>()
        
        try {
            // 使用 swagger-parser 解析 OpenAPI 文件
            val parseOptions = ParseOptions()
            parseOptions.isResolve = true // 解析 $ref 引用
            parseOptions.isResolveFully = true
            
            val result = OpenAPIV3Parser().readLocation(file.path, null, parseOptions)
            val openAPI = result.openAPI
            
            if (openAPI == null) {
                // 解析失败，记录错误
                val errors = result.messages?.map { message ->
                    ParseError(
                        file = file.path,
                        message = message,
                        severity = ErrorSeverity.ERROR
                    )
                } ?: emptyList()
                
                errorCache[file.path] = errors
                
                // Show notification to user about parse errors
                showParseErrorNotification(file, errors)
                
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.ERROR,
                    "Failed to parse OpenAPI file",
                    mapOf<String, Any>(
                        "file" to file.name,
                        "errors" to errors.size
                    )
                )
                
                return emptyList()
            }
            
            // 清除错误缓存
            errorCache.remove(file.path)
            
            // 提取 base URL
            val baseUrl = openAPI.servers?.firstOrNull()?.url ?: ""
            
            // 遍历所有路径和操作
            openAPI.paths?.forEach { (pathStr, pathItem) ->
                extractEndpointsFromPath(pathStr, pathItem, openAPI, file, baseUrl, endpoints)
            }
            
        } catch (e: Exception) {
            LoggingUtils.logError("Error parsing OpenAPI file: ${file.name}", e)
            
            errorCache[file.path] = listOf(
                ParseError(
                    file = file.path,
                    message = "Unexpected error: ${e.message}",
                    severity = ErrorSeverity.ERROR,
                    suggestion = "Please check if the file is a valid OpenAPI 3.0 specification"
                )
            )
            
            // Show notification to user about unexpected error
            showParseErrorNotification(file, errorCache[file.path] ?: emptyList())
        }
        
        return endpoints
    }

    
    private fun extractEndpointsFromPath(
        pathStr: String,
        pathItem: PathItem,
        openAPI: OpenAPI,
        file: VirtualFile,
        baseUrl: String,
        endpoints: MutableList<DiscoveredEndpoint>
    ) {
        // 提取每个 HTTP 方法的操作
        val operations = mapOf(
            HttpMethod.GET to pathItem.get,
            HttpMethod.POST to pathItem.post,
            HttpMethod.PUT to pathItem.put,
            HttpMethod.DELETE to pathItem.delete,
            HttpMethod.PATCH to pathItem.patch,
            HttpMethod.HEAD to pathItem.head,
            HttpMethod.OPTIONS to pathItem.options
        )
        
        operations.forEach { (method, operation) ->
            if (operation != null) {
                val endpoint = createEndpointFromOperation(
                    method = method,
                    path = pathStr,
                    operation = operation,
                    openAPI = openAPI,
                    file = file,
                    baseUrl = baseUrl
                )
                endpoints.add(endpoint)
            }
        }
    }
    
    private fun createEndpointFromOperation(
        method: HttpMethod,
        path: String,
        operation: Operation,
        openAPI: OpenAPI,
        file: VirtualFile,
        baseUrl: String
    ): DiscoveredEndpoint {
        // 提取参数
        val parameters = extractParameters(operation, openAPI)
        
        // 提取 tags
        val tags = operation.tags ?: emptyList()
        
        return DiscoveredEndpoint(
            method = method,
            path = path,
            className = tags.firstOrNull() ?: "OpenAPI",
            methodName = operation.operationId ?: "${method.name.lowercase()}_${path.replace("/", "_")}",
            parameters = parameters,
            sourceFile = file.path,
            lineNumber = 0,
            source = EndpointSource.OPENAPI,
            openAPIFile = file.path,
            operationId = operation.operationId,
            summary = operation.summary ?: operation.description,
            tags = tags
        )
    }
    
    private fun extractParameters(operation: Operation, openAPI: OpenAPI): List<EndpointParameter> {
        val parameters = mutableListOf<EndpointParameter>()
        
        operation.parameters?.forEach { param ->
            val paramType = when (param.`in`) {
                "path" -> ParameterType.PATH
                "query" -> ParameterType.QUERY
                "header" -> ParameterType.HEADER
                else -> ParameterType.QUERY
            }
            
            val schema = param.schema
            val dataType = schema?.type ?: "string"
            
            parameters.add(
                EndpointParameter(
                    name = param.name ?: "unknown",
                    type = paramType,
                    required = param.required ?: false,
                    description = param.description,
                    dataType = dataType,
                    example = schema?.example?.toString(),
                    defaultValue = schema?.default?.toString()
                )
            )
        }
        
        // 提取请求体参数
        operation.requestBody?.let { requestBody ->
            val content = requestBody.content
            if (content != null && content.isNotEmpty()) {
                val mediaType = content.values.firstOrNull()
                val schema = mediaType?.schema
                
                parameters.add(
                    EndpointParameter(
                        name = "body",
                        type = ParameterType.BODY,
                        required = requestBody.required ?: false,
                        description = requestBody.description,
                        dataType = schema?.type ?: "object",
                        example = schema?.example?.toString()
                    )
                )
            }
        }
        
        return parameters
    }

    
    override fun parseAllOpenAPIFiles(): List<DiscoveredEndpoint> {
        val files = discoverOpenAPIFiles()
        val allEndpoints = mutableListOf<DiscoveredEndpoint>()
        
        files.forEach { file ->
            val endpoints = parseOpenAPIFile(file)
            allEndpoints.addAll(endpoints)
        }
        
        return allEndpoints
    }
    
    override fun registerFileChangeListener() {
        if (messageBusConnection != null) {
            return // 已经注册
        }
        
        messageBusConnection = project.messageBus.connect()
        messageBusConnection?.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.forEach { event ->
                    val file = event.file
                    if (file != null && isOpenAPIFile(file)) {
                        // 使用防抖机制
                        val now = System.currentTimeMillis()
                        val lastParse = debouncedParse[file.path] ?: 0
                        
                        if (now - lastParse > debounceDelay) {
                            debouncedParse[file.path] = now
                            
                            // 在后台线程重新解析
                            ApplicationManager.getApplication().executeOnPooledThread {
                                parseOpenAPIFile(file)
                            }
                        }
                    }
                }
            }
        })
        
        LoggingUtils.logInfo("Registered OpenAPI file change listener")
    }
    
    override fun clearCacheAndRefresh() {
        fileCache.clear()
        errorCache.clear()
        debouncedParse.clear()
        
        LoggingUtils.logInfo("Cleared OpenAPI cache")
        
        // 重新解析所有文件
        ApplicationManager.getApplication().executeOnPooledThread {
            parseAllOpenAPIFiles()
        }
    }
    
    override fun getParseErrors(file: VirtualFile): List<ParseError> {
        return errorCache[file.path] ?: emptyList()
    }
    
    private fun isOpenAPIFile(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name.contains("openapi") || name.contains("swagger")
    }
    
    private fun calculateFileHash(file: VirtualFile): String {
        return "${file.timeStamp}_${file.length}"
    }
    
    /**
     * Show notification to user about parse errors
     * Implements Task 14.1: Error notification with specific error information
     */
    private fun showParseErrorNotification(file: VirtualFile, errors: List<ParseError>) {
        if (errors.isEmpty()) return
        
        val errorCount = errors.size
        val errorSummary = errors.take(3).joinToString("\n") { "• ${it.message}" }
        val moreErrors = if (errorCount > 3) "\n... and ${errorCount - 3} more errors" else ""
        
        val content = """
            Failed to parse OpenAPI file: ${file.name}
            
            $errorSummary$moreErrors
            
            ${errors.firstOrNull()?.suggestion ?: "Please check the file format and try again."}
        """.trimIndent()
        
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("HttpPal.Notifications")
                .createNotification(
                    "OpenAPI Parse Error",
                    content,
                    NotificationType.ERROR
                )
                .notify(project)
        }
        
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.ERROR,
            "Notified user about OpenAPI parse errors",
            mapOf(
                "file" to file.name,
                "errorCount" to errorCount
            )
        )
    }
    
    fun dispose() {
        messageBusConnection?.disconnect()
        fileCache.clear()
        errorCache.clear()
        debouncedParse.clear()
    }
}
