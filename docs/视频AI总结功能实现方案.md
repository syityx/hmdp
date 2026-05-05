# 视频 AI 总结功能实现方案

## 一、整体架构

```
浏览器                             后端 (:8081)                    MinIO      SiliconFlow API
  │                                  │                              │              │
  │  POST /blog/summary/{id}         │                              │              │
  ├─────────────────────────────────>│                              │              │
  │<── {"status":"processing"} ──────┤  查 Blog → 校验 videoId      │              │
  │                                  │  INSERT status=0             │              │
  │                                  │  提交到线程池                  │              │
  │                                  │                              │              │
  │  GET /blog/summary/{id}  (轮询)  │                              │              │
  ├─────────────────────────────────>│  SELECT status FROM           │              │
  │<── {"status":"processing"} ──────┤  tb_blog_summary              │              │
  │                                  │                              │              │
  │                     Worker 线程异步执行：                         │              │
  │                       ↓ 下载视频 ├─────────────────────────────>│              │
  │                       ↓ FFmpeg   │<── 视频文件 ──────────────────│              │
  │                       ↓ ASR 转录 ├──────────────────────────────┼─────────────>│
  │                       ↓ LLM 总结 ├──────────────────────────────┼─────────────>│
  │                       ↓ UPDATE   │<── 转录+总结 ─────────────────┼──────────────│
  │                             status=1, summary=xxx               │
  │                                  │                              │              │
  │  GET /blog/summary/{id}          │                              │              │
  ├─────────────────────────────────>│                              │              │
  │<── {"summary":"...","status":"done"}                            │              │
```

核心技术栈：**Spring Boot 3.5 + Spring AI 1.0.3 + Spring @Async + FFmpeg + SiliconFlow SenseVoice + Qwen3-8B**

---

## 二、方案选型：异步轮询 vs WebSocket

### 2.1 当前方案：异步轮询

| 维度 | 说明 |
|------|------|
| 调用方式 | `POST` 触发 → 立返 `processing`；`GET` 轮询拿结果 |
| 线程模型 | `@Async` 线程池，HTTP 线程不阻塞 |
| 状态管理 | `tb_blog_summary.status` 字段（0=处理中 / 1=完成 / 2=失败） |
| 容错 | 进程重启后状态还在 DB；刷新页面不丢结果 |
| 实现复杂度 | 低——两个 HTTP 接口 + 一个 @Async 方法 |
| 前端改动 | 两行代码：`fetch(POST)` + `setInterval(fetch(GET))` |

### 2.2 WebSocket 方案（已废弃）

| 维度 | 说明 |
|------|------|
| 调用方式 | 建 WS 连接 → 发消息 → 等服务端推送 → 关连接 |
| 线程模型 | Tomcat WS 线程阻塞等 15-40s |
| 状态管理 | 无持久化状态，依赖 WS 连接生命周期 |
| 容错 | 刷新页面 → WS 断开 → 结果推空，后端仍在跑 |
| 实现复杂度 | 中——Handler + Interceptor + SessionManager + Config |
| 额外依赖 | Nginx 需配 `Upgrade` / `Connection: upgrade` 才能转发 WS |

### 2.3 为什么用轮询而不是 WebSocket

1. **连接成本不匹配**：WS 适合高频双向通信（如聊天）。总结是一发一收、15-40s 才出结果，WS 长连接浪费资源
2. **容错差**：用户刷新页面 WS 即断，结果丢失，需要额外断线恢复逻辑
3. **运维成本高**：Nginx 要配 WS 代理，前端要处理重连
4. **轮询足够**：前端每 2 秒一次 GET，流量极小，状态在 DB 中持久化，刷新页面不丢结果

---

## 三、数据模型

### 3.1 tb_blog_summary

```sql
CREATE TABLE tb_blog_summary (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  blog_id     BIGINT NOT NULL UNIQUE COMMENT '博客ID',
  transcript  MEDIUMTEXT NULL COMMENT '语音转录文本',
  summary     TEXT NULL COMMENT 'AI总结（处理中时为NULL）',
  model       VARCHAR(64) NULL COMMENT 'AI模型',
  status      TINYINT DEFAULT 0 COMMENT '0=处理中 1=完成 2=失败',
  error_msg   VARCHAR(500) NULL COMMENT '失败原因',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_blog_id (blog_id)
);
```

### 3.2 status 状态机

```
         POST 请求
            │
            ▼
      ┌──────────┐
      │ status=0 │  处理中（pending 记录）
      │          │
      └────┬─────┘
           │ @Async 处理
      ┌────┴─────┐
      ▼          ▼
  ┌───────┐  ┌───────┐
  │status=│  │status=│
  │   1   │  │   2   │
  │ 完成  │  │ 失败  │
  └───────┘  └───┬───┘
                 │ 再次 POST
                 ▼
            删掉重试（回到 status=0）
```

### 3.3 实体类

[BlogSummary.java](src/main/java/com/syit/hmdp/entity/BlogSummary.java)：

