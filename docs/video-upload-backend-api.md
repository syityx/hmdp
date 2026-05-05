# 视频上传后端接口文档（MinIO 分片上传 + 断点续传 + MD5 去重）

## 1. 目标与约束

- 图片上传保持现有 `POST /upload/blog` 不变
- 视频走 MinIO 分片上传，后端不落盘，全程透传
- 支持 MD5 秒传（已传过的视频直接返回 URL）
- 支持断点续传（页面关闭重开后跳过已传分片）
- user 隔离：uploadId 绑定 userId，存储路径按 userId 分目录
- **MinIO bucket 设为 private**，视频不能通过 MinIO URL 直接访问，必须走 `/video/play` 接口获取预签名 URL（10 分钟过期），防止 URL 伪造和遍历

---

## 2. 数据表

### 2.1 video_file（文件记录，用于 MD5 去重）

```sql
CREATE TABLE video_file (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  md5         VARCHAR(32)  NOT NULL UNIQUE,
  object_key  VARCHAR(256) NOT NULL COMMENT 'MinIO 中的对象路径',
  file_size   BIGINT       NOT NULL COMMENT '字节',
  user_id     BIGINT       NOT NULL,
  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_id (user_id)
);
```

### 2.2 video_upload（上传会话，用于断点续传和鉴权）

```sql
CREATE TABLE video_upload (
  upload_id    VARCHAR(64) PRIMARY KEY COMMENT 'MinIO 返回的 uploadId，同时作为本表主键',
  user_id      BIGINT       NOT NULL,
  object_key   VARCHAR(256) NOT NULL COMMENT 'MinIO 对象路径，init 时确定',
  md5          VARCHAR(32)  NOT NULL,
  filename     VARCHAR(256) NOT NULL COMMENT '原始文件名',
  file_size    BIGINT       NOT NULL,
  total_chunks INT          NOT NULL COMMENT '总片数',
  content_type VARCHAR(64)  DEFAULT 'video/mp4',
  status       TINYINT      DEFAULT 0 COMMENT '0=上传中 1=已完成 2=已取消',
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_id (user_id)
);
```

---

## 3. 接口总览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/upload/video/check?md5=xxx` | MD5 去重查询（秒传） |
| POST | `/upload/video/init` | 初始化分片上传，返回 uploadId |
| POST | `/upload/video/chunk` | 上传单个分片，返回 ETag |
| POST | `/upload/video/complete` | 通知 MinIO 合并，校验 MD5 |
| GET | `/upload/video/progress?uploadId=xxx` | 查询已上传分片列表（续传用） |
| GET | `/upload/video/cancel?uploadId=xxx` | 取消上传，清理 MinIO 临时分片 |
| GET | `/video/play?videoId=xxx` | 获取预签名播放 URL（私有 bucket 访问入口） |

所有接口均需 token 鉴权（`Authorization` 请求头），与现有接口一致。

---

## 4. 接口详情

### 4.1 GET `/upload/video/check?md5=xxx`

**说明**：前端计算文件 MD5 后先调此接口。如果 `video_file` 表中已存在该 MD5，直接返回已有 URL，前端无需上传（秒传）。

**请求**：
```
GET /api/upload/video/check?md5=d41d8cd98f00b204e9800998ecf8427e
Authorization: <token>
```

**响应 - 不存在**：
```json
{
  "success": true,
  "data": { "exists": false }
}
```

**响应 - 已存在（秒传）**：
```json
{
  "success": true,
  "data": {
    "exists": true,
    "videoId": 42,
    "url": "/api/video/play?videoId=42"
  }
}
```

**后端逻辑**：
1. 从 token 获取 currentUserId（复用现有 `/user/me` 的 token 解析）
2. 查 `video_file` 表 `WHERE md5 = ?`
3. 有记录 → 返回 videoId + 播放 URL（前端通过 videoId 插值到播放接口）
4. 无记录 → 返回 `{ exists: false }`

**注意**：这里返回的 url 是私密接口路径（走 token 鉴权），不是 MinIO 裸 URL。

---

### 4.2 POST `/upload/video/init`

**说明**：初始化一个 MinIO Multipart Upload 会话。后端调 MinIO SDK 创建分片上传，返回 uploadId。

