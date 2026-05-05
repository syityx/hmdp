package com.syit.hmdp.service.impl;

import cn.hutool.core.io.FileUtil;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.syit.hmdp.entity.BlogSummary;
import com.syit.hmdp.entity.VideoFile;
import com.syit.hmdp.mapper.BlogSummaryMapper;
import com.syit.hmdp.mapper.VideoFileMapper;
import com.syit.hmdp.service.IAsrService;
import com.syit.hmdp.service.IAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryProcessor {

    private final IAiService aiService;
    private final IAsrService asrService;
    private final AmazonS3 amazonS3;
    private final VideoFileMapper videoFileMapper;
    private final BlogSummaryMapper blogSummaryMapper;

    @Value("${minio.bucket}")
    private String bucket;

    @Value("${ffmpeg.path}")
    private String ffmpegPath;

    @Value("${spring.ai.openai.chat.options.model}")
    private String aiModel;

    @Async("summaryExecutor")
    public void process(Long blogId, Long videoId, String title, String content) {
        log.info("异步总结开始, blogId={}, videoId={}", blogId, videoId);

        VideoFile vf = videoFileMapper.selectById(videoId);
        if (vf == null) {
            updateFailed(blogId, "视频不存在");
            return;
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("video-summary-");
        } catch (IOException e) {
            updateFailed(blogId, "创建临时目录失败");
            return;
        }

        try {
            File videoFile = new File(tempDir.toFile(), "video.mp4");
            log.info("下载视频, objectKey={}", vf.getObjectKey());
            amazonS3.getObject(new GetObjectRequest(bucket, vf.getObjectKey()), videoFile);

            File audioFile = new File(tempDir.toFile(), "audio.mp3");
            extractAudio(videoFile, audioFile);

            String transcript = asrService.transcribe(audioFile);

            String prompt = buildPrompt(title, content, transcript);
            String summary = aiService.summarize(prompt);

            blogSummaryMapper.update(null, new LambdaUpdateWrapper<BlogSummary>()
                    .eq(BlogSummary::getBlogId, blogId)
                    .set(BlogSummary::getTranscript, transcript)
                    .set(BlogSummary::getSummary, summary)
                    .set(BlogSummary::getModel, aiModel)
                    .set(BlogSummary::getStatus, 1)
                    .set(BlogSummary::getCreatedAt, LocalDateTime.now()));

            log.info("异步总结完成, blogId={}", blogId);
        } catch (Exception e) {
            log.error("异步总结失败, blogId={}", blogId, e);
            updateFailed(blogId, e.getMessage());
        } finally {
            FileUtil.del(tempDir.toFile());
        }
    }

    private void updateFailed(Long blogId, String errorMsg) {
        blogSummaryMapper.update(null, new LambdaUpdateWrapper<BlogSummary>()
                .eq(BlogSummary::getBlogId, blogId)
                .set(BlogSummary::getStatus, 2)
                .set(BlogSummary::getErrorMsg, errorMsg)
                .set(BlogSummary::getCreatedAt, LocalDateTime.now()));
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
}
