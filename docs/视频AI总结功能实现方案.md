# 视频 AI 总结功能实现方案

## 一、整体架构

```
浏览器                         后端 (:8081)                    MinIO      SiliconFlow API
  │                             │                              │              │
  │  POST /blog/{id}/summarize  │                              │              │
  ├────────────────────────────>│                              │              │
  │                             │  1. 查 Blog（解析 images 中  │              │
  │                             │     的 videoId）              │              │
  │                             │  2. 查 BlogSummary 缓存      │              │
  │                             │     (命中 → 直接返回)         │              │
  │                             │                              │              │
  │                             │  3. 查 VideoFile (objectKey) │              │
  │                             │                              │              │
  │                             │  4. 下载视频到临时文件        │              │
  │                             ├─────────────────────────────>│              │
  │                             │<──── 视频文件 ────────────────│              │
  │                             │                              │              │
  │                             │  5. FFmpeg 提取音频          │              │
  │                             │  ffmpeg -i video.mp4         │              │
  │                             │    -vn -acodec libmp3lame    │              │
  │                             │    -q:a 2 -y audio.mp3       │              │
  │                             │                              │              │
  │                             │  6. SenseVoice ASR 转录      │              │
  │                             ├──────────────────────────────┼─────────────>│
  │                             │<─────────────────────────────┼──────────────│
  │                             │     (返回转录文本)            │              │
  │                             │                              │              │
  │                             │  7. 拼 prompt                │              │
  │                             │  (标题+正文+转录文本)          │              │
  │                             │                              │              │
  │                             │  8. Qwen3-8B 生成总结         │              │
  │                             ├──────────────────────────────┼─────────────>│
  │                             │<─────────────────────────────┼──────────────│
  │                             │     (返回总结文本)            │              │
  │                             │                              │              │
  │                             │  9. INSERT tb_blog_summary   │              │
  │                             │                              │              │
  │<── { summary, transcript } ─┤                              │              │
```

核心技术栈：**Spring Boot 3.5 + Spring AI 1.0.3 + FFmpeg + SiliconFlow SenseVoice + Qwen3-8B**

---

## 二、数据流详解

### 2.1 完整流程

```
POST /blog/{id}/summarize
  │
  ├─ 1. 查缓存
  │    SELECT * FROM tb_blog_summary WHERE blog_id = ?
  │    命中 → 直接返回已有总结（秒返）
  │
  ├─ 2. 查 Blog
  │    SELECT * FROM tb_blog WHERE id = ?
  │    从 images 字段解析 videoId（格式：/api/video/play?videoId=2）
  │    无 videoId → 返回 "该博客没有关联视频"
  │
  ├─ 3. 查 VideoFile
  │    SELECT * FROM video_file WHERE id = videoId
  │    获取 MinIO objectKey
  │
  ├─ 4. 下载视频
  │    amazonS3.getObject(bucket, objectKey, tempFile)
  │    → 写入临时目录（Files.createTempDirectory）
  │
  ├─ 5. FFmpeg 提取音频
  │    ProcessBuilder: ffmpeg -i video.mp4 -vn -acodec libmp3lame -q:a 2 -y audio.mp3
  │    阻塞等待 → 校验 exitCode == 0
  │
  ├─ 6. ASR 转录
  │    POST https://api.siliconflow.cn/v1/audio/transcriptions
  │    multipart/form-data: file=audio.mp3, model=FunAudioLLM/SenseVoiceSmall
  │    响应: {"text": "转录文本..."}
  │
  ├─ 7. 构建 prompt
  │    "你是一个视频内容总结助手。请根据以下博客信息和视频语音转录文本，
  │     生成一段简洁的视频内容总结（200字以内）。
  │     【博客标题】{title}
  │     【博客正文】{content}
  │     【视频语音转录】{transcript}
  │     请生成总结："
  │
  ├─ 8. LLM 总结
  │    chatClientWithoutTools.prompt().user(prompt).call().content()
  │    → 返回总结文本（无 Tool 绑定，纯文本生成）
  │
  ├─ 9. 写入缓存
  │    INSERT INTO tb_blog_summary (blog_id, transcript, summary, model, created_at)
  │    VALUES (?, ?, ?, 'Qwen/Qwen3-8B', NOW())
  │
  └──> 返回 { id, blogId, transcript, summary, model, createdAt }
```

### 2.2 videoId 的解析方式

