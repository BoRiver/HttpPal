package com.httppal.graphql.service.impl

import com.httppal.graphql.model.*
import com.httppal.graphql.service.GraphQLExecutionService
import com.httppal.graphql.service.GraphQLSchemaService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of GraphQL schema service with introspection and caching.
 */
class GraphQLSchemaServiceImpl : GraphQLSchemaService {

    private val logger = Logger.getInstance(GraphQLSchemaServiceImpl::class.java)
    private val schemaCache = ConcurrentHashMap<String, GraphQLSchema>()

    override suspend fun introspectSchema(
        endpoint: String,
        headers: Map<String, String>
    ): GraphQLSchema? {
        logger.info("Starting schema introspection for endpoint: $endpoint")

        try {
            val executionService = service<GraphQLExecutionService>()

            // Build introspection query
            val introspectionQuery = buildIntrospectionQuery()

            // Execute introspection request
            val graphqlRequest = GraphQLRequest(
                query = introspectionQuery,
                variables = null,
                operationName = "IntrospectionQuery"
            )

            val response = executionService.executeGraphQL(endpoint, graphqlRequest, headers)

            if (response.hasErrors()) {
                logger.warn("Introspection query returned errors: ${response.errors}")
                return null
            }

            if (response.data == null) {
                logger.warn("Introspection query returned null data")
                return null
            }

            // Parse schema from response
            val schema = parseSchemaFromIntrospection(response.data)

            if (schema != null) {
                // Cache the schema
                schemaCache[endpoint] = schema
                logger.info("Successfully introspected and cached schema for $endpoint")
            }

            return schema

        } catch (e: Exception) {
            logger.error("Schema introspection failed: ${e.message}", e)
            return null
        }
    }

    override fun getCachedSchema(endpoint: String): GraphQLSchema? {
        return schemaCache[endpoint]
    }

    override fun clearCache(endpoint: String) {
        schemaCache.remove(endpoint)
        logger.info("Cleared schema cache for endpoint: $endpoint")
    }

    override fun clearAllCaches() {
        schemaCache.clear()
        logger.info("Cleared all schema caches")
    }

