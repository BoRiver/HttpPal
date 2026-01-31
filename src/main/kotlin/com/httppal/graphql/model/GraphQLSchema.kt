package com.httppal.graphql.model

/**
 * Represents a GraphQL schema with types and operation types.
 *
 * @property types List of all types in the schema
 * @property queryType Name of the query root type
 * @property mutationType Optional name of the mutation root type
 * @property subscriptionType Optional name of the subscription root type
 */
data class GraphQLSchema(
    val types: List<GraphQLType>,
    val queryType: String,
    val mutationType: String? = null,
    val subscriptionType: String? = null
)

/**
 * Represents a GraphQL type definition.
 *
 * @property name The type name
 * @property kind The kind of type (SCALAR, OBJECT, etc.)
 * @property description Optional description of the type
 * @property fields Optional list of fields (for OBJECT and INTERFACE types)
 * @property inputFields Optional list of input fields (for INPUT_OBJECT types)
 * @property enumValues Optional list of enum values (for ENUM types)
 * @property ofType Optional wrapped type (for LIST and NON_NULL types)
 */
data class GraphQLType(
    val name: String,
    val kind: TypeKind,
    val description: String? = null,
    val fields: List<GraphQLField>? = null,
    val inputFields: List<GraphQLInputValue>? = null,
    val enumValues: List<GraphQLEnumValue>? = null,
    val ofType: GraphQLType? = null
)

/**
 * Represents a field in a GraphQL type.
 *
 * @property name The field name
 * @property description Optional field description
 * @property args List of arguments for this field
 * @property type The field's return type
 * @property isDeprecated Whether the field is deprecated
 * @property deprecationReason Optional deprecation reason
 */
data class GraphQLField(
    val name: String,
    val description: String? = null,
    val args: List<GraphQLInputValue> = emptyList(),
    val type: GraphQLType,
    val isDeprecated: Boolean = false,
    val deprecationReason: String? = null
)

/**
 * Represents an input value (argument or input field).
 *
 * @property name The input value name
 * @property description Optional description
 * @property type The input value type
 * @property defaultValue Optional default value
 */
data class GraphQLInputValue(
    val name: String,
    val description: String? = null,
    val type: GraphQLType,
    val defaultValue: String? = null
)

/**
 * Represents an enum value.
 *
 * @property name The enum value name
 * @property description Optional description
 * @property isDeprecated Whether the value is deprecated
 * @property deprecationReason Optional deprecation reason
 */
data class GraphQLEnumValue(
    val name: String,
    val description: String? = null,
    val isDeprecated: Boolean = false,
    val deprecationReason: String? = null
)
