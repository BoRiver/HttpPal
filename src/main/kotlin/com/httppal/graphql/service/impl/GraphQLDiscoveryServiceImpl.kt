package com.httppal.graphql.service.impl

import com.httppal.graphql.model.GraphQLEndpoint
import com.httppal.graphql.model.GraphQLOperationType
import com.httppal.graphql.service.GraphQLDiscoveryService
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of GraphQL discovery service.
 * Scans for Spring GraphQL and Netflix DGS annotations.
 */
@Service(Service.Level.PROJECT)
class GraphQLDiscoveryServiceImpl(private val project: Project) : GraphQLDiscoveryService {

    private val logger = Logger.getInstance(GraphQLDiscoveryServiceImpl::class.java)
    private val listeners = mutableListOf<(List<GraphQLEndpoint>) -> Unit>()
    private val cachedEndpoints = ConcurrentHashMap<String, List<GraphQLEndpoint>>()

    // Spring GraphQL annotations
    private val springGraphQLAnnotations = setOf(
        "QueryMapping",
        "MutationMapping",
        "SubscriptionMapping",
        "SchemaMapping"
    )

    // Netflix DGS annotations
    private val dgsAnnotations = setOf(
        "DgsQuery",
        "DgsMutation",
        "DgsSubscription",
        "DgsData"
    )

    override fun discoverGraphQLEndpoints(): List<GraphQLEndpoint> {
        logger.info("Starting GraphQL endpoint discovery")

        val endpoints = mutableListOf<GraphQLEndpoint>()

        try {
            // Scan for Spring GraphQL endpoints
            endpoints.addAll(discoverSpringGraphQLEndpoints())

            // Scan for Netflix DGS endpoints
            endpoints.addAll(discoverDGSEndpoints())

            logger.info("Discovered ${endpoints.size} GraphQL endpoints")

        } catch (e: Exception) {
            logger.error("Error during GraphQL endpoint discovery", e)
        }

        // Notify listeners
        notifyListeners(endpoints)

        return endpoints
    }

    /**
     * Discover Spring GraphQL endpoints.
     */
    private fun discoverSpringGraphQLEndpoints(): List<GraphQLEndpoint> {
        val endpoints = mutableListOf<GraphQLEndpoint>()

        springGraphQLAnnotations.forEach { annotationName ->
            val searchHelper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            searchHelper.processAllFilesWithWord(
                annotationName,
                scope,
                { file ->
                    scanFileForSpringGraphQL(file, annotationName, endpoints)
                    true
                },
                true
            )
        }

        return endpoints
    }

