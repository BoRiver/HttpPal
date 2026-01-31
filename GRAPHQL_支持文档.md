# HttpPal GraphQL 支持

## 概述

HttpPal 现已集成完整的 GraphQL 支持，让您可以直接在 JetBrains IDE 中测试和探索 GraphQL API。

## 功能特性

### ✅ 已实现功能

1. **GraphQL 查询执行**
   - 发送查询、变更和订阅到 GraphQL 端点
   - 支持 JSON 格式的变量
   - 响应自动解析，数据和错误分开显示

2. **Schema Introspection（Schema 获取）**
   - 从端点获取 GraphQL schema
   - 查看 schema 类型、字段和描述
   - Schema 缓存机制提升性能

3. **Schema 浏览器**
   - 树形视图展示 schema 结构
   - 双击字段自动插入到查询编辑器
   - 支持 Query、Mutation、Subscription 类型浏览
   - 显示字段参数和返回类型

4. **端点自动发现**
   - 扫描 Spring GraphQL 注解（@QueryMapping、@MutationMapping）
   - 扫描 Netflix DGS 注解（@DgsQuery、@DgsMutation）
   - 自动识别项目中的 GraphQL 端点

5. **Mock 数据生成**
   - 基于 schema 自动生成示例查询
   - 智能生成测试数据（姓名、邮箱、电话等）
   - 支持复杂类型的嵌套数据生成

6. **自动补全**
   - Ctrl+Space 触发字段补全
   - 基于 introspected schema 提供建议
   - 支持顶层字段补全

7. **语法高亮**
   - 自动检测 GraphQL 插件以提供语法高亮
   - 优雅降级到 JSON 语法高亮

8. **请求历史**
   - 自动保存执行的 GraphQL 查询
   - 搜索和过滤历史记录
   - 一键恢复历史查询

9. **国际化支持**
   - 完整的中文界面
   - 英文界面（默认）
   - 其他语言可扩展

## 使用指南

### 基础查询执行

1. 打开 HttpPal 工具窗口（Ctrl+Alt+H）
2. 切换到"GraphQL"标签页
3. 输入 GraphQL 端点 URL
4. 在查询编辑器中编写查询：
   ```graphql
   query {
     countries {
       name
       code
       capital
     }
   }
   ```
5. 点击"执行查询"
6. 在响应面板查看结果

### 使用变量

1. 编写带参数的查询：
   ```graphql
   query GetCountry($code: ID!) {
     country(code: $code) {
       name
       capital
       currency
     }
   }
   ```

2. 在变量编辑器中添加 JSON 格式的变量：
   ```json
   {
     "code": "CN"
   }
   ```

3. 点击"执行查询"

### Schema Introspection

1. 输入 GraphQL 端点 URL
2. 点击"获取 Schema"
3. 等待 schema 获取完成
4. 查看 Schema 浏览器中的类型和字段
5. 双击字段可自动插入到查询编辑器

### 生成示例查询

1. 先获取 Schema（点击"获取 Schema"）
2. 点击"生成示例"按钮
3. 查询编辑器中将自动填充示例查询
4. 根据需要修改后执行

### 自动补全

1. 先获取 Schema
2. 在查询编辑器中按 Ctrl+Space
3. 选择建议的字段
4. 继续编写查询

## 测试用公开 GraphQL API

以下是一些可用于测试的公开 GraphQL API：

### 1. Countries API（国家信息）
- **端点**: `https://countries.trevorblades.com/`
- **无需认证**
- **示例查询**:
  ```graphql
  query {
    countries {
      name
      code
      capital
    }
  }
  ```

### 2. SpaceX API（SpaceX 数据）
- **端点**: `https://spacex-production.up.railway.app/`
- **无需认证**
- **示例查询**:
  ```graphql
  query {
    launchesPast(limit: 5) {
      mission_name
      launch_date_local
      rocket {
        rocket_name
      }
    }
  }
  ```

### 3. Rick and Morty API
- **端点**: `https://rickandmortyapi.com/graphql`
- **无需认证**
- **示例查询**:
  ```graphql
  query {
    characters(page: 1) {
      results {
        name
        status
        species
      }
    }
  }
  ```

