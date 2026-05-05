# AI 聊天助手方案

## 一、整体架构

```
浏览器                         后端 (:8081)                    DeepSeek API
  │                             │                              │
  │  WS /ws/chat?token=xxx      │                              │
  │  (Upgrade: websocket)       │                              │
  ├────────────────────────────>│                              │
  │                             │                              │
  │  {"type":"send",            │                              │
  │   "content":"推荐火锅"}      │                              │
  ├────────────────────────────>│                              │
  │                             │  saveUserMessage()           │
  │                             │  查历史 getHistory()          │
  │                             ├── ChatClient.call() ────────>│
  │                             │  (Spring AI 封装)             │
  │                             │<── AI 回复 ──────────────────┤
  │                             │  saveAiReply()               │
  │  {"type":"message",         │                              │
  │   "from":"0","fromName":"AI助手",                          │
  │   "content":"推荐xx店..."}    │                              │
  │<────────────────────────────┤                              │
  │                             │                              │
  │  GET /chat/history          │                              │
  │  Header: Authorization      │                              │
  ├────────────────────────────>│  queryHistory(userId)        │
  │  { success, data: [...] }   │                              │
  │<────────────────────────────┤                              │
```

核心技术栈：**Spring Boot 3.5 + Spring AI 1.0.3 + Spring WebSocket + DeepSeek API**

---

## 二、数据模型

### 2.1 数据库表

```sql
CREATE TABLE tb_chat_message (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  from_user   BIGINT NOT NULL COMMENT '发送者：userId 或 0(AI)',
  to_user     BIGINT NOT NULL COMMENT '接收者：0(AI) 或 userId',
  content     TEXT   NOT NULL COMMENT '消息内容',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间'
);
```

### 2.2 字段约定

| 字段 | 用户发消息 | AI 回复 |
|---|---|---|
| `from_user` | 用户 ID（如 1001） | `0`（AI 固定 ID） |
| `to_user` | `0`（发给 AI） | 用户 ID（如 1001） |

历史查询 SQL：`WHERE from_user = ? OR to_user = ? ORDER BY create_time ASC`

### 2.3 实体类

[ChatMessage.java](src/main/java/com/syit/hmdp/entity/ChatMessage.java) — 遵循项目标准 Entity 模式：`@Data @EqualsAndHashCode @Accessors(chain=true) @TableName("tb_chat_message")`，继承 `Serializable`，`createTime` 使用 `LocalDateTime`。

---

## 三、鉴权设计

### 3.1 HTTP 接口鉴权

`GET /chat/history` 走 Spring MVC 拦截器链，与现有接口完全一致：

```
请求 → RefreshTokenInterceptor（读 Authorization header → 查 Redis → UserHolder）
     → LoginInterceptor（检查 UserHolder → 401 或放行）
     → ChatController.getHistory()
```

不需要在 `MvcConfig` 中排除 `/chat/**`——需要登录才能访问。

### 3.2 WebSocket 鉴权

WebSocket 握手不经过 Spring MVC 拦截器，需要单独的 `HandshakeInterceptor`：

[ChatWebSocketInterceptor.java](src/main/java/com/syit/hmdp/ws/ChatWebSocketInterceptor.java)：

```
beforeHandshake:
  1. 从 URI query 解析 token 参数: /ws/chat?token=xxx
  2. 查 Redis: HGETALL login:token:{token}
  3. 不存在 → 返回 false（拒绝握手）
  4. 存在 → BeanUtil.fillBeanWithMap → UserDTO → 存入 WebSocket session attributes
  5. 返回 true
```

| 对比 | HTTP 鉴权 | WebSocket 鉴权 |
|---|---|---|
| token 位置 | `Authorization` header | `?token=xxx` query 参数 |
| 拦截机制 | Spring MVC HandlerInterceptor | WebSocket HandshakeInterceptor |
| 用户存储 | `UserHolder`（ThreadLocal） | WebSocket session attributes |

---

## 四、HTTP 接口

### 4.1 GET /chat/history

[ChatController.java](src/main/java/com/syit/hmdp/controller/ChatController.java)

```
GET /chat/history?page=1&size=20
Header: Authorization: <token>
```

