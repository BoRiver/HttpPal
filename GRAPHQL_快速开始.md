# GraphQL 快速开始指南

## 测试实现

### 1. 构建并运行插件

```bash
cd D:\my_work\http-pal-copy\HttpPal
.\gradlew runIde
```

这将启动一个沙盒 IntelliJ IDEA 实例，并安装 HttpPal 插件。

### 2. 打开 HttpPal 工具窗口

- 点击 IDE 右侧的"HttpPal"标签页
- 或使用快捷键：`Ctrl+Alt+H`

### 3. 切换到 GraphQL 标签页

在工具窗口中点击"GraphQL"标签页。

### 4. 尝试第一个查询

**端点**: `https://countries.trevorblades.com/`

**查询**:
```graphql
query {
  countries {
    name
    code
    capital
  }
}
```

**步骤**:
1. 在"端点"字段中输入端点 URL
2. 将查询粘贴到查询编辑器中
3. 点击"执行查询"
4. 在响应面板（数据标签页）中查看结果

### 5. 测试变量

**查询**:
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

**变量**（在变量编辑器中输入）:
```json
{
  "code": "CN"
}
```

**步骤**:
1. 将上面的查询替换到查询编辑器
2. 在变量编辑器中添加变量
3. 点击"执行查询"
4. 查看过滤后的结果

### 6. 测试 Schema Introspection

1. 保持 Countries API 端点
2. 点击"获取 Schema"
3. 您应该会看到成功消息："Schema 获取成功！找到 X 个类型。"
4. 查看左侧的 Schema 浏览器，展开"Query"节点查看可用字段
5. 双击一个字段，它会自动插入到查询编辑器中

### 7. 测试生成示例查询

1. 确保已获取 Schema（参见步骤 6）
2. 点击"生成示例"按钮
3. 查询编辑器中将自动生成示例查询
4. 点击"执行查询"查看结果

### 8. 测试错误处理

**无效查询**:
```graphql
query {
  invalidField {
    name
  }
}
```

**步骤**:
1. 输入这个无效查询
2. 点击"执行查询"
3. UI 应自动切换到"错误"标签页
4. 查看来自 GraphQL 服务器的错误消息

### 9. 测试自动补全

1. 确保已获取 Schema
2. 在查询编辑器中开始输入查询
3. 按 `Ctrl+Space` 触发自动补全
4. 选择一个建议的字段（如果自动补全当前未完全实现，可跳过此步骤）

### 10. 验证历史记录

1. 执行几个查询后
2. 历史记录应自动保存在内存中
3. （历史记录 UI 集成已完成 - 查询保存在内存中）

## 更多测试端点

### SpaceX API

**端点**: `https://spacex-production.up.railway.app/`

**查询**:
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

**端点**: `https://rickandmortyapi.com/graphql`

**查询**:
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

### Star Wars API

**端点**: `https://swapi-graphql.netlify.app/.netlify/functions/index`

**查询**:
```graphql
query {
  allFilms {
    films {
      title
      director
      releaseDate
    }
  }
}
```

## 验证清单

### ✅ 功能检查列表

- [ ] GraphQL 标签页出现在 HttpPal 工具窗口中
- [ ] 端点字段接受 URL 输入
- [ ] 查询编辑器有语法高亮（GraphQL 或 JSON）
- [ ] 变量编辑器接受 JSON 输入
- [ ] "执行查询"按钮工作正常
- [ ] 响应在"数据"标签页中显示
- [ ] 错误在"错误"标签页中显示（使用无效查询测试）
- [ ] 有错误时自动切换到"错误"标签页
- [ ] "获取 Schema"按钮工作正常
- [ ] Schema 浏览器显示类型树
- [ ] 双击字段可插入到查询编辑器
- [ ] "生成示例"按钮创建示例查询
- [ ] 成功/错误消息出现在状态栏
- [ ] 无编译错误
- [ ] 无运行时异常（检查 IDE 日志）

### ✅ UI/UX 检查列表

- [ ] 布局清晰直观（三栏：Schema | 请求 | 响应）
- [ ] 分隔栏可拖动调整大小
- [ ] 按钮提供视觉反馈
- [ ] 文本可读且格式正确
- [ ] 状态标签更新适当
- [ ] JSON 响应格式良好（pretty-printed）
- [ ] 编辑器中显示行号
- [ ] 中文界面正确显示

### ✅ 错误处理检查列表

- [ ] 空端点显示警告
- [ ] 空查询显示警告
- [ ] 变量中的无效 JSON 显示错误
- [ ] 网络错误优雅处理
- [ ] GraphQL 错误清晰显示
- [ ] 不向用户显示堆栈跟踪（错误已记录）

## 故障排除

### 插件无法加载
- 检查 `build/idea-sandbox/system/log/idea.log` 中的错误
- 验证所有依赖已下载：`.\gradlew dependencies`
- 尝试清理构建：`.\gradlew clean build`

### GraphQL 标签页缺失
- 检查 HttpPalToolWindow.kt 是否正确修改
- 验证 plugin.xml 中所有服务注册
- 查找构建输出中的编译错误

### 查询无法执行
- 检查 IDE 日志中的异常
- 验证端点 URL 正确
- 先在浏览器或 Postman 中测试端点
- 检查网络连接

### 无语法高亮
- 如果未安装 GraphQL 插件，这是预期行为
- 应降级到 JSON 语法高亮
- 从 JetBrains Marketplace 安装"GraphQL"插件以获得完整高亮

### Introspection 失败
- 某些端点可能禁用 introspection 查询
- 检查端点是否需要认证
- 尝试其他端点（Countries API 应该可以工作）

### Schema 浏览器为空
- 确保首先点击"获取 Schema"
- 检查 introspection 是否成功
- 查看状态消息以了解任何错误

### 生成示例失败
- 确保首先获取 Schema
- 检查 schema 是否包含 Query 类型
- 查看 IDE 日志以了解详细错误

## 完整测试会话示例

```
1. 启动：.\gradlew runIde
2. 等待 IDE 启动（约 30-60 秒）
3. 打开 HttpPal 工具窗口（Ctrl+Alt+H）
4. 点击"GraphQL"标签页
5. 输入端点：https://countries.trevorblades.com/
6. 点击"获取 Schema" → 看到"Schema 获取成功！"
7. 展开 Schema 浏览器中的"Query"节点
8. 双击"countries"字段 → 自动插入到查询编辑器
9. 编辑查询以添加字段：
   query {
     countries {
       name
       code
     }
   }
10. 点击"执行查询"
11. 在"数据"标签页中查看欧洲国家列表
12. 点击"生成示例" → 查询编辑器填充示例查询
13. 点击"执行查询" → 查看结果
14. 尝试无效查询 → 在"错误"标签页中看到错误
15. 成功！GraphQL 支持正常工作 ✅
```

## 测试后续步骤

如果一切正常：
1. 测试其他 GraphQL 端点
2. 尝试更复杂的查询（嵌套、别名等）
3. 测试带多个变量的查询
4. 探索不同的 schema 结构
5. 测试端点发现（如果有 Spring GraphQL 项目）
6. 构建发布版本：`.\gradlew buildPlugin`
7. 在 `build/distributions/` 中找到插件 ZIP 文件

## 获取帮助

如果遇到问题：
1. 检查 IDE 日志：`build/idea-sandbox/system/log/idea.log`
2. 查找控制台输出中的异常
3. 验证所有文件都已正确创建
4. 查看实现摘要文档
5. 检查 plugin.xml 中的服务注册是否正确

祝测试愉快！🚀