Blog 的 `images` 字段存储视频播放路径，格式为 `/api/video/play?videoId=2`。后端解析 videoId：

```java
private Long parseVideoId(String images) {
    if (images == null || images.isEmpty()) return null;
    String marker = "/video/play?videoId=";
    int idx = images.indexOf(marker);
    if (idx < 0) return null;
    String numStr = images.substring(idx + marker.length());
    int ampIdx = numStr.indexOf('&');
    if (ampIdx > 0) numStr = numStr.substring(0, ampIdx);
    try {
        return Long.parseLong(numStr.trim());
    } catch (NumberFormatException e) {
        return null;
    }
}
```

---

## 三、数据模型

### 3.1 Blog 表改动

```sql
ALTER TABLE tb_blog ADD COLUMN video_id BIGINT NULL COMMENT '关联视频ID';
```

Blog 实体新增字段：

```java
/**
 * 关联的视频ID
 */
private Long videoId;
```

> **注意**：实际 videoId 通过解析 `images` 字段中的 `/api/video/play?videoId=N` URL 获取，`video_id` 列为辅助索引用途。

### 3.2 tb_blog_summary（总结存储表）

```sql
CREATE TABLE tb_blog_summary (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  blog_id     BIGINT NOT NULL UNIQUE COMMENT '博客ID（UNIQUE，保证一博客只生成一次）',
  transcript  MEDIUMTEXT NULL COMMENT '语音转录文本（可能很长）',
  summary     TEXT NOT NULL COMMENT 'AI总结',
  model       VARCHAR(64) NULL COMMENT '使用的AI模型',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_blog_id (blog_id)
);
```

| 字段 | 说明 |
|------|------|
| `blog_id` | 博客 ID，UNIQUE 约束实现缓存——同一博客多次请求直接返回已有记录 |
| `transcript` | ASR 转录原始文本，MEDIUMTEXT 容纳长文本 |
| `summary` | AI 生成的总结文本 |
| `model` | 记录使用的模型（如 `Qwen/Qwen3-8B`），便于后续切换模型时识别来源 |

### 3.3 实体类

[BlogSummary.java](src/main/java/com/syit/hmdp/entity/BlogSummary.java)：

```java
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog_summary")
public class BlogSummary implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long blogId;
    private String transcript;
    private String summary;
    private String model;
    private LocalDateTime createdAt;
}
```

### 3.4 依赖复用

VideoFile 实体和 VideoFileMapper 复用视频上传功能（[视频上传功能实现方案](docs/视频上传功能实现方案.md)）中已创建的表和代码，不重复创建。

---

## 四、API 接口

### 4.1 POST /blog/{id}/summarize

**请求**：`POST /blog/5/summarize`（需携带 token）

**首次响应（同步等待几十秒）**：
```json
{
  "success": true,
  "data": {
    "id": 1,
    "blogId": 5,
    "transcript": "大家好，今天带大家探店...（完整转录文本）",
    "summary": "视频展示了某餐厅的环境和菜品，主播推荐了招牌菜A和甜品B，整体评价较好，适合情侣约会。",
    "model": "Qwen/Qwen3-8B",
    "createdAt": "2026-05-05T15:30:00"
  }
}
```

**缓存命中（秒返）**：
```json
{
  "success": true,
  "data": {
    "id": 1,
    "blogId": 5,
    "transcript": "大家好，今天带大家探店...",
    "summary": "视频展示了某餐厅的环境...",
    "model": "Qwen/Qwen3-8B",
    "createdAt": "2026-05-05T15:30:00"
  }
}
```

**异常响应**：
```json
{ "success": false, "message": "该博客没有关联视频" }
{ "success": false, "message": "视频不存在" }
```

### 4.2 前端调用方式

前端在博客详情页（`GET /blog/{id}`）返回的数据中检查 `images` 字段是否包含 `/video/play?videoId=` 来判断是否显示"AI 总结"按钮。若有，点击按钮调用 `POST /blog/{id}/summarize`。

---

## 五、服务层实现

### 5.1 接口定义

[IVideoSummaryService.java](src/main/java/com/syit/hmdp/service/IVideoSummaryService.java)：

```java
public interface IVideoSummaryService {
    Result summarizeBlog(Long blogId);
}
```

### 5.2 核心编排器

[VideoSummaryServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/VideoSummaryServiceImpl.java) 是整个功能的编排器，负责串联所有步骤。