**成功响应：**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "fromUser": 1001,
      "toUser": 0,
      "content": "推荐附近的火锅店",
      "createTime": "2026-05-05T14:30:00"
    },
    {
      "id": 2,
      "fromUser": 0,
      "toUser": 1001,
      "content": "为您推荐以下火锅店：1. 海底捞...",
      "createTime": "2026-05-05T14:30:05"
    }
  ],
  "total": 2
}
```

**实现**：[ChatMessageServiceImpl.getHistory()](src/main/java/com/syit/hmdp/service/impl/ChatMessageServiceImpl.java) — MyBatis-Plus `Page` 分页，`lambdaQuery().eq(fromUser).or().eq(toUser).orderByAsc(createTime)`，返回 `Result.ok(records, total)`。

---

## 五、WebSocket 接口

### 5.1 连接

```
ws://<host>:8081/ws/chat?token=<token>
```

由 [WebSocketConfig.java](src/main/java/com/syit/hmdp/config/WebSocketConfig.java) 注册端点，绑定 [ChatWebSocketHandler](src/main/java/com/syit/hmdp/ws/ChatWebSocketHandler.java) + [ChatWebSocketInterceptor](src/main/java/com/syit/hmdp/ws/ChatWebSocketInterceptor.java)。

### 5.2 前端 → 后端：发送消息

```json
{
  "type": "send",
  "content": "推荐附近的火锅店"
}
```

**后端处理流程**（[ChatWebSocketHandler.handleTextMessage()](src/main/java/com/syit/hmdp/ws/ChatWebSocketHandler.java)）：

```
1. 解析 JSON {type, content}
2. type != "send" → 忽略
3. 从 session attributes 取 UserDTO → userId
4. chatMessageService.saveUserMessage(userId, content)     → 写入 DB
5. chatMessageService.lambdaQuery() 查完整历史              → AI 上下文
6. aiService.chat(userId, content, history)
     ↓
   AiServiceImpl（Spring AI ChatClient）:
     构造 messages: [SystemMessage(systemPrompt),
                     ...历史 → UserMessage/AssistantMessage...,
                     UserMessage(当前消息)]
     → chatClient.prompt().messages(...).call().content()
     → 返回 AI 回复文本
7. chatMessageService.saveAiReply(userId, aiReply)         → 写入 DB
8. 构造推送 JSON → sessionManager.sendToUser(userId, json)
```

### 5.3 后端 → 前端：推送回复

```json
{
  "type": "message",
  "from": "0",
  "fromName": "AI助手",
  "fromIcon": "/imgs/icons/ai-icon.png",
  "content": "为您推荐以下火锅店：1. 海底捞（评分4.8）...",
  "time": "2026-05-05 14:30:05"
}
```

### 5.4 会话管理

[ChatWebSocketSessionManager.java](src/main/java/com/syit/hmdp/ws/ChatWebSocketSessionManager.java)：

| 方法 | 说明 |
|---|---|
| `addSession(userId, session)` | 连接建立时注册 |
| `removeSession(userId)` | 连接关闭时移除 |
| `sendToUser(userId, message)` | 推送消息（`synchronized(session)` 保证线程安全） |

底层 `ConcurrentHashMap<Long, WebSocketSession>`，`synchronized(session)` 防止同一连接并发写入。

---

## 六、AI 服务

### 6.1 为什么用 Spring AI

| 维度 | 裸 RestTemplate | Spring AI |
|---|---|---|
| 代码量 | ~80 行 | ~15 行 |
| 换模型 | 改 URL + 改请求体 | 只改 yaml |
| 流式输出 | 自己处理 SSE | `stream()` 返回 `Flux<String>` |
| 重试/超时 | 自己写 | 内置 RetryTemplate |
| DeepSeek 兼容 | 手动处理 | `base-url` 指向 DeepSeek 即可 |

### 6.2 实现

[AiServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AiServiceImpl.java)：

```java
@Service
public class AiServiceImpl implements IAiService {

    private final ChatClient chatClient;
    private final String systemPrompt;

    public AiServiceImpl(ChatClient.Builder builder,
                         @Value("${chat.system-prompt}") String systemPrompt) {
        this.chatClient = builder.build();
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String chat(Long userId, String userMessage, List<ChatMessage> history) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (ChatMessage msg : history) {
            if (msg.getFromUser() == 0) {
                messages.add(new AssistantMessage(msg.getContent()));
            } else {
                messages.add(new UserMessage(msg.getContent()));
            }
        }
        messages.add(new UserMessage(userMessage));

        return chatClient.prompt().messages(messages).call().content();
    }
}
```

### 6.3 配置

[application.yaml](src/main/resources/application.yaml)：

```yaml
spring:
  ai:
    openai:
      api-key: sk-xxx                            # DeepSeek API Key
      base-url: https://api.deepseek.com         # DeepSeek 兼容 OpenAI 接口
      chat:
        options:
          model: deepseek-chat                    # 或 deepseek-v4-flash

