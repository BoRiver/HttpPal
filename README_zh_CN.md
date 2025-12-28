# HttpPal - JetBrains IDE 的 HTTP/WebSocket 客户端插件

[English](README.md) | [中文](#readme_zh_cn)

HttpPal 是一个为 JetBrains IDE 提供集成 API 测试功能的综合 HTTP/WebSocket 客户端插件，让开发者可以直接在开发环境中进行 API 测试。

## 概述

HttpPal 是一个为 JetBrains IDE 提供集成 API 测试功能的综合 HTTP/WebSocket 客户端插件，让开发者可以直接在开发环境中进行 API 测试。

## 功能特性

### 🔍 自动端点发现
- **Spring MVC 支持**：自动发现 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping` 和 `@PatchMapping` 注解的 REST 端点
- **JAX-RS 支持**：检测 `@GET`、`@POST`、`@PUT`、`@DELETE` 和 `@PATCH` 注解的端点
- **实时更新**：源代码更改时自动刷新端点列表
- **有序显示**：按控制器/资源类分组显示端点，便于组织管理

### 🌐 HTTP 客户端
- **完整的 HTTP 方法支持**：GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS
- **请求配置**：完整的请求设置，包括请求头、请求体、查询参数和路径参数
- **响应显示**：格式化的响应体，支持 JSON/XML 语法高亮
- **请求验证**：实时验证，提供清晰的错误消息
- **超时配置**：可配置的请求超时和重定向处理

### 🔌 WebSocket 支持
- **连接管理**：建立和管理 WebSocket 连接
- **实时消息传递**：发送和接收带时间戳的消息
- **连接状态**：连接状态的可视化指示器
- **消息历史**：每个连接的持久化消息历史

### 🌍 环境管理
- **多环境支持**：开发、测试、生产环境
- **基础 URL 配置**：环境特定的基础 URL
- **全局请求头**：应用于所有请求的环境级请求头
- **快速切换**：带有可视化反馈的快速环境切换

### 📚 请求管理
- **请求历史**：自动跟踪所有执行的请求和响应
- **收藏夹**：保存常用请求以便快速访问
- **搜索和过滤**：在历史记录和收藏夹中快速查找请求
- **导出/导入**：备份和恢复请求集合

### ⚡ 并发测试
- **负载测试**：使用可配置线程池并发执行请求
- **性能指标**：响应时间分析、成功率和错误跟踪
- **进度监控**：实时进度指示器和取消支持
- **结果分析**：详细的统计信息和性能图表

### 🎯 JMeter 集成
- **单个请求导出**：将单个请求导出为 JMeter .jmx 格式
- **并发场景**：导出带有线程组配置的负载测试场景
- **多请求导出**：将多个请求组织成结构化测试计划
- **环境集成**：在导出的测试计划中包含环境设置
- **有效的 JMX 文件**：生成的文件可直接在 Apache JMeter 中执行

### 🎨 用户界面
- **集成工具窗口**：带有选项卡界面的专用工具窗口
- **上下文菜单**：代码编辑器和端点树中的右键操作
- **键盘快捷键**：常用操作的快速访问
- **响应式设计**：在操作期间保持 IDE 响应性
- **错误处理**：用户友好的错误消息和恢复选项

## 安装

1. 打开您的 JetBrains IDE（IntelliJ IDEA、WebStorm、PyCharm 等）
2. 转到 **文件** → **设置** → **插件**
3. 在市场中搜索 "HttpPal"
4. 点击 **安装** 并重启您的 IDE

## 快速开始

### 1. 打开 HttpPal 工具窗口
- 使用 **工具** → **HttpPal** → **打开 HttpPal**
- 或按 `Ctrl+Alt+H`（Windows/Linux）/ `Cmd+Alt+H`（Mac）

### 2. 发现端点
- HttpPal 自动扫描您的项目以查找 REST 端点
- 点击 **刷新** 手动更新端点列表
- 双击任何端点将其加载到请求表单中

### 3. 配置请求
- 选择 HTTP 方法，输入 URL，添加请求头
- 为 POST/PUT/PATCH 请求添加请求体
- 配置超时和重定向设置

### 4. 发送请求
- 点击 **发送请求** 执行
- 查看格式化的响应，包括请求头和时间
- 请求自动添加到历史记录

### 5. 导出到 JMeter
- 点击 **导出到 JMeter** 打开导出对话框
- 选择单个请求、多个请求或并发场景
- 为负载测试配置线程数和迭代次数
- 保存为 .jmx 文件以在 Apache JMeter 中使用

## 配置

### 环境设置
1. 转到 **HttpPal** → **管理环境**
2. 为不同的部署目标创建环境
3. 为每个环境设置基础 URL 和全局请求头
4. 使用下拉菜单在环境之间切换

### 全局设置
1. 转到 **文件** → **设置** → **工具** → **HttpPal**
2. 配置默认超时、线程数和其他首选项
3. 设置应用于所有请求的全局请求头
4. 配置历史记录保留和收藏夹管理

## 键盘快捷键

| 操作 | Windows/Linux | Mac |
|------|---------------|-----|
| 打开 HttpPal | `Ctrl+Alt+H` | `Cmd+Alt+H` |
| 发送请求 | `Ctrl+Alt+S` | `Cmd+Alt+S` |
| 刷新端点 | `Ctrl+Alt+R` | `Cmd+Alt+R` |
| 打开 WebSocket | `Ctrl+Alt+W` | `Cmd+Alt+W` |
| 查看历史 | `Ctrl+Alt+Y` | `Cmd+Alt+Y` |
| 管理环境 | `Ctrl+Alt+E` | `Cmd+Alt+E` |
| 导出到 JMeter | `Ctrl+Alt+J` | `Cmd+Alt+J` |
| 快速测试端点 | `Ctrl+Shift+T` | `Cmd+Shift+T` |

## 支持的框架

### Spring 框架
- Spring MVC 控制器
- Spring Boot REST 控制器
- Spring WebFlux（响应式端点）
- 自定义请求映射

### JAX-RS
- Jersey 实现
- RESTEasy 实现
- Apache CXF 实现
- 自定义 JAX-RS 提供者

## 国际化支持

HttpPal 插件支持多种语言：
- **English**（英语）
- **简体中文**
- **繁體中文**
- **日本語**（日语）

插件会自动检测您的 IDE 语言设置并显示相应的界面语言。

## 技术栈

- **语言**: Kotlin (兼容 Java)
- **构建工具**: Gradle 配合 `gradle-intellij-plugin`
- **HTTP 客户端**: Java 11+ HttpClient 或 OkHttp
- **并发处理**: `java.util.concurrent` 包
- **UI 框架**: IntelliJ 平台 UI DSL
- **代码分析**: IntelliJ PSI (程序结构接口)

## 架构组件

1. **核心引擎**: HTTP/WebSocket 通信处理
2. **发现服务**: 自动 API 端点检测
3. **UI 层**: ToolWindow 和表单组件
4. **存储层**: 设置和历史持久化
5. **环境管理器**: 多环境配置
6. **并发执行器**: 并行请求执行

## 安全考虑

- 敏感头信息的安全存储
- 认证令牌的加密持久化
- 项目级别的安全隔离
- 多用户环境中的凭证安全处理

## 贡献

我们欢迎贡献！请查看我们的贡献指南以获取更多信息。

## 许可证

*(许可证信息待定)*

## 支持

- **文档**：[https://httppal.com/docs](https://httppal.com/docs)
- **问题反馈**：[https://github.com/httppal/httppal-plugin/issues](https://github.com/httppal/httppal-plugin/issues)
- **邮箱**：support@httppal.com