**请求**：
```json
Content-Type: application/json

{
  "filename": "video.mp4",
  "fileSize": 52428800,
  "chunkSize": 5242880,
  "totalChunks": 10,
  "md5": "d41d8cd98f00b204e9800998ecf8427e",
  "contentType": "video/mp4"
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "uploadId": "minio-uuid-xxxx",
    "objectKey": "videos/1010/20260505-uuid.mp4"
  }
}
```

**后端逻辑**：
```
1. token → currentUserId
2. 再次查 video_file 表校验 MD5（防止并发重复）
3. 构造 objectKey = "videos/{userId}/{uuid}.{ext}"
4. 调 MinIO SDK：
     createMultipartUpload(bucket, objectKey, contentType)
     → 返回 uploadId
5. INSERT INTO video_upload(upload_id, user_id, object_key, md5, filename,
                            file_size, total_chunks, content_type)
6. 返回 uploadId 和 objectKey
```

---

### 4.3 POST `/upload/video/chunk`

**说明**：上传一个分片。后端收到后直接 `uploadPart` 转发到 MinIO，不落盘。返回 ETag 给前端保存。

**请求**（multipart/form-data）：
```
POST /api/upload/video/chunk
Authorization: <token>
Content-Type: multipart/form-data; boundary=----boundary

------boundary
Content-Disposition: form-data; name="uploadId"
uuid-xxxx
------boundary
Content-Disposition: form-data; name="chunkIndex"
0
------boundary
Content-Disposition: form-data; name="chunk"; filename="blob"
Content-Type: application/octet-stream
<binary data 5MB>
------boundary--
```

**响应**：
```json
{
  "success": true,
  "data": {
    "chunkIndex": 0,
    "etag": "\"abc123def456\""
  }
}
```

**后端逻辑**：
```
1. token → currentUserId
2. 查 video_upload WHERE upload_id = ?
3. 校验 upload.user_id === currentUserId   （否则 403）
4. 校验 upload.status === 0                  （否则 400 "上传已结束"）
5. 校验 chunkIndex 在 0 ~ totalChunks-1 范围
6. 从 multipart 中读取 chunk 字节流
7. 调 MinIO SDK：
     uploadPart(bucket, objectKey, uploadId, partNumber=chunkIndex+1, inputStream, size)
     → 返回 ETag
8. 返回 { chunkIndex, etag }
```

**注意**：
- MinIO 的 partNumber 从 1 开始，前端 chunkIndex 从 0 开始，后端需 +1
- ETag 包含双引号，原样返回给前端即可
- 后端不存分片在本地，读取完 InputStream 即丢弃

---

### 4.4 POST `/upload/video/complete`

**说明**：前端全部传完后调用，后端调 MinIO 合并分片，校验合并后文件的 MD5，写入 `video_file` 表。

**请求**：
```json
Content-Type: application/json

{
  "uploadId": "uuid-xxxx",
  "parts": [
    { "partNumber": 1, "etag": "\"abc123\"" },
    { "partNumber": 2, "etag": "\"def456\"" },
    ...
  ]
}
```

**响应**：
```json
{
  "success": true,
  "data": {
    "videoId": 42,
    "url": "/api/video/play?videoId=42"
  }
}
```

**后端逻辑**：
```
1. token → currentUserId
2. 查 video_upload，校验 upload.user_id === currentUserId
3. 校验 parts.length === upload.total_chunks    （否则 400 "分片数不匹配"）
4. 校验每个 partNumber 都有 etag                 （否则 400）
5. 调 MinIO SDK：
     completeMultipartUpload(bucket, objectKey, uploadId, parts)
     → MinIO 内部合并分片
6. 调 MinIO SDK 获取合并后文件元信息：
     statObject(bucket, objectKey)
     → 可以得到合并后文件的 ETag（MinIO 对完整文件的 ETag 即 MD5，前提是未加密、
        分片小于 8MB 时需注意：MinIO 对小文件的 ETag 不是 MD5）
7. MD5 校验（两种策略选其一）：

   策略 A：信任 MinIO ETag（简单）
     - 分片大小 ≥ 8MB 时 MinIO ETag 就是文件的 MD5
     - 与前端传入的 md5 比对，不一致则 abort 并返回错误

   策略 B：后端下载文件计算 MD5（准确但慢）
     - 调 MinIO getObject 流式读取，边读边算 MD5
     - 与前端传入的 md5 比对

   推荐策略 A，分片设为 8MB 以上即可利用 MinIO 的 ETag 校验

8. INSERT INTO video_file(md5, object_key, file_size, user_id)
   → 拿到自增主键 id 作为 videoId
9. UPDATE video_upload SET status = 1
10. 返回 videoId + 播放 URL
```