### 4. GitHub GraphQL API
- **端点**: `https://api.github.com/graphql`
- **需要认证**（在环境中添加 Authorization header）
- **示例查询**:
  ```graphql
  query {
    viewer {
      login
      name
      bio
    }
  }
  ```

## 架构说明

### 数据模型
- `GraphQLRequest` - GraphQL 请求（查询、变量、操作名）
- `GraphQLResponse` - GraphQL 响应（数据、错误、扩展信息）
- `GraphQLSchema` - Schema 定义（类型、字段等）
- `GraphQLHistoryEntry` - 历史记录条目
- `GraphQLEndpoint` - 发现的端点信息

### 服务层
- `GraphQLExecutionService` - 执行 GraphQL 请求（通过 HTTP POST）
- `GraphQLSchemaService` - Schema introspection 和缓存
- `GraphQLHistoryService` - 历史记录管理
- `GraphQLDiscoveryService` - 端点自动发现

### UI 组件
- `GraphQLPanel` - 主面板（端点输入、编辑器、按钮）
- `GraphQLQueryEditor` - 查询编辑器（支持语法高亮）
- `GraphQLVariablesEditor` - 变量编辑器（JSON 格式）
- `GraphQLResponsePanel` - 响应显示（数据/错误分标签页）
- `GraphQLSchemaExplorer` - Schema 树形浏览器
- `GraphQLCompletionProvider` - 自动补全提供器

### 工具类
- `GraphQLMockGenerator` - Mock 数据生成器

## 配置

所有 GraphQL 服务已在 `plugin.xml` 中注册：
- `GraphQLExecutionService` - 应用级服务
- `GraphQLSchemaService` - 应用级服务（带缓存）
- `GraphQLHistoryService` - 应用级服务
- `GraphQLDiscoveryService` - 项目级服务

## 依赖项

在 `build.gradle.kts` 中添加了以下依赖：
```kotlin
implementation("com.graphql-java:graphql-java:21.5")
implementation("com.graphql-java:graphql-java-extended-scalars:21.1")
```

## 错误处理

- 网络错误会捕获并显示友好的错误消息
- 服务器返回的 GraphQL 错误在"错误"标签页中显示
- 变量中的无效 JSON 会立即反馈验证错误
- Schema introspection 失败时提供清晰的错误提示

## 性能优化

- Schema introspection 结果按端点缓存
- 历史记录限制为 1000 条以防止内存问题
- 所有 HTTP 请求在 IO 调度器上异步执行
- UI 更新在 Swing EDT 上执行以确保线程安全

## 键盘快捷键

- `Ctrl+Space` - 触发自动补全（在查询编辑器中）
- `Ctrl+Enter` - 执行查询（计划中）

## 常见问题

### Q: 为什么没有语法高亮？
A: HttpPal 会自动检测是否安装了 GraphQL 插件。如果没有安装，会降级使用 JSON 语法高亮。建议从 JetBrains Marketplace 安装"GraphQL"插件以获得完整的语法高亮支持。

### Q: Schema Introspection 失败怎么办？
A: 某些 GraphQL 端点可能禁用了 introspection。这种情况下，您仍然可以手动编写查询并执行。

### Q: 如何添加认证 Header？
A: 使用环境功能添加全局 Header，例如 `Authorization: Bearer YOUR_TOKEN`。

### Q: 支持 Subscription 吗？
A: 当前版本支持识别 Subscription 端点，但执行需要 WebSocket 支持（计划中的功能）。

### Q: 自动补全为什么不工作？
A: 确保先点击"获取 Schema"加载 schema。自动补全依赖于 introspected schema。

## 未来增强（可选）

以下功能可作为后续增强：
- 更智能的自动补全（支持嵌套字段、参数提示）
- GraphQL 变量类型验证
- Query 片段（Fragments）支持
- GraphQL Subscription 通过 WebSocket 执行
- 查询性能分析
- 批量查询执行
- GraphQL Playground 集成

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

与 HttpPal 主项目相同的许可证。
