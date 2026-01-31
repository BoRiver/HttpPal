# GraphQL 实现故障排除指南

## 依赖问题已解决 ✅

### 问题
```
Could not get resource 'https://repo.maven.apache.org/maven2/com/graphql-java/graphql-java-extended-scalars/21.1/graphql-java-extended-scalars-21.1.pom'
```

### 解决方案
已将 `graphql-java-extended-scalars:21.1` 从依赖中移除（已注释）。当前只使用 `graphql-java:21.5` 核心库。

### 当前配置
```kotlin
// build.gradle.kts
implementation("com.graphql-java:graphql-java:21.5")
// Extended scalars 已注释（可选，当前不需要）
```

## 如何测试修复

### 方法 1: 使用 IntelliJ IDEA（推荐）

1. 打开 IntelliJ IDEA
2. 打开项目：`D:\my_work\http-pal-copy\HttpPal`
3. 等待 IDEA 索引完成
4. 右键点击 `build.gradle.kts`
5. 选择 "Reload Gradle Project"
6. 等待依赖下载（应该只下载 graphql-java:21.5）
7. 点击运行 "runIde" 配置

### 方法 2: 使用命令行（如果有 Gradle）

```bash
cd D:\my_work\http-pal-copy\HttpPal

# 清理并构建
gradle clean build

# 或直接运行 IDE
gradle runIde
```

### 方法 3: 生成 Gradle Wrapper

如果没有 gradlew 文件，可以生成：

```bash
cd D:\my_work\http-pal-copy\HttpPal
gradle wrapper
```

然后使用：
```bash
.\gradlew.bat runIde  # Windows
./gradlew runIde       # Linux/Mac
```

## 验证依赖已正确加载

### 在 IDE 中检查

1. 打开 "Project Structure" (Ctrl+Alt+Shift+S)
2. 选择 "Libraries"
3. 查找 `graphql-java:21.5`
4. 不应该有任何关于 `extended-scalars` 的错误

### 检查外部库

在项目视图中展开 "External Libraries"，应该看到：
- ✅ `com.graphql-java:graphql-java:21.5`
- ❌ ~~graphql-java-extended-scalars~~（不应该出现）

## 常见问题

### Q1: 依赖下载失败
**解决方案**:
1. 检查网络连接
2. 配置中已包含阿里云镜像：
   ```kotlin
   repositories {
       maven { url = uri("https://maven.aliyun.com/repository/public") }
       mavenCentral()
   }
   ```
3. 在 IDEA 中重新 Sync Gradle

### Q2: 编译错误
**解决方案**:
1. 清理项目：`gradle clean`
2. 删除 `.gradle` 和 `build` 目录
3. 重新导入项目
4. Reload Gradle Project

### Q3: 类找不到
**症状**: `java.lang.ClassNotFoundException: graphql.*`

**解决方案**:
1. 确认 `graphql-java:21.5` 已下载
2. Invalidate Caches (IDEA: File -> Invalidate Caches)
3. 重启 IDE

### Q4: 运行 runIde 失败
**解决方案**:
1. 检查 JDK 版本（需要 JDK 21）
2. 检查 IDEA 版本（需要 2025.1+）
3. 查看控制台输出的错误信息
4. 检查 `build/idea-sandbox/system/log/idea.log`

## GraphQL 功能验证

运行成功后，验证以下功能：

### 基础功能测试
1. ✅ GraphQL 标签页出现
2. ✅ 可以输入端点 URL
3. ✅ 可以编写查询
4. ✅ 可以执行查询
5. ✅ 响应正确显示

### Schema 功能测试
1. ✅ Schema Introspection 工作
2. ✅ Schema 浏览器显示类型
3. ✅ 双击字段可插入

### 高级功能测试
1. ✅ 生成示例查询
2. ✅ 变量编辑器
3. ✅ 错误显示
4. ✅ 历史记录

## 测试用例

### 测试 1: 简单查询
```graphql
# 端点: https://countries.trevorblades.com/
query {
  countries {
    name
    code
  }
}
```
**期望**: 返回国家列表

### 测试 2: Schema Introspection
```
1. 输入端点
2. 点击"获取 Schema"
3. 查看 Schema 浏览器
```
**期望**: 显示类型树，包含 Query、Mutation 等

### 测试 3: 生成示例
```
1. 获取 Schema 后
2. 点击"生成示例"
```
**期望**: 查询编辑器自动填充示例查询

## 如果问题仍然存在

### 收集调试信息

1. **Gradle 版本**:
   ```bash
   gradle --version
   ```

2. **Java 版本**:
   ```bash
   java -version
   ```

3. **依赖树**:
   ```bash
   gradle dependencies --configuration implementation > deps.txt
   ```

4. **IDE 日志**:
   ```
   build/idea-sandbox/system/log/idea.log
   ```

### 联系支持

提供以下信息：
- 操作系统版本
- JDK 版本
- IDEA 版本
- 完整的错误信息
- deps.txt 文件内容

## 确认修复成功

✅ 没有依赖下载错误
✅ 项目编译成功
✅ runIde 启动成功
✅ GraphQL 标签页显示
✅ 可以执行查询
✅ Schema Introspection 工作

## 下一步

修复成功后：
1. 阅读 `GRAPHQL_快速开始.md`
2. 测试各项功能
3. 查看 `GRAPHQL_支持文档.md` 了解详细用法
4. 尝试不同的 GraphQL 端点

祝使用愉快！🚀
