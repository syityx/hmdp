# 聊天模块后端接口文档

## 1. 架构总览

```
浏览器                          Nginx (:8080)               后端 (:8081)
  │                                │                           │
  │  GET /api/chat/history         │                           │
  │  Header: Authorization: <token>│                           │
  ├───────────────────────────────>│  GET /chat/history        │
  │                                ├──────────────────────────>│
  │                                │  { success, data: [...] } │
  │  { success, data: [...] }      │<──────────────────────────┤
  │<───────────────────────────────┤                           │
  │                                │                           │
  │  WS /api/ws/chat?token=<token> │                           │
  │  (Upgrade: websocket)          │                           │
  ├───────────────────────────────>│  WS /ws/chat?token=<tok>  │
  │                                ├──────────────────────────>│
  │  {"type":"send","content":"x"} ├──────────────────────────>│
  │  {"type":"message",...}        │<──────────────────────────┤
  │<───────────────────────────────┤                           │
```

**关键信息：**
- Nginx 将 `/api(/.*)` rewrite 为 `$1`，即去掉前缀 `/api`
- 后端实际收到的路径：`/chat/history`、`/ws/chat`
- Nginx 已配置 WebSocket Upgrade 头转发，支持长连接

## 2. 鉴权方式

与现有所有接口一致：
- HTTP 请求：前端 axios 拦截器自动在 `Authorization` 请求头放入 token（用户登录后存储在 sessionStorage）
- WebSocket 请求：token 通过 URL query 参数 `?token=xxx` 传递（因为浏览器 WebSocket API 不支持自定义请求头）

**后端鉴权逻辑：**
1. 从请求中提取 token
2. 根据 token 获取当前登录用户（参考现有 `/user/me` 的实现）
3. token 无效或过期返回 401（HTTP）或关闭连接（WebSocket）

## 3. HTTP 接口

### 3.1 获取历史消息

```
GET /chat/history
Header: Authorization: <token>
```

**响应格式（与现有接口一致，由 axios 拦截器处理）：**

成功：
```json
{
  "success": true,
  "data": [
    {
      "from": "用户ID",
      "fromName": "张三",
      "fromIcon": "/imgs/icons/xxx.jpg",
      "content": "你好",
      "time": "2026-05-05 14:30:00"
    },
    {
      "from": "当前用户ID",
      "fromName": "李四",
      "fromIcon": "/imgs/icons/yyy.jpg",
      "content": "你好呀",
      "time": "2026-05-05 14:31:00"
    }
  ]
}
```

失败：
```json
{
  "success": false,
  "errorMsg": "请先登录"
}
```

**说明：**
- 返回当前用户参与的所有聊天消息，按时间正序排列
- 前端根据 `from` 字段区分自己和对方的消息，渲染在不同侧
- 建议做分页，前端滚动到顶部时会再次请求

## 4. WebSocket 接口

### 4.1 连接

```
ws://<host>:8081/ws/chat?token=<token>
```

后端需要：
1. 从 query 参数中提取 token 并鉴权
2. 维护 userId → WebSocket session 的映射，以便转发消息给目标用户
3. 鉴权失败时关闭连接（建议返回 4001 状态码）

### 4.2 前端 → 后端：发送消息

```json
{
  "type": "send",
  "content": "消息文本内容"
}
```

后端处理逻辑：
1. 解析 JSON，如果 `type` 为 `"send"`，则这是一条待发送的聊天消息
2. 从当前 WebSocket 连接对应的 token 中获取发送者信息（userId、nickName、icon）
3. 确定消息接收者（业务逻辑自行定义，例如：发给客服、发给某个关联商户、或在好友关系表中查找）
4. 构造消息对象并持久化存储
5. 将消息推送给接收者的 WebSocket 连接（如果在线）

### 4.3 后端 → 前端：接收消息

```json
{
  "type": "message",
  "from": "发送者用户ID",
  "fromName": "发送者昵称",
  "fromIcon": "/imgs/icons/xxx.jpg",
  "content": "消息文本内容",
  "time": "2026-05-05 14:30:00"
}
```

**说明：**
- `time` 格式建议 `yyyy-MM-dd HH:mm:ss`
- `fromIcon` 为头像相对路径，前端已有默认图兜底：`/imgs/icons/default-icon.png`
- 后端自行决定何时向哪个连接推送消息（用户发给客服、用户间私聊、广播等）

## 5. 数据模型建议（MySQL）

```sql
CREATE TABLE chat_message (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  from_user   BIGINT NOT NULL COMMENT '发送者用户ID',
  to_user     BIGINT NOT NULL COMMENT '接收者用户ID',
  content     TEXT   NOT NULL COMMENT '消息内容',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间'
);
```

## 6. 前端行为补充说明

| 场景 | 前端行为 |
|------|----------|
| 页面加载，无 token | 直接 302 跳转 `/login.html` |
| 页面加载，有 token | 先调 `/user/me` 获取当前用户信息，然后调 `/chat/history` 拉历史消息，最后建 WebSocket |
| WebSocket 断开 | 自动重连，3 秒间隔，最多 5 次，超过提示用户刷新 |
| 历史消息不足 | 用户滚动到顶部时再次请求 `/chat/history` |
| 发送消息离线 | 发送按钮灰显，`sendMessage` 方法直接 return 不发送 |
| HTTP 接口返回 401 | axios 拦截器统一处理，跳转 `/login.html` |

## 7. 与现有后端对接注意事项

- 项目现有的所有接口返回值格式统一为 `{ success: true/false, data: ..., errorMsg: "..." }`（见 `common.js` 响应拦截器）
- 现有用户接口 `/user/me`、`/user/login` 等可复用，token 生成和验证逻辑不要重复实现
- WebSocket 是新增协议，但 token 解析逻辑应与 HTTP 拦截器一致
- 后端 Java 项目应该已经有 Spring Boot + WebSocket 的依赖，如果没有需引入 `spring-boot-starter-websocket`