**依赖注入**：

| 依赖 | 用途 |
|------|------|
| `IBlogService` | 查询 Blog 实体 |
| `VideoFileMapper` | 根据 videoId 查询 MinIO objectKey |
| `BlogSummaryMapper` | 读写 tb_blog_summary 缓存 |
| `IAsrService` | 调用 SenseVoice ASR 转录 |
| `IAiService` | 调用 LLM 生成总结 |
| `AmazonS3` | 从 MinIO 下载视频文件 |

**配置项**：

| 配置 | 说明 |
|------|------|
| `${minio.bucket}` | MinIO bucket 名称 |
| `${ffmpeg.path}` | FFmpeg 可执行文件路径（默认 `ffmpeg`） |
| `${spring.ai.openai.chat.options.model}` | LLM 模型名，存入 BlogSummary.model |

**错误处理**：各环节失败抛出 RuntimeException，由 `WebExceptionAdvice` 统一捕获。

### 5.3 临时文件管理

```java
Path tempDir = Files.createTempDirectory("video-summary-");
try {
    // 下载视频 → 提取音频 → ASR → LLM
} finally {
    FileUtil.del(tempDir.toFile());  // 无论成功失败都清理
}
```

下载的视频和提取的音频都写入临时目录，`finally` 块使用 Hutool 的 `FileUtil.del()` 递归删除。

---

## 六、ASR 服务（语音转文字）

### 6.1 接口

[IAsrService.java](src/main/java/com/syit/hmdp/service/IAsrService.java)：

```java
public interface IAsrService {
    String transcribe(java.io.File audioFile);
}
```

### 6.2 实现

[AsrServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AsrServiceImpl.java)：

- **请求方式**：`POST https://api.siliconflow.cn/v1/audio/transcriptions`
- **请求格式**：`multipart/form-data`
- **参数**：`file`（音频文件）+ `model`（模型名，如 `FunAudioLLM/SenseVoiceSmall`）
- **鉴权**：`Authorization: Bearer {spring.ai.openai.api-key}`
- **响应解析**：从 `{"text": "转录文本"}` 中提取 text 字段
- **HTTP 客户端**：Spring `RestTemplate` + `FileSystemResource`

```java
MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
body.add("file", new FileSystemResource(audioFile));
body.add("model", asrModel);

HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.MULTIPART_FORM_DATA);
headers.setBearerAuth(apiKey);

ResponseEntity<Map> response = restTemplate.exchange(
    "https://api.siliconflow.cn/v1/audio/transcriptions",
    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
```

### 6.3 模型选型

选用 **SenseVoiceSmall**（FunAudioLLM/SenseVoiceSmall），理由：
- SiliconFlow 免费托管，无需自部署
- 中文识别准确率高
- API 兼容 OpenAI Whisper 格式

---

## 七、AI 总结服务

### 7.1 与聊天服务的区别

| 维度 | 聊天服务（chat） | 总结服务（summarize） |
|------|-----------------|---------------------|
| 用途 | 多轮对话，支持 Tool 调用 | 单次文本生成，无 Tool |
| 历史 | 需要历史消息上下文 | 不需要 |
| Tools | 绑定 BlogTools（查博客等） | 不绑定任何 Tool |
| Prompt | 系统 prompt + 历史 + 用户消息 | 纯 prompt 文本 |
| ChatClient | `chatClient`（defaultTools(blogTools)） | `chatClientWithoutTools`（无 Tools） |

### 7.2 实现

[AiServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AiServiceImpl.java)：

```java
@Service
public class AiServiceImpl implements IAiService {

    private final ChatClient chatClient;           // 带 Tool 的，给聊天用
    private final ChatClient chatClientWithoutTools; // 不带 Tool，给总结用
    private final String systemPrompt;

    public AiServiceImpl(ChatClient.Builder chatClientBuilder,
                         @Value("${chat.system-prompt}") String systemPrompt,
                         BlogTools blogTools) {
        this.chatClient = chatClientBuilder
                .defaultTools(blogTools)
                .build();
        this.chatClientWithoutTools = chatClientBuilder.build();
        this.systemPrompt = systemPrompt;
    }

    // 聊天（多轮 + Tool）
    @Override
    public String chat(Long userId, String userMessage, List<ChatMessage> history) {
        // ... 构造 messages（SystemMessage + 历史 + UserMessage）
        return chatClient.prompt().messages(messages).call().content();
    }

    // 总结（单次 + 无 Tool）
    @Override
    public String summarize(String prompt) {
        return chatClientWithoutTools.prompt().user(prompt).call().content();
    }
}
```

