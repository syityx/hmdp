package com.syit.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ListPartsRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PartListing;

import com.amazonaws.services.s3.model.UploadPartRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.VideoFile;
import com.syit.hmdp.entity.VideoUpload;
import com.syit.hmdp.mapper.VideoFileMapper;
import com.syit.hmdp.mapper.VideoUploadMapper;
import com.syit.hmdp.service.IVideoUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoUploadServiceImpl extends ServiceImpl<VideoUploadMapper, VideoUpload>
        implements IVideoUploadService {

    private final AmazonS3 amazonS3;
    private final VideoFileMapper videoFileMapper;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public Result checkMd5(Long userId, String md5) {
        VideoFile existing = videoFileMapper.selectOne(
                new LambdaQueryWrapper<VideoFile>().eq(VideoFile::getMd5, md5));
        if (existing != null) {
            return Result.ok(buildExistsData(existing));
        }
        return Result.ok(Map.of("exists", false));
    }

    @Override
    public Result initUpload(Long userId, String filename, long fileSize, long chunkSize,
                             int totalChunks, String md5, String contentType) {
        VideoFile dup = videoFileMapper.selectOne(
                new LambdaQueryWrapper<VideoFile>().eq(VideoFile::getMd5, md5));
        if (dup != null) {
            return Result.ok(buildExistsData(dup));
        }

        String ext = extractExt(filename);
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        String objectKey = String.format("videos/%d/%s-%s.%s", userId, datePart, uuid, ext);
        String ct = contentType != null ? contentType : "video/mp4";

        try {
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucket, objectKey);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(ct);
            initRequest.setObjectMetadata(metadata);
            String uploadId = amazonS3.initiateMultipartUpload(initRequest).getUploadId();

            VideoUpload upload = new VideoUpload();
            upload.setUploadId(uploadId);
            upload.setUserId(userId);
            upload.setObjectKey(objectKey);
            upload.setMd5(md5);
            upload.setFilename(filename);
            upload.setFileSize(fileSize);
            upload.setTotalChunks(totalChunks);
            upload.setContentType(ct);
            upload.setStatus(0);
            upload.setCreatedAt(LocalDateTime.now());
            save(upload);

            Map<String, Object> data = new HashMap<>();
            data.put("uploadId", uploadId);
            data.put("objectKey", objectKey);
            return Result.ok(data);
        } catch (Exception e) {
            log.error("初始化分片上传失败", e);
            throw new RuntimeException("初始化分片上传失败", e);
        }
    }

    @Override
    public Result uploadChunk(Long userId, String uploadId, int chunkIndex, MultipartFile chunk) {
        VideoUpload upload = getById(uploadId);
        if (upload == null) return Result.fail("上传会话不存在");
        if (!upload.getUserId().equals(userId)) return Result.fail("无权操作此上传");
        if (upload.getStatus() != 0) return Result.fail("上传已结束");
        if (chunkIndex < 0 || chunkIndex >= upload.getTotalChunks()) return Result.fail("分片索引越界");

        int partNumber = chunkIndex + 1;
        try {
            UploadPartRequest partRequest = new UploadPartRequest()
                    .withBucketName(bucket)
                    .withKey(upload.getObjectKey())
                    .withUploadId(uploadId)
                    .withPartNumber(partNumber)
                    .withInputStream(chunk.getInputStream())
                    .withPartSize(chunk.getSize());
            String etag = amazonS3.uploadPart(partRequest).getPartETag().getETag();

            Map<String, Object> data = new HashMap<>();
            data.put("chunkIndex", chunkIndex);
            data.put("etag", etag);
            return Result.ok(data);
        } catch (Exception e) {
            log.error("分片上传失败, uploadId={}, chunkIndex={}", uploadId, chunkIndex, e);
            throw new RuntimeException("分片上传失败", e);
        }
    }

    @Override
    public Result completeUpload(Long userId, String uploadId, String partsJson) {
        VideoUpload upload = getById(uploadId);
        if (upload == null) return Result.fail("上传会话不存在");
        if (!upload.getUserId().equals(userId)) return Result.fail("无权操作此上传");

        JSONArray partsArr = JSONUtil.parseArray(partsJson);
        if (partsArr.size() != upload.getTotalChunks()) return Result.fail("分片数不匹配");

        List<PartETag> partETags = new ArrayList<>();
        for (int i = 0; i < partsArr.size(); i++) {
            JSONObject p = partsArr.getJSONObject(i);
            partETags.add(new PartETag(p.getInt("partNumber"), p.getStr("etag")));
        }

        try {
            amazonS3.completeMultipartUpload(new CompleteMultipartUploadRequest(
                    bucket, upload.getObjectKey(), uploadId, partETags));

            // ETag 校验
            ObjectMetadata metadata = amazonS3.getObjectMetadata(bucket, upload.getObjectKey());
            String serverEtag = metadata.getETag().replaceAll("\"", "");
            if (!serverEtag.equalsIgnoreCase(upload.getMd5())) {
                log.warn("MD5 不一致, client={}, server={}", upload.getMd5(), serverEtag);
            }

            VideoFile vf = new VideoFile();
            vf.setMd5(upload.getMd5());
            vf.setObjectKey(upload.getObjectKey());
            vf.setFileSize(upload.getFileSize());
            vf.setUserId(userId);
            vf.setCreatedAt(LocalDateTime.now());
            videoFileMapper.insert(vf);

            upload.setStatus(1);
            updateById(upload);

            Map<String, Object> data = new HashMap<>();
            data.put("videoId", vf.getId());
            data.put("url", "/api/video/play?videoId=" + vf.getId());
            return Result.ok(data);
        } catch (Exception e) {
            log.error("合并分片失败, uploadId={}", uploadId, e);
            try {
                amazonS3.abortMultipartUpload(
                        new AbortMultipartUploadRequest(bucket, upload.getObjectKey(), uploadId));
            } catch (Exception ignored) {}
            upload.setStatus(2);
            updateById(upload);
            throw new RuntimeException("合并分片失败", e);
        }
    }

    @Override
    public Result getProgress(Long userId, String uploadId) {
        VideoUpload upload = getById(uploadId);
        if (upload == null) return Result.fail("上传会话不存在");
        if (!upload.getUserId().equals(userId)) return Result.fail("无权操作此上传");

        try {
            PartListing listResult = amazonS3.listParts(
                    new ListPartsRequest(bucket, upload.getObjectKey(), uploadId));

            List<Map<String, Object>> completedParts = listResult.getParts().stream()
                    .map(ps -> {
                        Map<String, Object> p = new HashMap<>();
                        p.put("partNumber", ps.getPartNumber());
                        p.put("etag", ps.getETag());
                        return p;
                    }).collect(Collectors.toList());

            Map<String, Object> data = new HashMap<>();
            data.put("objectKey", upload.getObjectKey());
            data.put("totalChunks", upload.getTotalChunks());
            data.put("completedParts", completedParts);
            return Result.ok(data);
        } catch (Exception e) {
            log.error("查询进度失败, uploadId={}", uploadId, e);
            throw new RuntimeException("查询进度失败", e);
        }
    }

    @Override
    public Result cancelUpload(Long userId, String uploadId) {
        VideoUpload upload = getById(uploadId);
        if (upload == null) return Result.fail("上传会话不存在");
        if (!upload.getUserId().equals(userId)) return Result.fail("无权操作此上传");

        try {
            amazonS3.abortMultipartUpload(
                    new AbortMultipartUploadRequest(bucket, upload.getObjectKey(), uploadId));
        } catch (Exception e) {
            log.warn("MinIO 取消上传异常, uploadId={}", uploadId, e);
        }
        upload.setStatus(2);
        updateById(upload);
        return Result.ok();
    }

    @Override
    public Result getPlayUrl(Long videoId) {
        VideoFile vf = videoFileMapper.selectById(videoId);
        if (vf == null) return Result.fail("视频不存在");

        Date expiration = new Date(System.currentTimeMillis() + 10 * 60 * 1000);
        GeneratePresignedUrlRequest presignedRequest =
                new GeneratePresignedUrlRequest(bucket, vf.getObjectKey())
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
        String url = amazonS3.generatePresignedUrl(presignedRequest).toString();

        return Result.ok(Map.of("url", url));
    }

    private static Map<String, Object> buildExistsData(VideoFile vf) {
        Map<String, Object> data = new HashMap<>();
        data.put("exists", true);
        data.put("videoId", vf.getId());
        data.put("url", "/api/video/play?videoId=" + vf.getId());
        return data;
    }

    private static String extractExt(String filename) {
        int dotIdx = filename.lastIndexOf('.');
        return dotIdx > 0 ? filename.substring(dotIdx + 1) : "mp4";
    }
}
