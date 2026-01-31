# GraphQL 依赖说明

## 当前配置

在 `build.gradle.kts` 中只使用了核心 GraphQL 库：

```kotlin
// GraphQL support
implementation("com.graphql-java:graphql-java:21.5")
```

## 已移除的依赖

```kotlin
// 已注释掉（当前实现不需要）
// implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
```

## 为什么移除 extended-scalars？

1. **当前实现不需要**: 我们的实现主要使用 `graphql-java` 进行：
   - Schema introspection（获取 schema）
   - 解析 GraphQL 类型和字段
   - 基本的 GraphQL 操作

2. **避免依赖问题**: `graphql-java-extended-scalars` 的版本号与 `graphql-java` 不同步，容易导致混淆。

3. **足够的功能**: `graphql-java:21.5` 已经包含了所有标准的 GraphQL 标量类型：
   - String
   - Int
   - Float
   - Boolean
   - ID

## 何时需要 extended-scalars？

如果将来需要支持以下扩展标量类型，可以取消注释：

- **DateTime** - 日期时间类型
- **Date** - 日期类型
- **Time** - 时间类型
- **JSON** - JSON 对象类型
- **URL** - URL 类型
- **Locale** - 区域设置类型
- **BigDecimal** - 大数字类型
- 等等...

## 如何添加 extended-scalars（可选）

如果需要，在 `build.gradle.kts` 中取消注释：

```kotlin
// GraphQL support
implementation("com.graphql-java:graphql-java:21.5")
implementation("com.graphql-java:graphql-java-extended-scalars:22.0")
```

然后运行：
```bash
gradle clean build
# 或在 IDE 中重新导入 Gradle 项目
```

## 验证依赖

### 使用 IDE（推荐）

1. 在 IntelliJ IDEA 中打开项目
2. 右键点击 `build.gradle.kts`
3. 选择 "Reload Gradle Project"
4. 等待依赖下载完成

### 使用命令行

```bash
gradle dependencies --configuration implementation | grep graphql
```

应该只看到：
```
com.graphql-java:graphql-java:21.5
```

## 常见问题

### Q: 为什么之前设置为 21.1？
A: 这是一个错误。`extended-scalars` 的版本号是独立的，不跟随 `graphql-java` 的版本号。

### Q: 当前功能受影响吗？
A: 不会。我们所有的 GraphQL 功能都只依赖核心的 `graphql-java` 库。

### Q: 如何测试是否正常工作？
A: 运行 `gradle runIde` 并测试 GraphQL 功能。所有功能应该正常工作。

## 总结

当前配置已经足够支持所有实现的 GraphQL 功能：
- ✅ Schema Introspection
- ✅ 查询执行
- ✅ 变量支持
- ✅ 错误处理
- ✅ Schema 浏览
- ✅ 自动补全
- ✅ Mock 数据生成

如有任何问题，请参考官方文档：
- [graphql-java](https://www.graphql-java.com/)
- [graphql-java-extended-scalars](https://github.com/graphql-java/graphql-java-extended-scalars)