    /**
     * Scan a file for Spring GraphQL annotations.
     */
    private fun scanFileForSpringGraphQL(
        file: PsiFile,
        annotationName: String,
        endpoints: MutableList<GraphQLEndpoint>
    ) {
        try {
            // Java files
            if (file is PsiJavaFile) {
                PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).forEach { method ->
                    val annotation = method.getAnnotation("org.springframework.graphql.data.method.annotation.$annotationName")
                        ?: method.getAnnotation(annotationName)

                    if (annotation != null) {
                        val operationType = when (annotationName) {
                            "QueryMapping" -> GraphQLOperationType.QUERY
                            "MutationMapping" -> GraphQLOperationType.MUTATION
                            "SubscriptionMapping" -> GraphQLOperationType.SUBSCRIPTION
                            else -> GraphQLOperationType.QUERY
                        }

                        val operationName = extractOperationName(annotation, method.name)

                        val endpoint = GraphQLEndpoint(
                            url = "/graphql", // Default GraphQL endpoint
                            operationType = operationType,
                            operationName = operationName,
                            description = extractDocComment(method),
                            psiElement = method,
                            sourceFile = file.virtualFile?.path
                        )

                        endpoints.add(endpoint)
                    }
                }
            }

            // Kotlin files
            if (file is org.jetbrains.kotlin.psi.KtFile) {
                PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).forEach { function ->
                    val annotation = function.annotationEntries.find { it.shortName?.asString() == annotationName }

                    if (annotation != null) {
                        val operationType = when (annotationName) {
                            "QueryMapping" -> GraphQLOperationType.QUERY
                            "MutationMapping" -> GraphQLOperationType.MUTATION
                            "SubscriptionMapping" -> GraphQLOperationType.SUBSCRIPTION
                            else -> GraphQLOperationType.QUERY
                        }

                        val operationName = extractOperationNameKotlin(annotation, function.name ?: "")

                        val endpoint = GraphQLEndpoint(
                            url = "/graphql",
                            operationType = operationType,
                            operationName = operationName,
                            description = extractDocCommentKotlin(function),
                            psiElement = function,
                            sourceFile = file.virtualFile?.path
                        )

                        endpoints.add(endpoint)
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("Error scanning file ${file.name} for Spring GraphQL annotations", e)
        }
    }

    /**
     * Discover Netflix DGS endpoints.
     */
    private fun discoverDGSEndpoints(): List<GraphQLEndpoint> {
        val endpoints = mutableListOf<GraphQLEndpoint>()

        dgsAnnotations.forEach { annotationName ->
            val searchHelper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            searchHelper.processAllFilesWithWord(
                annotationName,
                scope,
                { file ->
                    scanFileForDGS(file, annotationName, endpoints)
                    true
                },
                true
            )
        }

        return endpoints
    }

    /**
     * Scan a file for Netflix DGS annotations.
     */
    private fun scanFileForDGS(
        file: PsiFile,
        annotationName: String,
        endpoints: MutableList<GraphQLEndpoint>
    ) {
        try {
            // Java files
            if (file is PsiJavaFile) {
                PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).forEach { method ->
                    val annotation = method.getAnnotation("com.netflix.graphql.dgs.$annotationName")
                        ?: method.getAnnotation(annotationName)

                    if (annotation != null) {
                        val operationType = when (annotationName) {
                            "DgsQuery" -> GraphQLOperationType.QUERY
                            "DgsMutation" -> GraphQLOperationType.MUTATION
                            "DgsSubscription" -> GraphQLOperationType.SUBSCRIPTION
                            else -> GraphQLOperationType.QUERY
                        }

                        val operationName = extractDGSFieldName(annotation, method.name)

                        val endpoint = GraphQLEndpoint(
                            url = "/graphql",
                            operationType = operationType,
                            operationName = operationName,
                            description = extractDocComment(method),
                            psiElement = method,
                            sourceFile = file.virtualFile?.path
                        )

                        endpoints.add(endpoint)
                    }
                }
            }

            // Kotlin files
            if (file is org.jetbrains.kotlin.psi.KtFile) {
                PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).forEach { function ->
                    val annotation = function.annotationEntries.find { it.shortName?.asString() == annotationName }

                    if (annotation != null) {
                        val operationType = when (annotationName) {
                            "DgsQuery" -> GraphQLOperationType.QUERY
                            "DgsMutation" -> GraphQLOperationType.MUTATION
                            "DgsSubscription" -> GraphQLOperationType.SUBSCRIPTION
                            else -> GraphQLOperationType.QUERY
                        }

                        val operationName = extractDGSFieldNameKotlin(annotation, function.name ?: "")

                        val endpoint = GraphQLEndpoint(
                            url = "/graphql",
                            operationType = operationType,
                            operationName = operationName,
                            description = extractDocCommentKotlin(function),
                            psiElement = function,
                            sourceFile = file.virtualFile?.path
                        )

                        endpoints.add(endpoint)
                    }
                }
            }

        } catch (e: Exception) {
            logger.warn("Error scanning file ${file.name} for DGS annotations", e)
        }
    }

    /**
     * Extract operation name from Spring annotation or use method name.
     */
    private fun extractOperationName(annotation: PsiAnnotation, methodName: String): String {
        val value = annotation.findAttributeValue("value")?.text?.removeSurrounding("\"")
        val name = annotation.findAttributeValue("name")?.text?.removeSurrounding("\"")
        return value ?: name ?: methodName
    }

    /**
     * Extract operation name from Kotlin Spring annotation.
     */
    private fun extractOperationNameKotlin(annotation: KtAnnotationEntry, methodName: String): String {
        val valueArgs = annotation.valueArguments
        if (valueArgs.isNotEmpty()) {
            val firstArg = valueArgs[0]
            return firstArg.getArgumentExpression()?.text?.removeSurrounding("\"") ?: methodName
        }
        return methodName
    }

    /**
     * Extract field name from DGS annotation.
     */
    private fun extractDGSFieldName(annotation: PsiAnnotation, methodName: String): String {
        val field = annotation.findAttributeValue("field")?.text?.removeSurrounding("\"")
        return field ?: methodName
    }

    /**
     * Extract field name from Kotlin DGS annotation.
     */
    private fun extractDGSFieldNameKotlin(annotation: KtAnnotationEntry, methodName: String): String {
        val valueArgs = annotation.valueArguments
        for (arg in valueArgs) {
            val argName = arg.getArgumentName()?.asName?.asString()
            if (argName == "field") {
                return arg.getArgumentExpression()?.text?.removeSurrounding("\"") ?: methodName
            }
        }
        return methodName
    }

    /**
     * Extract JavaDoc comment.
     */
    private fun extractDocComment(method: PsiMethod): String? {
        val docComment = method.docComment
        return docComment?.descriptionElements?.joinToString(" ") { it.text.trim() }
    }

    /**
     * Extract KDoc comment.
     */
    private fun extractDocCommentKotlin(function: KtNamedFunction): String? {
        val docComment = function.docComment
        return docComment?.text
    }

    override fun refreshEndpoints() {
        logger.info("Refreshing GraphQL endpoints")
        cachedEndpoints.clear()
        discoverGraphQLEndpoints()
    }

    override fun addEndpointChangeListener(listener: (List<GraphQLEndpoint>) -> Unit) {
        listeners.add(listener)
    }

    override fun removeEndpointChangeListener(listener: (List<GraphQLEndpoint>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(endpoints: List<GraphQLEndpoint>) {
        listeners.forEach { it(endpoints) }
    }
}