**为什么需要两个 ChatClient？**

聊天场景需要 Tool 调用（查博客、查评论等），总结场景不需要。如果共用同一个 Builder 构建的不同 ChatClient，Spring AI 每次 `chatClientBuilder.build()` 返回新的 ChatClient 实例，可以有不同的 defaultTools 配置，互不影响。

---

## 八、FFmpeg 音频提取

### 8.1 调用方式

```java
ProcessBuilder pb = new ProcessBuilder(
    ffmpegPath,
    "-i", videoFile.getAbsolutePath(),
    "-vn",                         // 丢弃视频流
    "-acodec", "libmp3lame",       // 编码为 MP3
    "-q:a", "2",                   // 音频质量（0-9，2 为高质量）
    "-y",                          // 覆盖已有文件
    audioFile.getAbsolutePath()
);
pb.redirectErrorStream(true);
Process process = pb.start();
int exitCode = process.waitFor();  // 阻塞等待完成
```

### 8.2 参数说明

| 参数 | 含义 |
|------|------|
| `-vn` | 不输出视频流 |
| `-acodec libmp3lame` | 使用 LAME 编码器输出 MP3 |
| `-q:a 2` | 音频质量，2=高质量（VBR ~190kbps），适合 ASR |
| `-y` | 覆盖已有输出文件，避免交互提示 |

### 8.3 前置条件

- 服务器需要安装 FFmpeg
- 验证：`ffmpeg -version` 有正常输出
- 配置 `ffmpeg.path` 指向 ffmpeg 可执行文件路径

---

## 九、配置

### 9.1 新增配置项

[application.yaml](src/main/resources/application.yaml) 新增：

```yaml
ffmpeg:
  path: ffmpeg                      # FFmpeg 可执行文件路径

siliconflow:
  asr:
    model: FunAudioLLM/SenseVoiceSmall  # ASR 模型
```

### 9.2 复用配置

| 配置 | 复用来源 | 说明 |
|------|---------|------|
| `spring.ai.openai.api-key` | 已有 | SiliconFlow API Key（ASR + LLM 共用） |
| `spring.ai.openai.base-url` | 已有 | `https://api.siliconflow.cn/`（LLM 用） |
| `spring.ai.openai.chat.options.model` | 已有 | `Qwen/Qwen3-8B`（LLM 模型，存入总结记录） |
| `minio.*` | 已有（视频上传） | MinIO 连接信息，下载视频用 |

---

## 十、文件清单

### 新建文件（6 个）

| # | 文件 | 说明 |
|---|---|---|
| 1 | [BlogSummary.java](src/main/java/com/syit/hmdp/entity/BlogSummary.java) | 总结实体，映射 tb_blog_summary |
| 2 | [BlogSummaryMapper.java](src/main/java/com/syit/hmdp/mapper/BlogSummaryMapper.java) | MyBatis-Plus Mapper |
| 3 | [IAsrService.java](src/main/java/com/syit/hmdp/service/IAsrService.java) | ASR 服务接口 |
| 4 | [AsrServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AsrServiceImpl.java) | SenseVoice ASR HTTP 调用 |
| 5 | [IVideoSummaryService.java](src/main/java/com/syit/hmdp/service/IVideoSummaryService.java) | 总结服务接口 |
| 6 | [VideoSummaryServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/VideoSummaryServiceImpl.java) | 核心编排器 |

### 修改文件（4 个）

| 文件 | 改动 |
|------|------|
| [Blog.java](src/main/java/com/syit/hmdp/entity/Blog.java) | 新增 `videoId` 字段 |
| [IAiService.java](src/main/java/com/syit/hmdp/service/IAiService.java) | 新增 `summarize(String prompt)` 方法 |
| [AiServiceImpl.java](src/main/java/com/syit/hmdp/service/impl/AiServiceImpl.java) | 新增 `chatClientWithoutTools` + 实现 `summarize` |
| [BlogController.java](src/main/java/com/syit/hmdp/controller/BlogController.java) | 新增 `POST /blog/{id}/summarize` 端点 |
| [application.yaml](src/main/resources/application.yaml) | 新增 `ffmpeg.path` + `siliconflow.asr.model` |

