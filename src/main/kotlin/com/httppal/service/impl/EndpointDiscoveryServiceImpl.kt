package com.httppal.service.impl

import com.httppal.model.DiscoveredEndpoint
import com.httppal.model.EndpointParameter
import com.httppal.model.HttpMethod
import com.httppal.model.ParameterType
import com.httppal.service.EndpointDiscoveryService
import com.httppal.service.EndpointChangeListener
import com.httppal.service.EndpointChangeNotification
import com.httppal.util.ErrorHandler
import com.httppal.util.LoggingUtils
import com.httppal.util.PerformanceMonitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.messages.MessageBusConnection
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of EndpointDiscoveryService using PSI analysis
 * Supports Spring MVC and JAX-RS annotation discovery
 */
@Service(Service.Level.PROJECT)
class EndpointDiscoveryServiceImpl(private val project: Project) : EndpointDiscoveryService {
    
    private val listeners = mutableListOf<(List<DiscoveredEndpoint>) -> Unit>()
    private val endpointChangeListeners = mutableListOf<EndpointChangeListener>()
    private var cachedEndpoints = ConcurrentHashMap<String, List<DiscoveredEndpoint>>()
    private var messageBusConnection: MessageBusConnection? = null
    
    // Performance optimization: Cache file modification timestamps to avoid redundant scans
    private val fileTimestamps = ConcurrentHashMap<String, Long>()
    
    // Performance optimization: Cache PSI file references to avoid repeated lookups
    private val psiFileCache = ConcurrentHashMap<String, PsiFile>()
    
    // Performance optimization: Track files that have been scanned to avoid duplicate work
    private val scannedFiles = ConcurrentHashMap.newKeySet<String>()
    
    // Discovery coordination: Prevent redundant scans during startup
    @Volatile
    private var isDiscovering = false
    
    @Volatile
    private var hasDiscovered = false
    
    private val discoveryLock = Any()
    
    // Spring MVC annotations
    private val springMvcAnnotations = mapOf(
        "RequestMapping" to null, // Can be any method
        "GetMapping" to HttpMethod.GET,
        "PostMapping" to HttpMethod.POST,
        "PutMapping" to HttpMethod.PUT,
        "DeleteMapping" to HttpMethod.DELETE,
        "PatchMapping" to HttpMethod.PATCH
    )
    
    // JAX-RS annotations
    private val jaxRsAnnotations = mapOf(
        "GET" to HttpMethod.GET,
        "POST" to HttpMethod.POST,
        "PUT" to HttpMethod.PUT,
        "DELETE" to HttpMethod.DELETE,
        "PATCH" to HttpMethod.PATCH,
        "HEAD" to HttpMethod.HEAD,
        "OPTIONS" to HttpMethod.OPTIONS
    )
    
    init {
        setupFileChangeListener()
    }
    
    override fun discoverEndpoints(): List<DiscoveredEndpoint> {
        // Check if IDE is in dumb mode (indexing)
        if (DumbService.isDumb(project)) {
            LoggingUtils.logWarning("Cannot discover endpoints: IDE is in dumb mode (indexing)")
            return emptyList()
        }
        
        // Check if already discovered and cache is valid
        if (hasDiscovered && cachedEndpoints.isNotEmpty()) {
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.DEBUG,
                "Using cached endpoints from previous discovery",
                mapOf<String, Any>("cachedFileCount" to cachedEndpoints.size)
            )
            return cachedEndpoints.values.flatten()
        }
        
        // Use synchronization to ensure only one thread performs discovery
        synchronized(discoveryLock) {
            // Double-check after acquiring lock
            if (hasDiscovered && cachedEndpoints.isNotEmpty()) {
                return cachedEndpoints.values.flatten()
            }
            
            // Check if another thread is already discovering
            if (isDiscovering) {
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.DEBUG,
                    "Discovery already in progress, waiting for completion",
                    emptyMap()
                )
                // Wait for the other thread to complete
                while (isDiscovering) {
                    try {
                        (discoveryLock as Object).wait(100)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return emptyList()
                    }
                }
                // Return the results from the completed discovery
                return cachedEndpoints.values.flatten()
            }
            