**异常处理**：
- MD5 校验失败 → 调 MinIO `abortMultipartUpload` 清理分片 → 标记 `status = 2`
- MinIO 合并失败 → 返回错误，保留分片让前端重试

---

### 4.5 GET `/upload/video/progress?uploadId=xxx`

**说明**：前端重新打开页面时调用，查询哪些分片已上传，以便跳过它们。

**请求**：
```
GET /api/upload/video/progress?uploadId=uuid-xxxx
Authorization: <token>
```

**响应**：
```json
{
  "success": true,
  "data": {
    "objectKey": "videos/1010/xxx.mp4",
    "totalChunks": 10,
    "completedParts": [
      { "partNumber": 1, "etag": "\"abc123\"" },
      { "partNumber": 3, "etag": "\"ghi789\"" },
      { "partNumber": 5, "etag": "\"mno345\"" }
    ]
  }
}
```

**后端逻辑**：
```
1. token → currentUserId
2. 查 video_upload，校验 upload.user_id === currentUserId
3. 调 MinIO SDK：
     listParts(bucket, objectKey, uploadId)
     → 返回 [{partNumber:1, etag:"..."}, ...]
4. 组装返回
```

---

### 4.6 GET `/upload/video/cancel?uploadId=xxx`

**说明**：用户放弃上传，清理 MinIO 临时分片。

**请求**：
```
GET /api/upload/video/cancel?uploadId=uuid-xxxx
Authorization: <token>
```

**响应**：
```json
{ "success": true }
```

**后端逻辑**：
```
1. token → currentUserId
2. 查 video_upload，校验 upload.user_id === currentUserId
3. 调 MinIO SDK：abortMultipartUpload(bucket, objectKey, uploadId)
4. UPDATE video_upload SET status = 2
```

---

### 4.7 GET `/video/play?videoId=xxx`

**说明**：私有 bucket 的视频不能直接通过 MinIO URL 访问，必须走后端获取带时效的预签名 URL。此接口校验 token 后生成 10 分钟有效的预签名 URL，前端拿到后直接请求 MinIO 播放。

**请求**：
```
GET /api/video/play?videoId=42
Authorization: <token>
```

**响应**：
```json
{
  "success": true,
  "data": {
    "url": "http://minio-host:9000/hmdp-videos/videos/1010/xxx.mp4?X-Amz-Algorithm=...&X-Amz-Expires=600..."
  }
}
```

**后端逻辑**：
```
1. token → currentUserId
2. 查 video_file WHERE id = videoId
3. 不存在 → 404
4. 调 MinIO SDK 生成预签名 GET URL（expiry = 10 分钟）：
     getPresignedObjectUrl(
       GetPresignedObjectUrlArgs.builder()
         .bucket("hmdp-videos")
         .object(objectKey)
         .expiry(10, TimeUnit.MINUTES)
         .method(Method.GET)
         .build()
     )
5. 返回预签名 URL
```

**安全说明**：
- 预签名 URL 有效期 10 分钟，过期自动失效
- 即使 URL 被截屏分享，10 分钟后无法访问
- 如果想进一步限制，可以把 `/video/play` 的 videoId 做权限校验（例如只有上传者本人可访问，或加上访问次数限制）

---

## 5. 鉴权总结

| 接口 | 鉴权方式 | 额外校验 |
|------|----------|----------|
| check | token → userId | 无 |
| init | token → userId | 无 |
| chunk | token → userId | uploadId 查表，比对 owner |
| complete | token → userId | uploadId 查表，比对 owner |
| progress | token → userId | uploadId 查表，比对 owner |
| cancel | token → userId | uploadId 查表，比对 owner |
| `/video/play` | token → userId | videoId 查表，可扩展权限校验 |

---

## 6. MinIO 配置建议

```
bucket: hmdp-videos
策略:   private       （禁止外部直接访问，所有读取走 /video/play 预签名 URL）
分片大小: ≥ 8MB      （保证 MinIO ETag 就是文件 MD5，用于 complete 时校验）
```