```java
@TableName("tb_blog_summary")
public class BlogSummary implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long blogId;
    private String transcript;
    private String summary;
    private String model;
    private Integer status;     // 0=处理中 1=完成 2=失败
    private String errorMsg;
    private LocalDateTime createdAt;
}
```

---

## 四、API 接口

### 4.1 POST /blog/summary/{id} —— 触发生成

```
POST /blog/summary/26
Header: Authorization: <token>
```

**已完成（秒返）**：
```json
{"success": true, "data": {"summary": "视频展示了...", "status": "done"}}
```

**处理中（秒返）**：
```json
{"success": true, "data": {"status": "processing"}}
```

**异常**：
```json
{"success": false, "message": "该博客没有关联视频"}
```

### 4.2 GET /blog/summary/{id} —— 轮询结果

```
GET /blog/summary/26
Header: Authorization: <token>
```

| 状态 | 响应 |
|------|------|
| 处理中 | `{"success": true, "data": {"status": "processing"}}` |
| 完成 | `{"success": true, "data": {"summary": "视频展示了...", "status": "done"}}` |
| 无记录/失败 | `{"success": true, "data": null}` |

### 4.3 videoId 解析

不依赖 Blog 表新增字段，直接从 `images` 字段中解析：

```java
// blog.images = "/api/video/play?videoId=2"
private Long parseVideoId(String images) {
    String marker = "/video/play?videoId=";
    int idx = images.indexOf(marker);
    // ... 提取数字部分
}
```

---

## 五、前端调用逻辑

```javascript
async function onAiSummaryClick(blogId) {
  // 1. POST 触发
  const res = await fetch(`/api/blog/summary/${blogId}`, { method: 'POST' });
  const json = await res.json();

  if (!json.success) {
    showError(json.message);
    return;
  }

  if (json.data.status === 'done') {
    showSummary(json.data.summary);           // 缓存命中，直接展示
    return;
  }

  // 2. status=processing，开始轮询
  showLoading();
  const timer = setInterval(async () => {
    const poll = await fetch(`/api/blog/summary/${blogId}`);
    const data = (await poll.json()).data;

    if (!data || data.status === 'processing') return;   // 继续等

    clearInterval(timer);
    hideLoading();
    if (data.status === 'done') showSummary(data.summary);
  }, 2000);  // 每 2 秒轮询
}
```

---

## 六、后端实现

### 6.1 线程池配置

[AsyncConfig.java](src/main/java/com/syit/hmdp/config/AsyncConfig.java)：

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean("summaryExecutor")
    public Executor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        // 队列满时由调用线程执行（避免丢失任务）
        executor.setRejectedExecutionHandler(new CallerRunsPolicy());
        executor.setThreadNamePrefix("summary-");
        executor.initialize();
        return executor;
    }
}
```

### 6.2 服务层

**IVideoSummaryService** 两个方法：

| 方法 | 说明 |
|------|------|
| `startSummary(blogId)` | 查缓存 → 校验 → 插入 status=0 → 提交异步任务 → 立返 `processing` |
| `getSummary(blogId)` | 查表 → 返回 `processing` / `done` / `null` |

**VideoSummaryServiceImpl** 负责同步的校验和状态管理，**SummaryProcessor**（`@Async("summaryExecutor")`）负责异步的下载→提取→ASR→LLM→写库。

**分工**：

| 类 | 线程 | 职责 |
|------|------|------|
| `VideoSummaryServiceImpl` | HTTP 线程 | 查缓存、校验、创建 pending 记录、提交异步任务（< 20ms） |
| `SummaryProcessor` | Worker 线程 | 下载视频→FFmpeg→ASR→LLM→UPDATE status=1（15-40s） |

### 6.3 并发控制

UNIQUE 约束 (`blog_id`) + status 状态机，天然防重：

```
请求1: SELECT → 无记录 → INSERT(blog_id=26, status=0) 成功 → 异步处理
请求2: SELECT → status=0 → 返回 {status:"processing"}（不再启动异步）
```

失败重试：再次 POST 时若 status=2，删旧记录后重新创建。

### 6.4 ASR 服务

[AsrServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AsrServiceImpl.java)：

手动构建 multipart 请求体（避免 Spring 框架层 multipart 边界丢失问题）：

```java
POST https://api.siliconflow.cn/v1/audio/transcriptions
Content-Type: multipart/form-data; boundary=----xxx
Authorization: Bearer {api-key}

----xxx
Content-Disposition: form-data; name="file"; filename="audio.mp3"
Content-Type: audio/mpeg

{音频字节}
----xxx
Content-Disposition: form-data; name="model"