---

## 十一、启动与验证

### 11.1 前置条件

1. MySQL 中执行：
   ```sql
   ALTER TABLE tb_blog ADD COLUMN video_id BIGINT NULL;
   CREATE TABLE tb_blog_summary (
     id BIGINT PRIMARY KEY AUTO_INCREMENT,
     blog_id BIGINT NOT NULL UNIQUE,
     transcript MEDIUMTEXT NULL,
     summary TEXT NOT NULL,
     model VARCHAR(64) NULL,
     created_at DATETIME DEFAULT CURRENT_TIMESTAMP
   );
   ```
2. 服务器安装 FFmpeg：`ffmpeg -version` 可正常输出
3. MinIO 已启动，`video_file` 表有测试数据
4. 某条 Blog 的 `images` 字段包含 `/api/video/play?videoId=N`（N 对应有效的 video_file.id）
5. SiliconFlow API Key 有效（ASR + LLM 共用）

### 11.2 验证步骤

```bash
# 1. 启动应用
mvn spring-boot:run

# 2. 确认博客关联了视频（查询 images 字段）
curl "http://localhost:8081/blog/5" -H "Authorization: <token>"
# 检查 images 字段是否包含 /api/video/play?videoId=2

# 3. 首次调用总结（等待几十秒，走完整管线）
curl -X POST "http://localhost:8081/blog/5/summarize" \
  -H "Authorization: <token>"
# → { "success": true, "data": { "summary": "...", "transcript": "...", ... } }

# 4. 再次调用（秒返，命中缓存）
curl -X POST "http://localhost:8081/blog/5/summarize" \
  -H "Authorization: <token>"
# → 直接返回同上结果

# 5. 验证数据库有记录
# SELECT * FROM tb_blog_summary WHERE blog_id = 5;
```

---

## 十二、与现有能力的关系

| 能力 | 复用方式 |
|------|----------|
| Token 鉴权 | 复用 `RefreshTokenInterceptor` + `LoginInterceptor` |
| 视频存储 | 复用 `video_file` 表 + `VideoFileMapper` + MinIO AmazonS3 |
| AI 调用 | 复用 Spring AI ChatClient.Builder（新建无 Tool 实例） |
| API Key | 复用 `spring.ai.openai.api-key`（SiliconFlow 的 ASR 和 LLM 共用同一个 Key） |
| 用户信息 | `UserHolder.getUser()` |
| 响应格式 | `Result.ok(data)` / `Result.fail(msg)` |
| 全局异常处理 | `WebExceptionAdvice` 捕获未处理异常 |
| 临时文件清理 | Hutool `FileUtil.del()` |

---

## 十三、错误处理

| 错误场景 | 错误信息 | HTTP 状态 |
|----------|---------|-----------|
| 博客不存在 | "博客不存在" | 200（业务异常） |
| images 不含 videoId | "该博客没有关联视频" | 200（业务异常） |
| VideoFile 不存在 | "视频不存在" | 200（业务异常） |
| MinIO 下载失败 | RuntimeException | 500（WebExceptionAdvice 统一处理） |
| FFmpeg 未安装或失败 | "FFmpeg 提取音频失败" | 500 |
| ASR API 失败 | "ASR 调用失败" | 500 |
| LLM API 失败 | RuntimeException（Spring AI 抛出） | 500 |

所有 RuntimeException 由 `WebExceptionAdvice` 统一捕获，返回 `{ "success": false, "message": "..." }` 格式。

---

## 十四、性能考量

| 环节 | 耗时（估算） | 是否可缓存 |
|------|-------------|-----------|
| 查 Blog + VideoFile | < 10ms | 否（但可忽略） |
| MinIO 下载视频 | 1-5s（取决于文件大小） | 否（后可考虑本地缓存热视频） |
| FFmpeg 提取音频 | 3-10s | 否 |
| SenseVoice ASR | 5-15s | 是（存入 tb_blog_summary） |
| LLM 总结 | 3-8s | 是（存入 tb_blog_summary） |
| **首次总计** | **~15-40s** | — |
| **缓存命中** | **< 20ms** | tb_blog_summary.blog_id UNIQUE |

关键优化：`tb_blog_summary.blog_id` 的 UNIQUE 约束确保同一博客只处理一次，后续请求直接返回缓存。
