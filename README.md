# HttpPal - HTTP/WebSocket Client Plugin for JetBrains IDE

[English](#readme) | [中文](README_zh_CN.md)

---

## Overview

HttpPal is a comprehensive HTTP/WebSocket client plugin for JetBrains IDEs (primarily IntelliJ IDEA) that provides developers with integrated API testing capabilities directly within their development environment.

## Key Features

#### 🔍 **Automatic API Discovery**
- Automatically scans Java projects to discover REST API endpoints
- Supports Spring MVC annotations (@RequestMapping, @GetMapping, @PostMapping, etc.)
- Supports JAX-RS annotations (@GET, @POST, @PUT, @DELETE, @PATCH)
- Real-time endpoint discovery with source code changes

#### 🛠️ **Manual Endpoint Management**
- Add and edit API endpoints manually
- Support for external APIs not discoverable from source code
- Persistent endpoint configuration storage
- Comprehensive endpoint validation

#### 🖥️ **Integrated User Interface**
- Native ToolWindow integration within JetBrains IDEs
- Intuitive request configuration interface
- Support for all HTTP methods (GET, POST, PUT, DELETE, PATCH)
- Real-time response display with syntax highlighting

#### 🔌 **WebSocket Support**
- Full WebSocket client functionality
- Real-time message sending and receiving
- Connection status monitoring
- Message history preservation

#### 📋 **Advanced Header Management**
- Individual request header configuration
- Global headers applied to all requests
- Header override capabilities
- Secure storage for sensitive headers

#### 📊 **Response Analysis**
- Complete response body display with formatting
- Response headers visualization
- Response time measurement
- JSON/XML syntax highlighting

#### ⚡ **Concurrent Execution**
- Parallel HTTP request execution
- Configurable thread count and iteration parameters
- Aggregated performance statistics
- Load testing capabilities

#### 📚 **History & Favorites**
- Automatic request history tracking
- Favorite requests management
- Quick access to frequently used endpoints
- Persistent storage across IDE sessions

#### 🌍 **Environment Management** *(Optional)*
- Multiple environment support (dev, staging, production)
- Environment-specific base URLs and global headers
- Easy environment switching
- Project-level environment configuration

#### 📤 **JMeter Export Integration**
- Export single HTTP requests as Apache JMeter .jmx files
- Export concurrent test scenarios with proper thread group configuration
- Multi-request export with structured test plan organization
- Environment integration for seamless JMeter compatibility
- Direct integration with JMeter ecosystem for advanced load testing

## Technical Stack

- **Language**: Kotlin (with Java compatibility)
- **Build Tool**: Gradle with `gradle-intellij-plugin`
- **HTTP Client**: Java 11+ HttpClient or OkHttp
- **Concurrency**: `java.util.concurrent` package
- **UI Framework**: IntelliJ Platform UI DSL
- **Code Analysis**: IntelliJ PSI (Program Structure Interface)

## Architecture Components

1. **Core Engine**: HTTP/WebSocket communication handling
2. **Discovery Service**: Automatic API endpoint detection
3. **UI Layer**: ToolWindow and form components
4. **Storage Layer**: Settings and history persistence
5. **Environment Manager**: Multi-environment configuration
6. **Concurrent Executor**: Parallel request execution

## Security Considerations

- Secure storage for sensitive header information
- Encrypted persistence for authentication tokens
- Project-level security isolation
- Safe handling of credentials in multi-user environments

## Installation & Usage

1. Open your JetBrains IDE (IntelliJ IDEA, WebStorm, PyCharm, etc.)
2. Go to **File** → **Settings** → **Plugins**
3. Search for "HttpPal" in the marketplace
4. Click **Install** and restart your IDE

## Quick Start

### 1. Open HttpPal Tool Window
- Use **Tools** → **HttpPal** → **Open HttpPal**
- Or press `Ctrl+Alt+H` (Windows/Linux) / `Cmd+Alt+H` (Mac)

### 2. Discover Endpoints
- HttpPal automatically scans your project to find REST endpoints
- Click **Refresh** to manually update the endpoint list
- Double-click any endpoint to load it into the request form

### 3. Configure Request
- Select HTTP method, enter URL, add headers
- Add request body for POST/PUT/PATCH requests
- Configure timeout and redirect settings

### 4. Send Request
- Click **Send Request** to execute
- View formatted response including headers and timing
- Request is automatically added to history

### 5. Export to JMeter
- Click **Export to JMeter** to open the export dialog
- Select single request, multiple requests, or concurrent scenario
- Configure thread count and iterations for load testing
- Save as .jmx file for use in Apache JMeter

## Configuration

### Environment Settings
1. Go to **HttpPal** → **Manage Environments**
2. Create environments for different deployment targets
3. Set base URLs and global headers for each environment
4. Switch between environments using the dropdown menu

### Global Settings
1. Go to **File** → **Settings** → **Tools** → **HttpPal**
2. Configure default timeout, thread count, and other preferences
3. Set global headers applied to all requests
4. Configure history retention and favorites management

## Keyboard Shortcuts

| Action | Windows/Linux | Mac |
|--------|---------------|-----|
| Open HttpPal | `Ctrl+Alt+H` | `Cmd+Alt+H` |
| Send Request | `Ctrl+Alt+S` | `Cmd+Alt+S` |
| Refresh Endpoints | `Ctrl+Alt+R` | `Cmd+Alt+R` |
| Open WebSocket | `Ctrl+Alt+W` | `Cmd+Alt+W` |
| View History | `Ctrl+Alt+Y` | `Cmd+Alt+Y` |
| Manage Environments | `Ctrl+Alt+E` | `Cmd+Alt+E` |
| Export to JMeter | `Ctrl+Alt+J` | `Cmd+Alt+J` |
| Quick Test Endpoint | `Ctrl+Shift+T` | `Cmd+Shift+T` |

## Supported Frameworks

### Spring Framework
- Spring MVC controllers
- Spring Boot REST controllers
- Spring WebFlux (reactive endpoints)
- Custom request mappings

### JAX-RS
- Jersey implementation
- RESTEasy implementation
- Apache CXF implementation
- Custom JAX-RS providers

## Internationalization Support

The HttpPal plugin supports multiple languages:
- **English**
- **Simplified Chinese**
- **Traditional Chinese**
- **Japanese**

The plugin automatically detects your IDE language settings and displays the corresponding interface language.

## Contributing

We welcome contributions! Please see our contributing guidelines for more information.

## License

*(License information to be determined)*

## Support

- **Documentation**: [https://httppal.com/docs](https://httppal.com/docs)
- **Issue Tracker**: [https://github.com/httppal/httppal-plugin/issues](https://github.com/httppal/httppal-plugin/issues)
- **Email**: support@httppal.com