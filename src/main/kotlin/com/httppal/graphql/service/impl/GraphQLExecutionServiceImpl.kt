package com.httppal.graphql.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.httppal.graphql.model.ErrorLocation
import com.httppal.graphql.model.GraphQLError
import com.httppal.graphql.model.GraphQLRequest
import com.httppal.graphql.model.GraphQLResponse
import com.httppal.graphql.service.GraphQLExecutionService
import com.httppal.model.HttpMethod
import com.httppal.model.RequestConfig
import com.httppal.service.RequestExecutionService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.time.Duration

/**
 * Implementation of GraphQL execution service.
 * Converts GraphQL requests to HTTP POST requests and parses responses.
 */
class GraphQLExecutionServiceImpl : GraphQLExecutionService {

    private val logger = Logger.getInstance(GraphQLExecutionServiceImpl::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    override suspend fun executeGraphQL(
        endpoint: String,
        graphqlRequest: GraphQLRequest,
        additionalHeaders: Map<String, String>
    ): GraphQLResponse {
        logger.info("Executing GraphQL request: endpoint=$endpoint, operation=${graphqlRequest.operationName}")

        try {
            // Build GraphQL request body
            val requestBody = buildRequestBody(graphqlRequest)
            logger.debug("GraphQL request body: $requestBody")

            // Build headers
            val headers = buildHeaders(additionalHeaders)

            // Create HTTP POST request configuration
            val httpConfig = RequestConfig(
                method = HttpMethod.POST,
                url = endpoint,
                headers = headers,
                body = requestBody,
                timeout = Duration.ofSeconds(60)
            )

            // Execute HTTP request
            val requestExecutionService = service<RequestExecutionService>()
            val httpResponse = requestExecutionService.executeRequest(httpConfig)

            logger.info("GraphQL HTTP response: status=${httpResponse.statusCode}, time=${httpResponse.responseTime}")

            // Parse GraphQL response
            return parseGraphQLResponse(httpResponse.body, httpResponse.statusCode)

        } catch (e: Exception) {
            logger.error("GraphQL execution failed: ${e.message}", e)
            return GraphQLResponse(
                data = null,
                errors = listOf(
                    GraphQLError(
                        message = "GraphQL execution failed: ${e.message}",
                        locations = null,
                        path = null,
                        extensions = mapOf("exception" to e.javaClass.simpleName)
                    )
                )
            )
        }
    }

    /**
     * Build the JSON request body for the GraphQL request.
     */
    private fun buildRequestBody(graphqlRequest: GraphQLRequest): String {
        val requestMap = mutableMapOf<String, Any?>(
            "query" to graphqlRequest.query
        )

        if (graphqlRequest.variables != null) {
            requestMap["variables"] = graphqlRequest.variables
        }

        if (graphqlRequest.operationName != null) {
            requestMap["operationName"] = graphqlRequest.operationName
        }

        return objectMapper.writeValueAsString(requestMap)
    }

    /**
     * Build HTTP headers for the GraphQL request.
     */
    private fun buildHeaders(additionalHeaders: Map<String, String>): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        headers.putAll(additionalHeaders)
        return headers
    }

    /**
     * Parse the HTTP response body as a GraphQL response.
     */
    private fun parseGraphQLResponse(responseBody: String, statusCode: Int): GraphQLResponse {
        try {
            if (responseBody.isBlank()) {
                return GraphQLResponse(
                    data = null,
                    errors = listOf(
                        GraphQLError(
                            message = "Empty response from server (HTTP $statusCode)",
                            locations = null,
                            path = null,
                            extensions = mapOf("statusCode" to statusCode)
                        )
                    )
                )
            }

            // Parse JSON response
            val jsonResponse: Map<String, Any?> = objectMapper.readValue(responseBody)

            // Extract data
            val data = jsonResponse["data"]

            // Extract errors
            val errors = parseErrors(jsonResponse["errors"])

            // Extract extensions
            @Suppress("UNCHECKED_CAST")
            val extensions = jsonResponse["extensions"] as? Map<String, Any>

            return GraphQLResponse(
                data = data,
                errors = errors,
                extensions = extensions
            )

        } catch (e: Exception) {
            logger.error("Failed to parse GraphQL response: ${e.message}", e)
            return GraphQLResponse(
                data = null,
                errors = listOf(
                    GraphQLError(
                        message = "Failed to parse GraphQL response: ${e.message}",
                        locations = null,
                        path = null,
                        extensions = mapOf(
                            "exception" to e.javaClass.simpleName,
                            "responseBody" to responseBody.take(500)
                        )
                    )
                )
            )
        }
    }

    /**
     * Parse the errors array from the GraphQL response.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseErrors(errorsObj: Any?): List<GraphQLError>? {
        if (errorsObj == null) return null

        try {
            val errorsList = errorsObj as? List<*> ?: return null

            return errorsList.mapNotNull { errorObj ->
                val errorMap = errorObj as? Map<String, Any?> ?: return@mapNotNull null

                val message = errorMap["message"] as? String ?: "Unknown error"
                val locations = parseLocations(errorMap["locations"])
                val path = parseErrorPath(errorMap["path"])
                val extensions = errorMap["extensions"] as? Map<String, Any>

                GraphQLError(
                    message = message,
                    locations = locations,
                    path = path,
                    extensions = extensions
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse GraphQL errors: ${e.message}")
            return null
        }
    }

    /**
     * Parse error locations from the GraphQL error object.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseLocations(locationsObj: Any?): List<ErrorLocation>? {
        if (locationsObj == null) return null

        try {
            val locationsList = locationsObj as? List<*> ?: return null

            return locationsList.mapNotNull { locObj ->
                val locMap = locObj as? Map<String, Any?> ?: return@mapNotNull null
                val line = (locMap["line"] as? Number)?.toInt() ?: return@mapNotNull null
                val column = (locMap["column"] as? Number)?.toInt() ?: return@mapNotNull null

                ErrorLocation(line = line, column = column)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse error locations: ${e.message}")
            return null
        }
    }

    /**
     * Parse error path from the GraphQL error object.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseErrorPath(pathObj: Any?): List<Any>? {
        if (pathObj == null) return null

        return try {
            pathObj as? List<Any>
        } catch (e: Exception) {
            logger.warn("Failed to parse error path: ${e.message}")
            null
        }
    }

    companion object {
        fun getInstance(): GraphQLExecutionService = service()
    }
}