    /**
     * Build the standard GraphQL introspection query.
     */
    private fun buildIntrospectionQuery(): String {
        return """
            query IntrospectionQuery {
              __schema {
                queryType { name }
                mutationType { name }
                subscriptionType { name }
                types {
                  kind
                  name
                  description
                  fields(includeDeprecated: true) {
                    name
                    description
                    args {
                      name
                      description
                      type {
                        ...TypeRef
                      }
                      defaultValue
                    }
                    type {
                      ...TypeRef
                    }
                    isDeprecated
                    deprecationReason
                  }
                  inputFields {
                    name
                    description
                    type {
                      ...TypeRef
                    }
                    defaultValue
                  }
                  enumValues(includeDeprecated: true) {
                    name
                    description
                    isDeprecated
                    deprecationReason
                  }
                }
              }
            }

            fragment TypeRef on __Type {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                  ofType {
                    kind
                    name
                    ofType {
                      kind
                      name
                      ofType {
                        kind
                        name
                        ofType {
                          kind
                          name
                          ofType {
                            kind
                            name
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }

    /**
     * Parse GraphQL schema from introspection response data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSchemaFromIntrospection(data: Any): GraphQLSchema? {
        try {
            val dataMap = data as? Map<String, Any?> ?: return null
            val schemaMap = dataMap["__schema"] as? Map<String, Any?> ?: return null

            // Parse root types
            val queryTypeMap = schemaMap["queryType"] as? Map<String, Any?>
            val queryType = queryTypeMap?.get("name") as? String ?: "Query"

            val mutationTypeMap = schemaMap["mutationType"] as? Map<String, Any?>
            val mutationType = mutationTypeMap?.get("name") as? String

            val subscriptionTypeMap = schemaMap["subscriptionType"] as? Map<String, Any?>
            val subscriptionType = subscriptionTypeMap?.get("name") as? String

            // Parse types
            val typesList = schemaMap["types"] as? List<*> ?: emptyList<Any>()
            val types = typesList.mapNotNull { parseType(it) }

            return GraphQLSchema(
                types = types,
                queryType = queryType,
                mutationType = mutationType,
                subscriptionType = subscriptionType
            )

        } catch (e: Exception) {
            logger.error("Failed to parse introspection data: ${e.message}", e)
            return null
        }
    }

    /**
     * Parse a GraphQL type from introspection data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseType(typeObj: Any?): GraphQLType? {
        if (typeObj == null) return null

        try {
            val typeMap = typeObj as? Map<String, Any?> ?: return null

            val name = typeMap["name"] as? String ?: return null
            val kindStr = typeMap["kind"] as? String ?: return null
            val kind = TypeKind.valueOf(kindStr)
            val description = typeMap["description"] as? String

            // Parse fields
            val fieldsList = typeMap["fields"] as? List<*>
            val fields = fieldsList?.mapNotNull { parseField(it) }

            // Parse input fields
            val inputFieldsList = typeMap["inputFields"] as? List<*>
            val inputFields = inputFieldsList?.mapNotNull { parseInputValue(it) }

            // Parse enum values
            val enumValuesList = typeMap["enumValues"] as? List<*>
            val enumValues = enumValuesList?.mapNotNull { parseEnumValue(it) }

            // Parse ofType (for wrapper types like LIST and NON_NULL)
            val ofType = parseType(typeMap["ofType"])

            return GraphQLType(
                name = name,
                kind = kind,
                description = description,
                fields = fields,
                inputFields = inputFields,
                enumValues = enumValues,
                ofType = ofType
            )

        } catch (e: Exception) {
            logger.warn("Failed to parse type: ${e.message}")
            return null
        }
    }

    /**
     * Parse a GraphQL field from introspection data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseField(fieldObj: Any?): GraphQLField? {
        if (fieldObj == null) return null

        try {
            val fieldMap = fieldObj as? Map<String, Any?> ?: return null

            val name = fieldMap["name"] as? String ?: return null
            val description = fieldMap["description"] as? String
            val typeObj = fieldMap["type"] ?: return null
            val type = parseType(typeObj) ?: return null
            val isDeprecated = fieldMap["isDeprecated"] as? Boolean ?: false
            val deprecationReason = fieldMap["deprecationReason"] as? String

            // Parse arguments
            val argsList = fieldMap["args"] as? List<*> ?: emptyList<Any>()
            val args = argsList.mapNotNull { parseInputValue(it) }

            return GraphQLField(
                name = name,
                description = description,
                args = args,
                type = type,
                isDeprecated = isDeprecated,
                deprecationReason = deprecationReason
            )

        } catch (e: Exception) {
            logger.warn("Failed to parse field: ${e.message}")
            return null
        }
    }

    /**
     * Parse a GraphQL input value (argument or input field) from introspection data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseInputValue(inputObj: Any?): GraphQLInputValue? {
        if (inputObj == null) return null

        try {
            val inputMap = inputObj as? Map<String, Any?> ?: return null

            val name = inputMap["name"] as? String ?: return null
            val description = inputMap["description"] as? String
            val typeObj = inputMap["type"] ?: return null
            val type = parseType(typeObj) ?: return null
            val defaultValue = inputMap["defaultValue"] as? String

            return GraphQLInputValue(
                name = name,
                description = description,
                type = type,
                defaultValue = defaultValue
            )

        } catch (e: Exception) {
            logger.warn("Failed to parse input value: ${e.message}")
            return null
        }
    }

    /**
     * Parse a GraphQL enum value from introspection data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEnumValue(enumObj: Any?): GraphQLEnumValue? {
        if (enumObj == null) return null

        try {
            val enumMap = enumObj as? Map<String, Any?> ?: return null

            val name = enumMap["name"] as? String ?: return null
            val description = enumMap["description"] as? String
            val isDeprecated = enumMap["isDeprecated"] as? Boolean ?: false
            val deprecationReason = enumMap["deprecationReason"] as? String

            return GraphQLEnumValue(
                name = name,
                description = description,
                isDeprecated = isDeprecated,
                deprecationReason = deprecationReason
            )

        } catch (e: Exception) {
            logger.warn("Failed to parse enum value: ${e.message}")
            return null
        }
    }

    companion object {
        fun getInstance(): GraphQLSchemaService = service()
    }
}