chat:
  system-prompt: "你是本地生活推荐平台的AI客服助手..."
```

`ChatClient.Builder` 由 Spring AI 自动配置注入，无需手动创建 Bean。

### 6.4 历史消息角色映射

| ChatMessage.fromUser | Spring AI Message 类型 |
|---|---|
| != 0 | `UserMessage` |
| == 0 | `AssistantMessage` |
| 第一条 | `SystemMessage`（system prompt） |

---

## 七、异常处理

### 7.1 AI 调用失败

```java
try {
    aiReply = aiService.chat(userId, content, history);
} catch (Exception e) {
    log.error("AI 调用失败, userId={}", userId, e);
    aiReply = "抱歉，AI 服务暂时不可用，请稍后重试。";
}
```

AI 调用失败不阻塞 WebSocket——返回友好提示信息，用户消息已保存到 DB。

### 7.2 WebSocket 鉴权失败

`ChatWebSocketInterceptor.beforeHandshake()` 返回 `false`，拒绝握手。前端 WebSocket 连接失败，触发重连逻辑。

### 7.3 用户断线

`afterConnectionClosed()` 自动从 `ChatWebSocketSessionManager` 移除会话，不影响后续重连。

---

## 八、文件清单

### 新建文件（11 个）

| # | 文件 | 说明 |
|---|---|---|
| 1 | `entity/ChatMessage.java` | 实体类 |
| 2 | `mapper/ChatMessageMapper.java` | MyBatis-Plus Mapper |
| 3 | `service/IChatMessageService.java` | 消息服务接口 |
| 4 | `service/impl/ChatMessageServiceImpl.java` | 历史查询 + 保存用户/AI 消息 |
| 5 | `service/IAiService.java` | AI 服务接口 |
| 6 | `service/impl/AiServiceImpl.java` | Spring AI ChatClient 调用 |
| 7 | `controller/ChatController.java` | `GET /chat/history` |
| 8 | `ws/ChatWebSocketInterceptor.java` | WebSocket 握手鉴权 |
| 9 | `ws/ChatWebSocketSessionManager.java` | 会话管理 |
| 10 | `ws/ChatWebSocketHandler.java` | 消息收发 + AI 调用编排 |
| 11 | `config/WebSocketConfig.java` | WS 端点注册 |

### 修改的配置（2 个）

| 文件 | 改动 |
|---|---|
| `pom.xml` | 新增 `spring-boot-starter-websocket` + `spring-ai-starter-model-openai:1.0.3` |
| `application.yaml` | 新增 `spring.ai.openai.*` + `chat.system-prompt` |

**不修改任何已有 `.java` 源文件。**

---

## 九、与项目现有能力的关系

| 能力 | 复用方式 |
|---|---|
| Token 鉴权 | HTTP 路径复用现有 `RefreshTokenInterceptor` + `LoginInterceptor` |
| 用户信息 | `UserHolder.getUser()` + `UserDTO(id, nickName, icon)` |
| Redis Token 存储 | `login:token:{token}` → Hash，WebSocket 握手时同样查此 key |
| MyBatis-Plus CRUD | `ServiceImpl<Mapper, Entity>` 标准模式 |
| 响应格式 | `Result.ok(data, total)` / `Result.fail(msg)` |
| 全局异常处理 | `WebExceptionAdvice` 捕获未处理异常 |

---

## 十、启动与验证

### 10.1 前置条件

1. MySQL 中创建 `tb_chat_message` 表
2. `application.yaml` 中配置有效的 DeepSeek API Key
3. 已有用户完成登录，拿到 token

### 10.2 验证步骤

1. `mvn spring-boot:run` 启动，日志中出现 `WebSocket` 端点注册
2. `curl http://localhost:8081/chat/history?page=1 -H "Authorization: <token>"` → 返回空列表
3. 浏览器 WebSocket 客户端连接 `ws://localhost:8081/ws/chat?token=<token>`
4. 发送 `{"type":"send","content":"推荐附近的火锅店"}`
5. 收到 AI 回复 `{"type":"message","from":"0","fromName":"AI助手",...}`
6. 再次调 `/chat/history`，返回 2 条消息（用户消息 + AI 回复）
7. 断开 WebSocket 后重连，历史消息仍在