            // Mark as discovering
            isDiscovering = true
        }
        
        try {
            LoggingUtils.logInfo("=== Starting endpoint discovery ===")

            return LoggingUtils.measureAndLog("Discover API endpoints") {
                PerformanceMonitor.measureEndpointDiscovery(
                    details = mapOf("operation" to "full_scan")
                ) {
                    ReadAction.compute<List<DiscoveredEndpoint>, RuntimeException> {
                        ErrorHandler.withErrorHandling(
                            "Discover endpoints",
                            project
                        ) {
                            val allEndpoints = mutableListOf<DiscoveredEndpoint>()

                            // Search Java files with optimized PSI queries
                            val javaFiles = FileTypeIndex.getFiles(
                                com.intellij.ide.highlighter.JavaFileType.INSTANCE,
                                GlobalSearchScope.projectScope(project)
                            )

                            LoggingUtils.logWithContext(
                                LoggingUtils.LogLevel.DEBUG,
                                "Scanning Java files for endpoints",
                                mapOf<String, Any>("fileCount" to javaFiles.size)
                            )

                            javaFiles.forEach { virtualFile ->
                                try {
                                    // Performance optimization: Check if file has been modified since last scan
                                    val filePath = virtualFile.path
                                    val currentTimestamp = virtualFile.timeStamp
                                    val cachedTimestamp = fileTimestamps[filePath]

                                    if (cachedTimestamp != null && cachedTimestamp == currentTimestamp && cachedEndpoints.containsKey(
                                            filePath
                                        )
                                    ) {
                                        // Use cached endpoints for this file
                                        val cachedFileEndpoints = cachedEndpoints[filePath] ?: emptyList()
                                        allEndpoints.addAll(cachedFileEndpoints)
                                        scannedFiles.add(filePath)
                                    } else {
                                        // Scan the file
                                        val psiFile = getPsiFile(virtualFile)
                                        if (psiFile is PsiJavaFile) {
                                            val endpoints = discoverEndpointsInJavaFile(psiFile)
                                            allEndpoints.addAll(endpoints)
                                            cachedEndpoints[filePath] = endpoints
                                            fileTimestamps[filePath] = currentTimestamp
                                            scannedFiles.add(filePath)

                                            if (endpoints.isNotEmpty()) {
                                                LoggingUtils.logWithContext(
                                                    LoggingUtils.LogLevel.DEBUG,
                                                    "Found endpoints in Java file",
                                                    mapOf<String, Any>(
                                                        "file" to virtualFile.name,
                                                        "endpointCount" to endpoints.size
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    LoggingUtils.logWithContext(
                                        LoggingUtils.LogLevel.WARN,
                                        "Failed to scan Java file for endpoints",
                                        mapOf<String, Any>(
                                            "file" to virtualFile.name,
                                            "error" to (e.message ?: "Unknown error")
                                        ),
                                        e
                                    )
                                }
                            }

                            // Search Kotlin files with optimized PSI queries
                            val kotlinFiles = FileTypeIndex.getFiles(
                                FileTypeManager.getInstance().getFileTypeByExtension("kt"),
                                GlobalSearchScope.projectScope(project)
                            )

                            LoggingUtils.logWithContext(
                                LoggingUtils.LogLevel.DEBUG,
                                "Scanning Kotlin files for endpoints",
                                mapOf<String, Any>("fileCount" to kotlinFiles.size)
                            )

                            kotlinFiles.forEach { virtualFile ->
                                try {
                                    // Performance optimization: Check if file has been modified since last scan
                                    val filePath = virtualFile.path
                                    val currentTimestamp = virtualFile.timeStamp
                                    val cachedTimestamp = fileTimestamps[filePath]

                                    if (cachedTimestamp != null && cachedTimestamp == currentTimestamp && cachedEndpoints.containsKey(
                                            filePath
                                        )
                                    ) {
                                        // Use cached endpoints for this file
                                        val cachedFileEndpoints = cachedEndpoints[filePath] ?: emptyList()
                                        allEndpoints.addAll(cachedFileEndpoints)
                                        scannedFiles.add(filePath)
                                    } else {
                                        // Scan the file
                                        val psiFile = getPsiFile(virtualFile)
                                        if (psiFile != null) {
                                            val endpoints = discoverEndpointsInKotlinFile(psiFile)
                                            allEndpoints.addAll(endpoints)
                                            cachedEndpoints[filePath] = endpoints
                                            fileTimestamps[filePath] = currentTimestamp
                                            scannedFiles.add(filePath)

                                            if (endpoints.isNotEmpty()) {
                                                LoggingUtils.logWithContext(
                                                    LoggingUtils.LogLevel.DEBUG,
                                                    "Found endpoints in Kotlin file",
                                                    mapOf<String, Any>(
                                                        "file" to virtualFile.name,
                                                        "endpointCount" to endpoints.size
                                                    )
                                                )
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    LoggingUtils.logWithContext(
                                        LoggingUtils.LogLevel.WARN,
                                        "Failed to scan Kotlin file for endpoints",
                                        mapOf<String, Any>(
                                            "file" to virtualFile.name,
                                            "error" to (e.message ?: "Unknown error")
                                        ),
                                        e
                                    )
                                }
                            }

                            LoggingUtils.logWithContext(
                                LoggingUtils.LogLevel.INFO,
                                "Endpoint discovery completed",
                                mapOf<String, Any>(
                                    "totalEndpoints" to allEndpoints.size,
                                    "javaFilesScanned" to javaFiles.size,
                                    "kotlinFilesScanned" to kotlinFiles.size,
                                    "cachedFiles" to (javaFiles.size + kotlinFiles.size - scannedFiles.size),
                                    "scannedFiles" to scannedFiles.size
                                )
                            )

                            if (allEndpoints.isEmpty()) {
                                LoggingUtils.logWarning("No endpoints found! Java files: ${javaFiles.size}, Kotlin files: ${kotlinFiles.size}")
                            }

                            // 集成 OpenAPI 端点
                            try {
                                val openAPIService =
                                    project.getService(com.httppal.service.OpenAPIDiscoveryService::class.java)
                                if (openAPIService != null) {
                                    val openAPIEndpoints = openAPIService.parseAllOpenAPIFiles()
                                    allEndpoints.addAll(openAPIEndpoints)

                                    LoggingUtils.logWithContext(
                                        LoggingUtils.LogLevel.INFO,
                                        "Added OpenAPI endpoints",
                                        mapOf<String, Any>("openAPIEndpoints" to openAPIEndpoints.size)
                                    )
                                }
                            } catch (e: Exception) {
                                LoggingUtils.logError("Failed to discover OpenAPI endpoints", e)
                            }

                            return@withErrorHandling allEndpoints
                        } ?: emptyList()
                    }
                }
            }
        }finally {
            // Mark discovery as complete and notify waiting threads
            synchronized(discoveryLock) {
                isDiscovering = false
                hasDiscovered = true
                (discoveryLock as Object).notifyAll()
            }
        }
    }
    
    /**
     * Get PSI file with caching to avoid repeated lookups
     * Performance optimization for requirement 2.1, 9.3
     */
    private fun getPsiFile(virtualFile: com.intellij.openapi.vfs.VirtualFile): PsiFile? {
        val filePath = virtualFile.path
        
        // Check cache first
        val cachedPsiFile = psiFileCache[filePath]
        if (cachedPsiFile != null && cachedPsiFile.isValid) {
            return cachedPsiFile
        }
        
        // Lookup and cache
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        if (psiFile != null) {
            psiFileCache[filePath] = psiFile
        }
        
        return psiFile
    }
    
    override fun refreshEndpoints() {
        // Perform refresh in background thread to avoid blocking EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Reset discovery state to allow fresh scan
                synchronized(discoveryLock) {
                    hasDiscovered = false
                    isDiscovering = false
                }
                
                // Clear caches to force full rescan
                cachedEndpoints.clear()
                fileTimestamps.clear()
                psiFileCache.clear()
                scannedFiles.clear()
                
                val newEndpoints = discoverEndpoints()
                
                // Notify listeners on EDT thread
                ApplicationManager.getApplication().invokeLater {
                    notifyListeners(newEndpoints)
                }
            } catch (e: Exception) {
                LoggingUtils.logError("Failed to refresh endpoints", e)
            }
        }
    }
    
    override fun notifyEndpointsChanged(endpoints: List<DiscoveredEndpoint>) {
        try {
            // Notify listeners on EDT thread to ensure UI updates are safe
            ApplicationManager.getApplication().invokeLater {
                try {
                    val listenerCount = listeners.size + endpointChangeListeners.size
                    
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.DEBUG,
                        "Notifying listeners of endpoint changes",
                        mapOf<String, Any>(
                            "endpointCount" to endpoints.size,
                            "listenerCount" to listenerCount
                        )
                    )
                    
                    notifyListeners(endpoints)
                    
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.DEBUG,
                        "Successfully notified all listeners",
                        mapOf<String, Any>("listenerCount" to listenerCount)
                    )
                } catch (e: Exception) {
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.ERROR,
                        "Failed to notify listeners",
                        mapOf<String, Any>("error" to (e.message ?: "Unknown error")),
                        e
                    )
                }
            }
        } catch (e: Exception) {
            LoggingUtils.logWithContext(
                LoggingUtils.LogLevel.ERROR,
                "Failed to schedule listener notification",
                mapOf<String, Any>("error" to (e.message ?: "Unknown error")),
                e
            )
        }
    }
    
    override fun addEndpointChangeListener(listener: (List<DiscoveredEndpoint>) -> Unit) {
        listeners.add(listener)
    }
    
    override fun removeEndpointChangeListener(listener: (List<DiscoveredEndpoint>) -> Unit) {
        listeners.remove(listener)
    }
    
    override fun registerEndpointChangeListener(listener: EndpointChangeListener) {
        endpointChangeListeners.add(listener)
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.DEBUG,
            "Registered endpoint change listener",
            mapOf<String, Any>("listenerClass" to listener::class.simpleName.orEmpty())
        )
    }
    
    override fun unregisterEndpointChangeListener(listener: EndpointChangeListener) {
        endpointChangeListeners.remove(listener)
        LoggingUtils.logWithContext(
            LoggingUtils.LogLevel.DEBUG,
            "Unregistered endpoint change listener",
            mapOf<String, Any>("listenerClass" to listener::class.simpleName.orEmpty())
        )
    }
    
    override fun getEndpointsForFile(file: PsiFile): List<DiscoveredEndpoint> {
        return ReadAction.compute<List<DiscoveredEndpoint>, RuntimeException> {
            when (file) {
                is PsiJavaFile -> discoverEndpointsInJavaFile(file)
                else -> discoverEndpointsInKotlinFile(file)
            }
        }
    }
    
    override fun hasEndpoints(file: PsiFile): Boolean {
        return getEndpointsForFile(file).isNotEmpty()
    }
    
    override fun getEndpointsByClass(): Map<String, List<DiscoveredEndpoint>> {
        val allEndpoints = cachedEndpoints.values.flatten()
        return allEndpoints.groupBy { it.className }
    }
    
    override fun scanFile(file: PsiFile): List<DiscoveredEndpoint> {
        return ReadAction.compute<List<DiscoveredEndpoint>, RuntimeException> {
            PerformanceMonitor.measureFileScan(
                fileName = file.name,
                details = mapOf("filePath" to (file.virtualFile?.path ?: file.name))
            ) {
                ErrorHandler.withErrorHandling(
                    "Scan file for endpoints",
                    project
                ) {
                    val filePath = file.virtualFile?.path ?: file.name
                    val oldEndpoints = cachedEndpoints[filePath] ?: emptyList()
                    
                    // Performance optimization: Check if file has been modified since last scan
                    val virtualFile = file.virtualFile
                    if (virtualFile != null) {
                        val currentTimestamp = virtualFile.timeStamp
                        val cachedTimestamp = fileTimestamps[filePath]
                        
                        if (cachedTimestamp != null && cachedTimestamp == currentTimestamp && cachedEndpoints.containsKey(filePath)) {
                            // File hasn't changed, return cached endpoints
                            LoggingUtils.logWithContext(
                                LoggingUtils.LogLevel.DEBUG,
                                "Using cached endpoints for file",
                                mapOf<String, Any>(
                                    "file" to file.name,
                                    "endpointCount" to oldEndpoints.size
                                )
                            )
                            return@withErrorHandling oldEndpoints
                        }
                    }
                    
                    // Scan the file
                    val endpoints = when (file) {
                        is PsiJavaFile -> discoverEndpointsInJavaFile(file)
                        else -> discoverEndpointsInKotlinFile(file)
                    }
                    
                    // Update cache with new endpoints for this file
                    cachedEndpoints[filePath] = endpoints
                    if (virtualFile != null) {
                        fileTimestamps[filePath] = virtualFile.timeStamp
                        psiFileCache[filePath] = file
                    }
                    
                    // Detect changes and notify listeners
                    val notification = detectEndpointChanges(oldEndpoints, endpoints)
                    if (notification.hasChanges()) {
                        notifyEndpointChangeListeners(notification)
                    }
                    
                    LoggingUtils.logWithContext(
                        LoggingUtils.LogLevel.DEBUG,
                        "Scanned file for endpoints",
                        mapOf<String, Any>(
                            "file" to file.name,
                            "endpointCount" to endpoints.size,
                            "added" to notification.addedEndpoints.size,
                            "modified" to notification.modifiedEndpoints.size,
                            "removed" to notification.removedEndpoints.size
                        )
                    )
                    
                    return@withErrorHandling endpoints
                } ?: emptyList()
            }
        }
    }
    
    override fun hasDiscoveredEndpoints(): Boolean {
        return hasDiscovered
    }
    
    override fun getCachedEndpoints(): List<DiscoveredEndpoint> {
        return cachedEndpoints.values.flatten()
    }
    
    private fun discoverEndpointsInJavaFile(javaFile: PsiJavaFile): List<DiscoveredEndpoint> {
        val endpoints = mutableListOf<DiscoveredEndpoint>()
        
        LoggingUtils.logDebug("Scanning Java file: ${javaFile.name}, classes count: ${javaFile.classes.size}")
        
        // Performance optimization: Use optimized PSI queries
        // Only process classes that have REST annotations
        javaFile.classes.forEach { psiClass ->
            LoggingUtils.logDebug("Checking class: ${psiClass.name}")
            
            // Quick check: Skip classes without REST annotations
            if (!hasRestAnnotations(psiClass)) {
                LoggingUtils.logDebug("Class ${psiClass.name} has no REST annotations, skipping")
                return@forEach
            }
            
            LoggingUtils.logDebug("Class ${psiClass.name} has REST annotations, checking methods")
            
            val classPath = extractClassLevelPath(psiClass)
            
            psiClass.methods.forEach { method ->
                LoggingUtils.logDebug("Checking method: ${method.name}")
                
                // Quick check: Skip methods without REST annotations
                if (!hasRestAnnotations(method)) {
                    LoggingUtils.logDebug("Method ${method.name} has no REST annotations, skipping")
                    return@forEach
                }
                
                LoggingUtils.logDebug("Method ${method.name} has REST annotations, extracting endpoint")
                
                val endpoint = extractEndpointFromJavaMethod(method, psiClass, classPath, javaFile)
                if (endpoint != null) {
                    LoggingUtils.logInfo("Found endpoint: ${endpoint.method} ${endpoint.path}")
                    endpoints.add(endpoint)
                } else {
                    LoggingUtils.logDebug("Failed to extract endpoint from method ${method.name}")
                }
            }
        }
        
        LoggingUtils.logInfo("Discovered ${endpoints.size} endpoints in ${javaFile.name}")
        return endpoints
    }
    
    /**
     * Quick check if a PSI element has REST annotations
     * Performance optimization to avoid processing non-REST classes/methods
     */
    private fun hasRestAnnotations(element: PsiModifierListOwner): Boolean {
        val annotations = element.annotations
        if (annotations.isEmpty()) return false
        
        val result = annotations.any { annotation ->
            val qualifiedName = annotation.qualifiedName ?: return@any false
            val shortName = qualifiedName.substringAfterLast('.')
            
            // Debug logging
            LoggingUtils.logDebug("Checking annotation: $qualifiedName (short: $shortName)")
            
            // Check for Spring MVC or JAX-RS annotations
            val hasAnnotation = springMvcAnnotations.containsKey(shortName) || 
                jaxRsAnnotations.containsKey(shortName) ||
                shortName == "Path" || // JAX-RS @Path
                shortName == "RequestMapping" // Spring @RequestMapping
            
            if (hasAnnotation) {
                LoggingUtils.logDebug("Found REST annotation: $shortName")
            }
            
            hasAnnotation
        }
        
        return result
    }
    
    private fun discoverEndpointsInKotlinFile(kotlinFile: PsiFile): List<DiscoveredEndpoint> {
        val endpoints = mutableListOf<DiscoveredEndpoint>()
        
        // Performance optimization: Use optimized PSI queries
        val ktClasses = PsiTreeUtil.findChildrenOfType(kotlinFile, KtClass::class.java)
        ktClasses.forEach { ktClass ->
            // Quick check: Skip classes without REST annotations
            if (!hasKotlinRestAnnotations(ktClass)) {
                return@forEach
            }
            
            val classPath = extractClassLevelPathFromKotlin(ktClass)
            
            val ktFunctions = PsiTreeUtil.findChildrenOfType(ktClass, KtFunction::class.java)
            ktFunctions.forEach { function ->
                // Quick check: Skip functions without REST annotations
                if (!hasKotlinRestAnnotations(function)) {
                    return@forEach
                }
                
                val endpoint = extractEndpointFromKotlinMethod(function, ktClass, classPath, kotlinFile)
                if (endpoint != null) {
                    endpoints.add(endpoint)
                }
            }
        }
        
        return endpoints
    }
    
    /**
     * Quick check if a Kotlin element has REST annotations
     * Performance optimization to avoid processing non-REST classes/functions
     */
    private fun hasKotlinRestAnnotations(element: org.jetbrains.kotlin.psi.KtAnnotated): Boolean {
        val annotations = element.annotationEntries
        if (annotations.isEmpty()) return false
        
        return annotations.any { annotation ->
            val shortName = annotation.shortName?.asString() ?: return@any false
            
            // Check for Spring MVC or JAX-RS annotations
            springMvcAnnotations.containsKey(shortName) || 
            jaxRsAnnotations.containsKey(shortName) ||
            shortName == "Path" || // JAX-RS @Path
            shortName == "RequestMapping" // Spring @RequestMapping
        }
    }
    
    private fun extractEndpointFromJavaMethod(
        method: PsiMethod,
        psiClass: PsiClass,
        classPath: String,
        file: PsiJavaFile
    ): DiscoveredEndpoint? {
        val annotations = method.annotations
        
        // 提取 OpenAPI @Operation 注解信息
        var operationSummary: String? = null
        var operationDescription: String? = null
        var operationId: String? = null
        
        val operationAnnotation = annotations.find { 
            it.qualifiedName == "io.swagger.v3.oas.annotations.Operation" 
        }
        if (operationAnnotation != null) {
            operationSummary = extractAnnotationStringValue(operationAnnotation, "summary")
            operationDescription = extractAnnotationStringValue(operationAnnotation, "description")
            operationId = extractAnnotationStringValue(operationAnnotation, "operationId")
        } else {
            // Try Swagger 2 @ApiOperation
            val apiOperationAnnotation = annotations.find {
                it.qualifiedName == "io.swagger.annotations.ApiOperation"
            }
            if (apiOperationAnnotation != null) {
                operationSummary = extractAnnotationStringValue(apiOperationAnnotation, "value")
                operationDescription = extractAnnotationStringValue(apiOperationAnnotation, "notes")
                // nickname is sometimes used as operationId in Swagger 2
                operationId = extractAnnotationStringValue(apiOperationAnnotation, "nickname")
            }
        }
        
        // 提取类级别的 @Tag 注解
        val classTags = extractTagsFromClass(psiClass)
        
        for (annotation in annotations) {
            val annotationName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
            
            // Check Spring MVC annotations
            if (springMvcAnnotations.containsKey(annotationName)) {
                val httpMethod = extractHttpMethodFromSpringAnnotation(annotation, annotationName)
                val path = extractPathFromSpringAnnotation(annotation, classPath)
                val parameters = extractParametersFromJavaMethod(method)
                
                // Extract Swagger 2 @ApiImplicitParams
                val implicitParams = extractImplicitParameters(method)
                
                // Extract Swagger 3 @Parameters (method level)
                val commonParams = extractCommonParameters(method)
                
                // Merge parameters: implicit/common params can enrich or add to existing params
                val mergedParameters = mergeParameters(parameters, implicitParams, commonParams)
                
                if (httpMethod != null && path != null) {
                    return DiscoveredEndpoint(
                        method = httpMethod,
                        path = path,
                        className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown",
                        methodName = method.name,
                        parameters = mergedParameters,
                        sourceFile = file.virtualFile?.path ?: file.name,
                        lineNumber = getLineNumber(method),
                        source = com.httppal.model.EndpointSource.CODE_SCAN,
                        operationId = operationId,
                        summary = operationSummary,
                        description = operationDescription,
                        tags = classTags
                    )
                }
            }
            
            // Check JAX-RS annotations
            if (jaxRsAnnotations.containsKey(annotationName)) {
                val httpMethod = jaxRsAnnotations[annotationName]!!
                val path = extractPathFromJaxRsMethod(method, classPath)
                val parameters = extractParametersFromJavaMethod(method)
                
                return DiscoveredEndpoint(
                    method = httpMethod,
                    path = path,
                    className = psiClass.qualifiedName ?: psiClass.name ?: "Unknown",
                    methodName = method.name,
                    parameters = parameters,
                    sourceFile = file.virtualFile?.path ?: file.name,
                    lineNumber = getLineNumber(method),
                    source = com.httppal.model.EndpointSource.CODE_SCAN,
                    operationId = operationId,
                    summary = operationSummary,
                    description = operationDescription,
                    tags = classTags
                )
            }
        }
        
        return null
    }
    
    private fun extractEndpointFromKotlinMethod(
        function: KtFunction,
        ktClass: KtClass,
        classPath: String,
        file: PsiFile
    ): DiscoveredEndpoint? {
        val annotations = function.annotationEntries
        
        for (annotation in annotations) {
            val annotationName = annotation.shortName?.asString() ?: continue
            
            // Check Spring MVC annotations
            if (springMvcAnnotations.containsKey(annotationName)) {
                val httpMethod = extractHttpMethodFromKotlinSpringAnnotation(annotation, annotationName)
                val path = extractPathFromKotlinSpringAnnotation(annotation, classPath)
                val parameters = extractParametersFromKotlinMethod(function)
                
                if (httpMethod != null && path != null) {
                    return DiscoveredEndpoint(
                        method = httpMethod,
                        path = path,
                        className = ktClass.fqName?.asString() ?: ktClass.name ?: "Unknown",
                        methodName = function.name ?: "Unknown",
                        parameters = parameters,
                        sourceFile = file.virtualFile?.path ?: file.name,
                        lineNumber = getLineNumber(function)
                    )
                }
            }
            
            // Check JAX-RS annotations
            if (jaxRsAnnotations.containsKey(annotationName)) {
                val httpMethod = jaxRsAnnotations[annotationName]!!
                val path = extractPathFromKotlinJaxRsMethod(function, classPath)
                val parameters = extractParametersFromKotlinMethod(function)
                
                return DiscoveredEndpoint(
                    method = httpMethod,
                    path = path,
                    className = ktClass.fqName?.asString() ?: ktClass.name ?: "Unknown",
                    methodName = function.name ?: "Unknown",
                    parameters = parameters,
                    sourceFile = file.virtualFile?.path ?: file.name,
                    lineNumber = getLineNumber(function)
                )
            }
        }
        
        return null
    }
    
    private fun extractClassLevelPath(psiClass: PsiClass): String {
        val requestMappingAnnotation = psiClass.annotations.find { 
            it.qualifiedName?.endsWith("RequestMapping") == true 
        }
        
        return if (requestMappingAnnotation != null) {
            extractValueFromAnnotation(requestMappingAnnotation) ?: ""
        } else {
            ""
        }
    }
    
    private fun extractClassLevelPathFromKotlin(ktClass: KtClass): String {
        val requestMappingAnnotation = ktClass.annotationEntries.find {
            it.shortName?.asString() == "RequestMapping"
        }
        
        return if (requestMappingAnnotation != null) {
            extractValueFromKotlinAnnotation(requestMappingAnnotation) ?: ""
        } else {
            ""
        }
    }
    
    private fun extractHttpMethodFromSpringAnnotation(annotation: PsiAnnotation, annotationName: String): HttpMethod? {
        return when (annotationName) {
            "RequestMapping" -> {
                // Extract method from RequestMapping annotation
                val methodAttribute = annotation.findAttributeValue("method")
                if (methodAttribute != null) {
                    val methodText = methodAttribute.getText() // 使用 getText() 方法而不是 .text
                    when {
                        methodText.contains("GET") -> HttpMethod.GET
                        methodText.contains("POST") -> HttpMethod.POST
                        methodText.contains("PUT") -> HttpMethod.PUT
                        methodText.contains("DELETE") -> HttpMethod.DELETE
                        methodText.contains("PATCH") -> HttpMethod.PATCH
                        else -> HttpMethod.GET // Default to GET
                    }
                } else {
                    HttpMethod.GET // Default to GET if no method specified
                }
            }
            else -> springMvcAnnotations[annotationName]
        }
    }
    
    private fun extractHttpMethodFromKotlinSpringAnnotation(annotation: org.jetbrains.kotlin.psi.KtAnnotationEntry, annotationName: String): HttpMethod? {
        return when (annotationName) {
            "RequestMapping" -> {
                // Extract method from RequestMapping annotation
                val methodArgument = annotation.valueArguments.find { 
                    it.getArgumentName()?.asName?.asString() == "method" 
                }
                if (methodArgument != null) {
                    val methodText = methodArgument.toString()
                    when {
                        methodText.contains("GET") -> HttpMethod.GET
                        methodText.contains("POST") -> HttpMethod.POST
                        methodText.contains("PUT") -> HttpMethod.PUT
                        methodText.contains("DELETE") -> HttpMethod.DELETE
                        methodText.contains("PATCH") -> HttpMethod.PATCH
                        else -> HttpMethod.GET
                    }
                } else {
                    HttpMethod.GET
                }
            }
            else -> springMvcAnnotations[annotationName]
        }
    }
    
    private fun extractPathFromSpringAnnotation(annotation: PsiAnnotation, classPath: String): String? {
        val path = extractValueFromAnnotation(annotation) ?: return null
        return combinePaths(classPath, path)
    }
    
    private fun extractPathFromKotlinSpringAnnotation(annotation: org.jetbrains.kotlin.psi.KtAnnotationEntry, classPath: String): String? {
        val path = extractValueFromKotlinAnnotation(annotation) ?: return null
        return combinePaths(classPath, path)
    }
    
    private fun extractPathFromJaxRsMethod(method: PsiMethod, classPath: String): String {
        val pathAnnotation = method.annotations.find { 
            it.qualifiedName?.endsWith("Path") == true 
        }
        
        val methodPath = if (pathAnnotation != null) {
            extractValueFromAnnotation(pathAnnotation) ?: ""
        } else {
            ""
        }
        
        return combinePaths(classPath, methodPath)
    }
    
    private fun extractPathFromKotlinJaxRsMethod(function: KtFunction, classPath: String): String {
        val pathAnnotation = function.annotationEntries.find {
            it.shortName?.asString() == "Path"
        }
        
        val methodPath = if (pathAnnotation != null) {
            extractValueFromKotlinAnnotation(pathAnnotation) ?: ""
        } else {
            ""
        }
        
        return combinePaths(classPath, methodPath)
    }
    
    private fun extractValueFromAnnotation(annotation: PsiAnnotation): String? {
        val valueAttribute = annotation.findAttributeValue("value") ?: annotation.findAttributeValue(null) ?: annotation.findAttributeValue("path")
        val text = valueAttribute?.text?.replace("\"", "")?.trim()
        
        if (text == "{}" || text == "[]") {
            return "" // Empty array should be treated as empty path
        }
        
        return text?.removeSurrounding("{", "}")?.trim()
    }
    
    private fun extractValueFromKotlinAnnotation(annotation: org.jetbrains.kotlin.psi.KtAnnotationEntry): String? {
        val valueArgument = annotation.valueArguments.firstOrNull()
        return valueArgument?.toString()?.removeSurrounding("\"")
    }
    
    private fun combinePaths(classPath: String, methodPath: String): String {
        val cleanClassPath = classPath.trim('/')
        val cleanMethodPath = methodPath.trim('/')
        
        return when {
            cleanClassPath.isEmpty() && cleanMethodPath.isEmpty() -> "/"
            cleanClassPath.isEmpty() -> "/$cleanMethodPath"
            cleanMethodPath.isEmpty() -> "/$cleanClassPath"
            else -> "/$cleanClassPath/$cleanMethodPath"
        }
    }
    
    private fun extractParametersFromJavaMethod(method: PsiMethod): List<EndpointParameter> {
        val parameters = mutableListOf<EndpointParameter>()
        
        method.parameterList.parameters.forEach { parameter ->
            val annotations = parameter.annotations
            
            // 提取 OpenAPI @Parameter 注解信息
            var paramDescription: String? = null
            var paramExample: String? = null
            var paramRequired: Boolean? = null
            
            val openApiParamAnnotation = annotations.find {
                it.qualifiedName == "io.swagger.v3.oas.annotations.Parameter"
            }
            if (openApiParamAnnotation != null) {
                paramDescription = extractAnnotationStringValue(openApiParamAnnotation, "description")
                paramExample = extractAnnotationStringValue(openApiParamAnnotation, "example")
                val requiredValue = openApiParamAnnotation.findAttributeValue("required")
                if (requiredValue != null) {
                    paramRequired = requiredValue.text == "true"
                }
            } else {
                // Try Swagger 2 @ApiParam
                val apiParamAnnotation = annotations.find {
                    it.qualifiedName == "io.swagger.annotations.ApiParam"
                }
                if (apiParamAnnotation != null) {
                    paramDescription = extractAnnotationStringValue(apiParamAnnotation, "value")
                    paramExample = extractAnnotationStringValue(apiParamAnnotation, "example")
                    val requiredValue = apiParamAnnotation.findAttributeValue("required")
                    if (requiredValue != null) {
                        paramRequired = requiredValue.text == "true"
                    }
                }
            }
            
            // Check for path parameters
            val pathVariableAnnotation = annotations.find { 
                it.qualifiedName?.endsWith("PathVariable") == true 
            }
            if (pathVariableAnnotation != null) {
                var paramName = extractValueFromAnnotation(pathVariableAnnotation)
                if (paramName.isNullOrBlank()) {
                    paramName = parameter.name
                }
                
                parameters.add(EndpointParameter(
                    name = paramName,
                    type = ParameterType.PATH,
                    required = paramRequired ?: true,
                    dataType = parameter.type.presentableText,
                    qualifiedType = parameter.type.canonicalText,
                    description = paramDescription,
                    example = paramExample
                ))
                return@forEach
            }
            
            // Check for query parameters
            val requestParamAnnotation = annotations.find { 
                it.qualifiedName?.endsWith("RequestParam") == true 
            }
            if (requestParamAnnotation != null) {
                var paramName = extractValueFromAnnotation(requestParamAnnotation)
                if (paramName.isNullOrBlank()) {
                    paramName = parameter.name
                }
                val required = paramRequired ?: extractRequiredFromAnnotation(requestParamAnnotation)
                parameters.add(EndpointParameter(
                    name = paramName,
                    type = ParameterType.QUERY,
                    required = required,
                    dataType = parameter.type.presentableText,
                    qualifiedType = parameter.type.canonicalText,
                    description = paramDescription,
                    example = paramExample
                ))
                return@forEach
            }
            
            // Check for header parameters
            val requestHeaderAnnotation = annotations.find { 
                it.qualifiedName?.endsWith("RequestHeader") == true 
            }
            if (requestHeaderAnnotation != null) {
                var paramName = extractValueFromAnnotation(requestHeaderAnnotation)
                if (paramName.isNullOrBlank()) {
                    paramName = parameter.name
                }
                val required = paramRequired ?: extractRequiredFromAnnotation(requestHeaderAnnotation)
                parameters.add(EndpointParameter(
                    name = paramName,
                    type = ParameterType.HEADER,
                    required = required,
                    dataType = parameter.type.presentableText,
                    qualifiedType = parameter.type.canonicalText,
                    description = paramDescription,
                    example = paramExample
                ))
                return@forEach
            }
            
            // Check for request body
            val requestBodyAnnotation = annotations.find { 
                it.qualifiedName?.endsWith("RequestBody") == true 
            }
            if (requestBodyAnnotation != null) {
                parameters.add(EndpointParameter(
                    name = parameter.name ?: "body",
                    type = ParameterType.BODY,
                    required = paramRequired ?: true,
                    dataType = parameter.type.presentableText,
                    qualifiedType = parameter.type.canonicalText,
                    description = paramDescription,
                    example = paramExample
                ))
                return@forEach
            }
            // Default to body if no annotation and complex type
            if (!isSimpleType(parameter.type)) {
                parameters.add(EndpointParameter(
                    name = parameter.name,
                    type = ParameterType.BODY,
                    required = true,
                    dataType = parameter.type.presentableText,
                    qualifiedType = parameter.type.canonicalText,
                    description = paramDescription,
                    example = paramExample
                ))
            }
        }
        
        return parameters
    }
    
    // Helper method to check if type is simple
    private fun isSimpleType(type: PsiType): Boolean {
        val name = type.presentableText.lowercase()
        return name in setOf("string", "int", "integer", "long", "boolean", "double", "float", "short", "byte", "char", "date", "localdate", "localdatetime", "bigdecimal") ||
               type is PsiPrimitiveType
    }
    
    private fun extractParametersFromKotlinMethod(function: KtFunction): List<EndpointParameter> {
        val parameters = mutableListOf<EndpointParameter>()
        
        function.valueParameters.forEach { parameter ->
            val annotations = parameter.annotationEntries
            
            // Check for path parameters
            val pathVariableAnnotation = annotations.find { 
                it.shortName?.asString() == "PathVariable" 
            }
            if (pathVariableAnnotation != null) {
                val paramName = extractValueFromKotlinAnnotation(pathVariableAnnotation) ?: parameter.name
                parameters.add(EndpointParameter(
                    name = paramName ?: "unknown",
                    type = ParameterType.PATH,
                    required = true,
                    dataType = parameter.typeReference?.text
                ))
            }
            
            // Check for query parameters
            val requestParamAnnotation = annotations.find { 
                it.shortName?.asString() == "RequestParam" 
            }
            if (requestParamAnnotation != null) {
                val paramName = extractValueFromKotlinAnnotation(requestParamAnnotation) ?: parameter.name
                val required = extractRequiredFromKotlinAnnotation(requestParamAnnotation)
                parameters.add(EndpointParameter(
                    name = paramName ?: "unknown",
                    type = ParameterType.QUERY,
                    required = required,
                    dataType = parameter.typeReference?.text
                ))
            }
            
            // Check for header parameters
            val requestHeaderAnnotation = annotations.find { 
                it.shortName?.asString() == "RequestHeader" 
            }
            if (requestHeaderAnnotation != null) {
                val paramName = extractValueFromKotlinAnnotation(requestHeaderAnnotation) ?: parameter.name
                val required = extractRequiredFromKotlinAnnotation(requestHeaderAnnotation)
                parameters.add(EndpointParameter(
                    name = paramName ?: "unknown",
                    type = ParameterType.HEADER,
                    required = required,
                    dataType = parameter.typeReference?.text
                ))
            }
            
            // Check for request body
            val requestBodyAnnotation = annotations.find { 
                it.shortName?.asString() == "RequestBody" 
            }
            if (requestBodyAnnotation != null) {
                parameters.add(EndpointParameter(
                    name = parameter.name ?: "body",
                    type = ParameterType.BODY,
                    required = true,
                    dataType = parameter.typeReference?.text
                ))
            }
        }
        
        return parameters
    }
    
    private fun extractRequiredFromAnnotation(annotation: PsiAnnotation): Boolean {
        val requiredAttribute = annotation.findAttributeValue("required")
        return requiredAttribute?.text != "false"
    }
    
    private fun extractRequiredFromKotlinAnnotation(annotation: org.jetbrains.kotlin.psi.KtAnnotationEntry): Boolean {
        val requiredArgument = annotation.valueArguments.find { 
            it.getArgumentName()?.asName?.asString() == "required" 
        }
        return requiredArgument?.toString() != "false"
    }
    
    private fun getLineNumber(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        return if (document != null) {
            document.getLineNumber(element.textOffset) + 1
        } else {
            0
        }
    }
    
    private fun setupFileChangeListener() {
        messageBusConnection = project.messageBus.connect()
        messageBusConnection?.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var shouldRefresh = false
                
                events.forEach { event ->
                    val file = event.file
                    if (file != null && (file.extension == "java" || file.extension == "kt")) {
                        shouldRefresh = true
                        // Remove cached endpoints for this file
                        cachedEndpoints.remove(file.path)
                    }
                }
                
                if (shouldRefresh) {
                    // Refresh endpoints (already runs in background thread)
                    refreshEndpoints()
                }
            }
        })
    }
    
    private fun notifyListeners(endpoints: List<DiscoveredEndpoint>) {
        listeners.forEach { it(endpoints) }
    }
    
    /**
     * Detect changes between old and new endpoint lists
     */
    private fun detectEndpointChanges(
        oldEndpoints: List<DiscoveredEndpoint>,
        newEndpoints: List<DiscoveredEndpoint>
    ): EndpointChangeNotification {
        val oldEndpointMap = oldEndpoints.associateBy { endpointKey(it) }
        val newEndpointMap = newEndpoints.associateBy { endpointKey(it) }
        
        val added = mutableListOf<DiscoveredEndpoint>()
        val modified = mutableListOf<DiscoveredEndpoint>()
        val removed = mutableListOf<DiscoveredEndpoint>()
        
        // Find added and modified endpoints
        newEndpointMap.forEach { (key, newEndpoint) ->
            val oldEndpoint = oldEndpointMap[key]
            if (oldEndpoint == null) {
                added.add(newEndpoint)
            } else if (!endpointsEqual(oldEndpoint, newEndpoint)) {
                modified.add(newEndpoint)
            }
        }
        
        // Find removed endpoints
        oldEndpointMap.forEach { (key, oldEndpoint) ->
            if (!newEndpointMap.containsKey(key)) {
                removed.add(oldEndpoint)
            }
        }
        
        return EndpointChangeNotification(
            addedEndpoints = added,
            modifiedEndpoints = modified,
            removedEndpoints = removed
        )
    }
    
    /**
     * Generate a unique key for an endpoint based on its identifying characteristics
     */
    private fun endpointKey(endpoint: DiscoveredEndpoint): String {
        return "${endpoint.className}::${endpoint.methodName}"
    }
    
    /**
     * Check if two endpoints are equal (comparing all relevant fields)
     */
    private fun endpointsEqual(e1: DiscoveredEndpoint, e2: DiscoveredEndpoint): Boolean {
        return e1.method == e2.method &&
               e1.path == e2.path &&
               e1.parameters == e2.parameters
    }
    
    /**
     * Notify all registered endpoint change listeners
     */
    private fun notifyEndpointChangeListeners(notification: EndpointChangeNotification) {
        endpointChangeListeners.forEach { listener ->
            try {
                listener.onEndpointsChanged(notification)
            } catch (e: Exception) {
                LoggingUtils.logWithContext(
                    LoggingUtils.LogLevel.ERROR,
                    "Error notifying endpoint change listener",
                    mapOf<String, Any>(
                        "listenerClass" to listener::class.simpleName.orEmpty(),
                        "error" to (e.message ?: "Unknown error")
                    ),
                    e
                )
            }
        }
    }
    
    /**
     * 从注解中提取字符串值
     */
    private fun extractAnnotationStringValue(annotation: PsiAnnotation, attributeName: String): String? {
        val value = annotation.findAttributeValue(attributeName)
        return value?.text?.removeSurrounding("\"")
    }
    
    /**
     * 从类中提取 @Tag 注解的名称
     */
    /**
     * Merge parameters from different sources.
     * Priority: Method Arguments < Implicit Params/Common Params (Enrichment)
     */
    private fun mergeParameters(
        methodParams: List<EndpointParameter>,
        implicitParams: List<EndpointParameter>,
        commonParams: List<EndpointParameter>
    ): List<EndpointParameter> {
        val merged = methodParams.toMutableList()
        val additionalParams = implicitParams + commonParams
        
        additionalParams.forEach { additional ->
            val existingIndex = merged.indexOfFirst { it.name == additional.name }
            if (existingIndex != -1) {
                // Enrich existing parameter
                val existing = merged[existingIndex]
                merged[existingIndex] = existing.copy(
                    description = existing.description ?: additional.description,
                    example = existing.example ?: additional.example,
                    required = if (existing.required) true else additional.required, // specific logic could be debated
                    dataType = if (existing.dataType == "Object" || existing.dataType == null) additional.dataType else existing.dataType
                )
            } else {
                // Add new parameter if it's a valid type (path/query/header/body)
                // Implicit params often define things not in signature (like headers)
                merged.add(additional)
            }
        }
        return merged
    }

    private fun extractImplicitParameters(method: PsiMethod): List<EndpointParameter> {
        val parameters = mutableListOf<EndpointParameter>()
        
        val implicitParamsAnnotation = method.annotations.find { 
            it.qualifiedName == "io.swagger.annotations.ApiImplicitParams" 
        } ?: return parameters

        val valueAttr = implicitParamsAnnotation.findAttributeValue("value")
        if (valueAttr is com.intellij.psi.PsiArrayInitializerMemberValue) {
            valueAttr.initializers.forEach { initializer ->
                if (initializer is PsiAnnotation) {
                     parameters.addNotNull(parseApiImplicitParam(initializer))
                }
            }
        } else if (valueAttr is PsiAnnotation) {
             parameters.addNotNull(parseApiImplicitParam(valueAttr))
        }

        return parameters
    }

    private fun parseApiImplicitParam(annotation: PsiAnnotation): EndpointParameter? {
        val name = extractAnnotationStringValue(annotation, "name") ?: return null
        val value = extractAnnotationStringValue(annotation, "value") // description
        val defaultValue = extractAnnotationStringValue(annotation, "defaultValue")
        val required = annotation.findAttributeValue("required")?.text == "true"
        val dataType = extractAnnotationStringValue(annotation, "dataType")
        val paramType = extractAnnotationStringValue(annotation, "paramType")
        val example = extractAnnotationStringValue(annotation, "example")

        val type = when (paramType?.lowercase()) {
            "path" -> ParameterType.PATH
            "query" -> ParameterType.QUERY
            "header" -> ParameterType.HEADER
            "body" -> ParameterType.BODY
            "form" -> ParameterType.BODY // Form data is body
            else -> return null // Ignore unknown types
        }

        return EndpointParameter(
            name = name,
            type = type,
            required = required,
            dataType = dataType ?: "String",
            description = value,
            example = example ?: defaultValue
        )
    }

    private fun extractCommonParameters(method: PsiMethod): List<EndpointParameter> {
        val parameters = mutableListOf<EndpointParameter>()
        // To be implemented for Swagger 3 @Parameters if needed, currently focusing on @ApiImplicitParams
        // Swagger 3 usually uses @Parameter inline or @Parameters wrapper
        
        val parametersAnnotation = method.annotations.find {
            it.qualifiedName == "io.swagger.v3.oas.annotations.Parameters"
        }
        
        if (parametersAnnotation != null) {
             val valueAttr = parametersAnnotation.findAttributeValue("value")
             if (valueAttr is com.intellij.psi.PsiArrayInitializerMemberValue) {
                valueAttr.initializers.forEach { initializer ->
                    if (initializer is PsiAnnotation) {
                         parameters.addNotNull(parseSwagger3Parameter(initializer))
                    }
                }
            }
        }
        
        // Also check for repeatable @Parameter annotation on method (if supported by libraries/compiler)
        // Usually @Parameters is the container
        
        return parameters
    }

    private fun parseSwagger3Parameter(annotation: PsiAnnotation): EndpointParameter? {
        val name = extractAnnotationStringValue(annotation, "name") ?: return null
        val description = extractAnnotationStringValue(annotation, "description")
        val example = extractAnnotationStringValue(annotation, "example")
        val required = annotation.findAttributeValue("required")?.text == "true"
        
        // In Swagger 3, `in` determines the type (query, path, header, cookie)
        val inType = extractAnnotationEnumName(annotation, "in")
        
        val type = when (inType?.lowercase()) {
            "path" -> ParameterType.PATH
            "query" -> ParameterType.QUERY
            "header" -> ParameterType.HEADER
            "cookie" -> ParameterType.HEADER // Treat cookie as header for now or ignore
            else -> return null // Default or unknown? If 'in' is missing, it's ambiguous.
        }

        return EndpointParameter(
            name = name,
            type = type,
            required = required,
            dataType = "String", // Hard to get type from annotation unless schema is parsed
            description = description,
            example = example
        )
    }
    
    private fun extractAnnotationEnumName(annotation: PsiAnnotation, attributeName: String): String? {
        val value = annotation.findAttributeValue(attributeName)
        // Value is usually a reference expression like ParameterIn.QUERY
        if (value is com.intellij.psi.PsiReferenceExpression) {
            return value.referenceName
        }
        return null
    }

    private fun <T> MutableList<T>.addNotNull(element: T?) {
        if (element != null) add(element)
    }

    private fun extractTagsFromClass(psiClass: PsiClass): List<String> {
        val tags = mutableListOf<String>()
        
        psiClass.annotations.forEach { annotation ->
            if (annotation.qualifiedName == "io.swagger.v3.oas.annotations.tags.Tag") {
                val tagName = extractAnnotationStringValue(annotation, "name")
                if (tagName != null) {
                    tags.add(tagName)
                }
            } else if (annotation.qualifiedName == "io.swagger.annotations.Api") {
                // Swagger 2 @Api(tags = {"..."}) or @Api(value = "...")
                val tagsValue = annotation.findAttributeValue("tags")
                if (tagsValue != null && tagsValue.text != "{}") {
                    // Handle array of tags or single tag
                    val text = tagsValue.text.removeSurrounding("{", "}").trim()
                    if (text.isNotBlank()) {
                        text.split(",").forEach { 
                            tags.add(it.trim().removeSurrounding("\"")) 
                        }
                    }
                }
                
                // Fallback to value if tags is empty
                if (tags.isEmpty()) {
                    val value = extractAnnotationStringValue(annotation, "value")
                    if (!value.isNullOrBlank()) {
                        tags.add(value)
                    }
                }
            }
        }
        
        return tags
    }
    
    fun dispose() {
        messageBusConnection?.disconnect()
        
        // Clear all caches on dispose
        cachedEndpoints.clear()
        fileTimestamps.clear()
        psiFileCache.clear()
        scannedFiles.clear()
    }
}