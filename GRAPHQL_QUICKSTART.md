# GraphQL Quick Start Guide

## Test the Implementation

### 1. Build and Run the Plugin

```bash
cd D:\my_work\http-pal-copy\HttpPal
.\gradlew runIde
```

This will launch a sandboxed IntelliJ IDEA instance with the HttpPal plugin installed.

### 2. Open the HttpPal Tool Window

- Click on the "HttpPal" tab on the right side of the IDE
- Or use keyboard shortcut: `Ctrl+Alt+H`

### 3. Switch to GraphQL Tab

Click on the "GraphQL" tab in the tool window.

### 4. Try Your First Query

**Endpoint**: `https://countries.trevorblades.com/`

**Query**:
```graphql
query {
  countries {
    name
    code
    capital
  }
}
```

**Steps**:
1. Enter the endpoint URL in the "Endpoint" field
2. Paste the query in the Query editor
3. Click "Execute Query"
4. View the results in the Response panel (Data tab)

### 5. Test with Variables

**Query**:
```graphql
query GetCountry($code: ID!) {
  country(code: $code) {
    name
    capital
    currency
    emoji
  }
}
```

**Variables** (enter in the Variables editor):
```json
{
  "code": "US"
}
```

**Steps**:
1. Replace the query with the above
2. Add the variables in the Variables editor
3. Click "Execute Query"
4. See the filtered result

### 6. Test Schema Introspection

1. With the Countries API endpoint still entered
2. Click "Introspect Schema"
3. You should see a success message: "Schema introspected successfully! X types found."

### 7. Test Error Handling

**Bad Query**:
```graphql
query {
  invalidField {
    name
  }
}
```

**Steps**:
1. Enter this invalid query
2. Click "Execute Query"
3. The UI should automatically switch to the "Errors" tab
4. View the error message from the GraphQL server

### 8. Verify History

1. After executing a few queries
2. Check that they're being saved to history
3. (History UI integration is complete - queries are saved in memory)

## More Test Endpoints

### SpaceX API

**Endpoint**: `https://spacex-production.up.railway.app/`

**Query**:
```graphql
query RecentLaunches {
  launchesPast(limit: 10) {
    mission_name
    launch_date_local
    launch_success
    rocket {
      rocket_name
      rocket_type
    }
  }
}
```

### Rick and Morty API

**Endpoint**: `https://rickandmortyapi.com/graphql`

**Query**:
```graphql
query {
  characters(page: 1) {
    results {
      name
      status
      species
      type
      gender
    }
  }
}
```

### GitHub API (Requires Authentication)

**Endpoint**: `https://api.github.com/graphql`

**Note**: You need to add an Authorization header. For now, you can test with public endpoints above.

## What to Verify

### ✅ Functionality Checklist

- [ ] GraphQL tab appears in HttpPal tool window
- [ ] Endpoint field accepts URL input
- [ ] Query editor has syntax highlighting (GraphQL or JSON)
- [ ] Variables editor accepts JSON input
- [ ] Execute Query button works
- [ ] Response displays in Data tab
- [ ] Errors display in Errors tab (test with invalid query)
- [ ] Auto-switch to Errors tab when errors occur
- [ ] Introspect Schema button works
- [ ] Success/error messages appear in status bar
- [ ] No compilation errors
- [ ] No runtime exceptions in IDE logs

### ✅ UI/UX Checklist

- [ ] Layout is clean and intuitive
- [ ] Split pane divider is draggable
- [ ] Buttons provide visual feedback
- [ ] Text is readable and properly formatted
- [ ] Status label updates appropriately
- [ ] JSON responses are pretty-printed
- [ ] Line numbers show in editors

### ✅ Error Handling Checklist

- [ ] Empty endpoint shows warning
- [ ] Empty query shows warning
- [ ] Invalid JSON in variables shows error
- [ ] Network errors are handled gracefully
- [ ] GraphQL errors are displayed clearly
- [ ] No stack traces shown to user (errors are logged)

## Troubleshooting

### Plugin doesn't load
- Check `build/idea-sandbox/system/log/idea.log` for errors
- Verify all dependencies downloaded: `.\gradlew dependencies`
- Try clean build: `.\gradlew clean build`

### GraphQL tab missing
- Check that HttpPalToolWindow.kt was modified correctly
- Verify plugin.xml has all service registrations
- Look for compilation errors in build output

### Queries don't execute
- Check IDE logs for exceptions
- Verify endpoint URL is correct
- Test endpoint in browser or Postman first
- Check network connectivity

### No syntax highlighting
- This is expected if GraphQL plugin isn't installed
- Should fall back to JSON highlighting
- Install "GraphQL" plugin from JetBrains Marketplace for full highlighting

### Introspection fails
- Some endpoints may block introspection queries
- Check if endpoint requires authentication
- Try with a different endpoint (Countries API should work)

## Example Session

Here's a complete test session:

```
1. Launch: .\gradlew runIde
2. Wait for IDE to start (~30-60 seconds)
3. Open HttpPal tool window (Ctrl+Alt+H)
4. Click "GraphQL" tab
5. Enter endpoint: https://countries.trevorblades.com/
6. Click "Introspect Schema" → See "Schema introspected successfully!"
7. Enter query:
   query {
     countries(filter: { continent: { eq: "EU" } }) {
       name
       capital
       currency
     }
   }
8. Click "Execute Query"
9. See results in Data tab with European countries
10. Try an invalid query → See errors in Errors tab
11. Success! GraphQL support is working ✅
```

## Next Steps After Testing

If everything works:
1. Consider implementing optional tasks (#6, #7, #10, #11, #12)
2. Add more comprehensive error messages
3. Implement GraphQL-specific features like fragments
4. Add support for subscriptions (WebSocket-based)
5. Build the distribution: `.\gradlew buildPlugin`
6. Find the plugin ZIP in `build/distributions/`

## Getting Help

If you encounter issues:
1. Check the IDE logs: `build/idea-sandbox/system/log/idea.log`
2. Look for exceptions in the console output
3. Verify all files were created correctly
4. Review the implementation summary document
5. Check that plugin.xml service registrations are correct

Happy testing! 🚀