后端初始化时确保 bucket 存在：

```java
boolean found = minioClient.bucketExists(
    BucketExistsArgs.builder().bucket("hmdp-videos").build()
);
if (!found) {
  minioClient.makeBucket(
    MakeBucketArgs.builder().bucket("hmdp-videos").build()
  );
}
```

---

## 7. 时序图

```
前端                             后端                           MinIO
 │                                │                               │
 │── 1. 计算文件 MD5               │                               │
 │── GET /check?md5=xxx ────────>│                               │
 │<── {exists:false} ──────────│                               │
 │                                │                               │
 │── POST /init ────────────────>│                               │
 │                                │── createMultipartUpload ────>│
 │                                │<── uploadId ────────────────│
 │                                │── INSERT video_upload         │
 │<── {uploadId, objectKey} ───│                               │
 │                                │                               │
 │── POST /chunk (index=0) ─────>│                               │
 │   (校验 userId)                 │── uploadPart(partNum=1) ────>│
 │                                │<── ETag ────────────────────│
 │<── {etag:"xxx"} ────────────│                               │
 │                                │                               │
 │  ... 重复 N 次 ...              │                               │
 │                                │                               │
 │── POST /complete ────────────>│                               │
 │   (校验 userId, parts.length)   │── completeMultipartUpload ──>│
 │                                │<── OK ─────────────────────│
 │                                │  校验 MD5                     │
 │                                │── INSERT video_file           │
 │                                │── UPDATE video_upload status=1│
 │<── {videoId, url} ───────────│                               │
 │                                │                               │
 │── GET /video/play?videoId=42 ─>│                               │
 │   (token 鉴权)                  │── getPresignedObjectUrl ─────>│
 │                                │<── 预签名 URL ───────────────│
 │<── {url: "minio-host/...?     │                               │
 │       X-Amz-Expires=600..."} ──│                               │
 │                                │                               │
 │── GET minio-host/...?X-Amz...──│                               │
 │   (浏览器直连 MinIO 播放)       │    MinIO 验证签名，返回视频流    │
```

---

## 8. 错误码约定

| HTTP 状态码 | 场景 |
|-------------|------|
| 401 | token 无效或过期（由现有拦截器统一处理） |
| 403 | uploadId 不属于当前用户 |
| 400 | 参数缺失 / 分片数不对 / 上传已结束 |
| 409 | init 时 MD5 已存在（并发） |
| 500 | MinIO 故障 / 服务端异常 |

---

## 9. 定时清理

建议后台定时任务（如每 1 小时）：

```sql
SELECT upload_id, object_key
FROM video_upload
WHERE status = 0 AND created_at < NOW() - INTERVAL 24 HOUR;
```

逐条调 MinIO `abortMultipartUpload` 清理，然后 `SET status = 2`。

---

## 10. URL 安全模型

**问题**：MinIO bucket 设 `public-read` 时，知道 object_key 即可直接访问视频。路径中 `videos/1010/xxx.mp4` 包含用户 ID，遍历即可拿到所有用户的视频。

**方案**：bucket 设为 **private**，所有视频访问走后端接口 `/video/play?videoId=xxx`。

**完整链路**：

```
1. 用户请求播放 → POST blog/comment 等业务接口嵌入的是 videoId，不是 MinIO URL

2. 前端解析到 <video> 标签 → 发起 GET /api/video/play?videoId=42
   → 携带 token，后端鉴权

3. 后端查 video_file 表获取 objectKey
   → 调 MinIO SDK 生成预签名 URL（10 分钟过期）
   → 返回给前端

4. 前端拿到预签名 URL → <video src="预签名URL"> → 浏览器直连 MinIO 播放

5. 10 分钟后 → 预签名 URL 失效 → 前端重新请求 /video/play 获取新 URL
```

**防护效果**：

| 攻击方式 | 防护 |
|----------|------|
| 伪造 videoId 遍历 | `/video/play` 需 token 鉴权 |
| 截屏分享视频 URL | 10 分钟后过期，对方拿到的是失效链接 |
| 直接请求 MinIO | bucket 为 private，无签名拒绝访问 |
| 批量下载 | 可扩展 `/video/play` 增加频率限制（如每分钟 10 次） |

