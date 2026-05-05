package com.syit.hmdp.service.impl;

import cn.hutool.core.io.FileUtil;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.Blog;
import com.syit.hmdp.entity.BlogSummary;
import com.syit.hmdp.entity.VideoFile;
import com.syit.hmdp.mapper.BlogSummaryMapper;
import com.syit.hmdp.mapper.VideoFileMapper;
import com.syit.hmdp.service.IAsrService;
import com.syit.hmdp.service.IAiService;
import com.syit.hmdp.service.IBlogService;
import com.syit.hmdp.service.IVideoSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSummaryServiceImpl implements IVideoSummaryService {

    private final IBlogService blogService;
    private final VideoFileMapper videoFileMapper;
    private final BlogSummaryMapper blogSummaryMapper;
    private final IAsrService asrService;
    private final IAiService aiService;
    private final AmazonS3 amazonS3;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    @Value("${spring.ai.openai.chat.options.model}")
    private String aiModel;

    @Override
    public Result summarizeBlog(Long blogId) {
        BlogSummary existing = blogSummaryMapper.selectOne(
                new LambdaQueryWrapper<BlogSummary>().eq(BlogSummary::getBlogId, blogId));
        if (existing != null) {
            log.info("命中缓存, blogId={}", blogId);
            return Result.ok(buildResult(existing));
        }

        Blog blog = blogService.getById(blogId);
        if (blog == null) return Result.fail("博客不存在");

        Long videoId = parseVideoId(blog.getImages());
        if (videoId == null) return Result.fail("该博客没有关联视频");

        VideoFile vf = videoFileMapper.selectById(videoId);
        if (vf == null) return Result.fail("视频不存在");

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("video-summary-");
        } catch (IOException e) {
            throw new RuntimeException("创建临时目录失败", e);
        }

        try {
            File videoFile = new File(tempDir.toFile(), "video.mp4");
            log.info("下载视频, objectKey={}", vf.getObjectKey());
            amazonS3.getObject(new GetObjectRequest(bucket, vf.getObjectKey()), videoFile);

            File audioFile = new File(tempDir.toFile(), "audio.mp3");
            extractAudio(videoFile, audioFile);

            String transcript = asrService.transcribe(audioFile);

            String prompt = buildPrompt(blog.getTitle(), blog.getContent(), transcript);

            String summary = aiService.summarize(prompt);

            BlogSummary bs = new BlogSummary();
            bs.setBlogId(blogId);
            bs.setTranscript(transcript);
            bs.setSummary(summary);
            bs.setModel(aiModel);
            bs.setCreatedAt(LocalDateTime.now());
            try {
                blogSummaryMapper.insert(bs);
                log.info("视频总结完成, blogId={}", blogId);
                return Result.ok(buildResult(bs));
            } catch (DuplicateKeyException e) {
                log.info("并发写入冲突, 读取已有记录, blogId={}", blogId);
                BlogSummary cached = blogSummaryMapper.selectOne(
                        new LambdaQueryWrapper<BlogSummary>().eq(BlogSummary::getBlogId, blogId));
                return Result.ok(buildResult(cached));
            }
        } finally {
            FileUtil.del(tempDir.toFile());
        }
    }

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

    private void extractAudio(File videoFile, File audioFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-i", videoFile.getAbsolutePath(),
                    "-vn", "-acodec", "libmp3lame", "-q:a", "2", "-y",
                    audioFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String err = new String(process.getInputStream().readAllBytes());
                throw new RuntimeException("FFmpeg 提取音频失败: " + err);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("FFmpeg 提取音频失败", e);
        }
    }

    private String buildPrompt(String title, String content, String transcript) {
        return String.format(
                "你是一个视频内容总结助手。请根据以下博客信息和视频语音转录文本，生成一段简洁的视频内容总结（200字以内）。\n\n" +
                        "【博客标题】%s\n【博客正文】%s\n【视频语音转录】%s\n\n请生成总结：",
                title != null ? title : "",
                content != null ? content : "",
                transcript != null ? transcript : "");
    }

    private Map<String, Object> buildResult(BlogSummary bs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", bs.getId());
        data.put("blogId", bs.getBlogId());
        data.put("transcript", bs.getTranscript());
        data.put("summary", bs.getSummary());
        data.put("model", bs.getModel());
        data.put("createdAt", bs.getCreatedAt());
        return data;
    }
}