FunAudioLLM/SenseVoiceSmall
----xxx--
```

### 6.5 AI 总结

复用 `AiServiceImpl` 的无 Tool ChatClient：

```java
chatClientWithoutTools.prompt().user(prompt).call().content()
```

---

## 七、FFmpeg 音频提取

```java
ProcessBuilder pb = new ProcessBuilder(
    ffmpegPath, "-i", videoFile.getAbsolutePath(),
    "-vn",                           // 丢弃视频流
    "-acodec", "libmp3lame",         // MP3 编码
    "-q:a", "2",                     // 高质量
    "-y",                            // 覆盖已有文件
    audioFile.getAbsolutePath()
);
pb.redirectErrorStream(true);
Process process = pb.start();
int exitCode = process.waitFor();    // 阻塞等待
```

临时文件在 `finally` 块中用 `FileUtil.del()` 清理。

---

## 八、配置

[application.yaml](src/main/resources/application.yaml)：

```yaml
ffmpeg:
  path: ffmpeg

siliconflow:
  asr:
    model: FunAudioLLM/SenseVoiceSmall
```

复用配置：

| 配置 | 说明 |
|------|------|
| `spring.ai.openai.api-key` | ASR + LLM 共用 API Key |
| `spring.ai.openai.base-url` | `https://api.siliconflow.cn` |
| `spring.ai.openai.chat.options.model` | `Qwen/Qwen3-8B` |
| `minio.*` | 视频下载 |

---

## 九、文件清单

### 新建文件（8 个）

| # | 文件 | 说明 |
|---|---|---|
| 1 | `entity/BlogSummary.java` | 总结实体 |
| 2 | `mapper/BlogSummaryMapper.java` | MyBatis-Plus Mapper |
| 3 | `service/IAsrService.java` | ASR 服务接口 |
| 4 | `service/impl/AsrServiceImpl.java` | SenseVoice 手动 multipart 调用 |
| 5 | `service/IVideoSummaryService.java` | 总结服务接口 |
| 6 | `service/impl/VideoSummaryServiceImpl.java` | 同步校验+状态管理 |
| 7 | `service/impl/SummaryProcessor.java` | `@Async` 异步处理管线 |
| 8 | `config/AsyncConfig.java` | `@EnableAsync` + 线程池配置 |

### 修改文件（4 个）

| 文件 | 改动 |
|------|------|
| `service/IAiService.java` | 新增 `summarize(String prompt)` |
| `service/impl/AiServiceImpl.java` | 新增 `chatClientWithoutTools` |
| `controller/BlogController.java` | 新增 `POST/GET /blog/summary/{id}` |
| `application.yaml` | 新增 `ffmpeg.path` + `siliconflow.asr.model` |

---

## 十、启动与验证

### 10.1 前置条件

1. MySQL 建表：
   ```sql
   CREATE TABLE tb_blog_summary (
     id BIGINT PRIMARY KEY AUTO_INCREMENT,
     blog_id BIGINT NOT NULL UNIQUE,
     transcript MEDIUMTEXT NULL,
     summary TEXT NULL,
     model VARCHAR(64) NULL,
     status TINYINT DEFAULT 0,
     error_msg VARCHAR(500) NULL,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP
   );
   ```
2. 安装 FFmpeg：`ffmpeg -version`
3. MinIO 可访问，`video_file` 表有数据
4. 某 Blog 的 `images` 字段含 `/api/video/play?videoId=N`
5. SiliconFlow API Key 有效

### 10.2 验证步骤

```bash
# 1. 启动
mvn spring-boot:run

# 2. 触发生成
curl -X POST "http://localhost:8081/blog/summary/26" \
  -H "Authorization: <token>"
# → {"success":true,"data":{"status":"processing"}}

# 3. 轮询（隔几秒）
curl "http://localhost:8081/blog/summary/26" \
  -H "Authorization: <token>"
# → {"success":true,"data":{"status":"processing"}}    ← 处理中
# → {"success":true,"data":{"summary":"...","status":"done"}}  ← 完成

# 4. 再次 POST（缓存命中）
curl -X POST "http://localhost:8081/blog/summary/26" \
  -H "Authorization: <token>"
# → {"success":true,"data":{"summary":"...","status":"done"}}
```

---

## 十一、错误处理

| 场景 | 响应 |
|------|------|
| 博客不存在 | `{"success":false,"message":"博客不存在"}` |
| 无关联视频 | `{"success":false,"message":"该博客没有关联视频"}` |
| 视频不存在 | `{"success":false,"message":"视频不存在"}` |
| FFmpeg 失败 | status=2, `error_msg` 记录具体错误 |
| ASR 失败 | status=2, `error_msg` 记录 API 错误 |
| LLM 失败 | status=2, `error_msg` 记录异常信息 |
| 并发重复请求 | status=0 时返回 `processing`，不重复启动 |

---

## 十二、性能

| 环节 | 耗时 | 可缓存 |
|------|------|--------|
| POST 触发 | < 20ms | — |
| GET 轮询 | < 5ms | — |
| 下载视频 | 1-5s | 否 |
| FFmpeg | 3-10s | 否 |
| ASR | 5-15s | 否 |
| LLM | 3-8s | 否 |
| 异步总计 | 15-40s | — |
| **缓存命中** | **< 5ms** | `blog_id` UNIQUE |

HTTP 线程占用 < 20ms，Worker 线程 2-4 个，队列 10。极端情况（队列满 + 池满）由 `CallerRunsPolicy` 保底——调用线程自行执行，不丢任务。
