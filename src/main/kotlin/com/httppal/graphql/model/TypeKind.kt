package com.httppal.graphql.model

/**
 * Enum representing the different kinds of GraphQL types.
 */
enum class TypeKind {
    SCALAR,
    OBJECT,
    INTERFACE,
    UNION,
    ENUM,
    INPUT_OBJECT,
    LIST,
    NON_NULL
}
