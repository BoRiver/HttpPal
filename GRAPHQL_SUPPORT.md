# GraphQL Support in HttpPal

## Overview

HttpPal now includes comprehensive GraphQL support, allowing you to test and explore GraphQL APIs directly from your JetBrains IDE.

## Features

### ✅ Implemented

1. **GraphQL Query Execution**
   - Send queries, mutations, and subscriptions to GraphQL endpoints
   - Support for variables in JSON format
   - Automatic response parsing with separate data and errors display

2. **Schema Introspection**
   - Introspect GraphQL schemas from endpoints
   - View schema types, fields, and descriptions
   - Cached schemas for improved performance

3. **Syntax Highlighting**
   - Automatic detection of GraphQL plugin for enhanced highlighting
   - Graceful fallback to JSON syntax highlighting

4. **Request History**
   - Automatically save executed GraphQL queries
   - Search and filter history
   - Restore previous queries with one click

5. **UI Components**
   - Dedicated GraphQL tab in the tool window
   - Split-pane layout with query editor and response viewer
   - Separate tabs for response data and errors
   - Visual feedback for success/failure states

### 🔄 Future Enhancements (Optional)

- **Schema Explorer UI** (Task #6) - Tree view of schema types and fields
- **Auto-completion** (Task #10) - Field and argument completion based on schema
- **Mock Data Generation** (Task #11) - Generate sample queries and test data
- **Endpoint Discovery** (Task #7) - Discover GraphQL endpoints from Spring/DGS annotations

## Usage

### Basic Query Execution

1. Open HttpPal tool window (Ctrl+Alt+H)
2. Switch to the "GraphQL" tab
3. Enter your GraphQL endpoint URL
4. Write your query in the Query editor:
   ```graphql
   query {
     countries {
       name
       code
       capital
     }
   }
   ```
5. Click "Execute Query"
6. View results in the Response panel

### Using Variables

1. Write a parameterized query:
   ```graphql
   query GetCountry($code: ID!) {
     country(code: $code) {
       name
       capital
       currency
     }
   }
   ```

2. Add variables in JSON format:
   ```json
   {
     "code": "US"
   }
   ```

3. Click "Execute Query"

### Schema Introspection

1. Enter a GraphQL endpoint URL
2. Click "Introspect Schema"
3. The schema will be fetched and cached for future use
4. View the success message showing number of types discovered

## Testing with Public GraphQL APIs

Here are some public GraphQL APIs you can use for testing:

### 1. Countries API
- **Endpoint**: `https://countries.trevorblades.com/`
- **No authentication required**
- **Sample Query**:
  ```graphql
  query {
    countries {
      name
      code
      capital
    }
  }
  ```

### 2. SpaceX API
- **Endpoint**: `https://spacex-production.up.railway.app/`
- **No authentication required**
- **Sample Query**:
  ```graphql
  query {
    launches(limit: 5) {
      mission_name
      launch_date_utc
      rocket {
        rocket_name
      }
    }
  }
  ```

### 3. GitHub GraphQL API
- **Endpoint**: `https://api.github.com/graphql`
- **Requires authentication**
- **Add header**: `Authorization: Bearer YOUR_TOKEN`
- **Sample Query**:
  ```graphql
  query {
    viewer {
      login
      name
      bio
    }
  }
  ```

## Architecture

### Data Models
- `GraphQLRequest` - Represents a GraphQL request with query, variables, and operation name
- `GraphQLResponse` - Represents a response with data, errors, and extensions
- `GraphQLSchema` - Represents an introspected schema with types and fields
- `GraphQLHistoryEntry` - Represents a saved query in history

### Services
- `GraphQLExecutionService` - Executes GraphQL requests via HTTP POST
- `GraphQLSchemaService` - Handles schema introspection and caching
- `GraphQLHistoryService` - Manages query history

### UI Components
- `GraphQLPanel` - Main panel with endpoint input, query editor, and variables editor
- `GraphQLQueryEditor` - Code editor for GraphQL queries with syntax highlighting
- `GraphQLVariablesEditor` - JSON editor for query variables
- `GraphQLResponsePanel` - Tabbed display for response data and errors

## Configuration

All GraphQL services are registered in `plugin.xml`:
- `GraphQLExecutionService` - Application-level service
- `GraphQLSchemaService` - Application-level service with schema caching
- `GraphQLHistoryService` - Application-level service for query history

## Dependencies

The following dependencies were added to `build.gradle.kts`:
```kotlin
implementation("com.graphql-java:graphql-java:21.5")
implementation("com.graphql-java:graphql-java-extended-scalars:21.1")
```

## Error Handling

- Network errors are caught and displayed with user-friendly messages
- GraphQL errors from the server are displayed in the "Errors" tab
- Invalid JSON in variables shows immediate validation feedback
- Schema introspection failures provide clear error messages

## Performance Considerations

- Schema introspection results are cached per endpoint
- History is limited to 1000 entries to prevent memory issues
- All HTTP requests are executed asynchronously on IO dispatcher
- UI updates are performed on the Swing EDT to ensure thread safety

## Future Work

See tasks #6, #7, #10, #11, and #12 for planned enhancements:
- Visual schema explorer with tree view
- Intelligent auto-completion based on introspected schemas
- Generate mock queries from schema definitions
- Discover GraphQL endpoints from Spring GraphQL / Netflix DGS annotations
- Comprehensive unit and integration tests
