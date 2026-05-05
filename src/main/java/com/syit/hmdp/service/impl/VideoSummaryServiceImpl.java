package com.syit.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.Blog;
import com.syit.hmdp.entity.BlogSummary;
import com.syit.hmdp.entity.VideoFile;
import com.syit.hmdp.mapper.BlogSummaryMapper;
import com.syit.hmdp.mapper.VideoFileMapper;
import com.syit.hmdp.service.IBlogService;
import com.syit.hmdp.service.IVideoSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSummaryServiceImpl implements IVideoSummaryService {

    private final IBlogService blogService;
    private final VideoFileMapper videoFileMapper;
    private final BlogSummaryMapper blogSummaryMapper;
    private final SummaryProcessor summaryProcessor;

    @Override
    public Result startSummary(Long blogId) {
        // 1. 查缓存
        BlogSummary existing = blogSummaryMapper.selectOne(
                new LambdaQueryWrapper<BlogSummary>().eq(BlogSummary::getBlogId, blogId));
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() == 1) {
                return Result.ok(Map.of("summary", existing.getSummary(), "status", "done"));
            }
            if (existing.getStatus() != null && existing.getStatus() == 0) {
                return Result.ok(Map.of("status", "processing"));
            }
            // status == 2 失败，删掉重试
            blogSummaryMapper.deleteById(existing.getId());
        }

        // 2. 校验博客
        Blog blog = blogService.getById(blogId);
        if (blog == null) return Result.fail("博客不存在");

        Long videoId = parseVideoId(blog.getImages());
        if (videoId == null) return Result.fail("该博客没有关联视频");

        VideoFile vf = videoFileMapper.selectById(videoId);
        if (vf == null) return Result.fail("视频不存在");

        // 3. 创建 pending 记录
        BlogSummary bs = new BlogSummary();
        bs.setBlogId(blogId);
        bs.setStatus(0);
        bs.setCreatedAt(LocalDateTime.now());
        blogSummaryMapper.insert(bs);

        // 4. 启动异步处理
        summaryProcessor.process(blogId, videoId, blog.getTitle(), blog.getContent());

        log.info("已启动异步总结, blogId={}", blogId);
        return Result.ok(Map.of("status", "processing"));
    }

    @Override
    public Result getSummary(Long blogId) {
        BlogSummary bs = blogSummaryMapper.selectOne(
                new LambdaQueryWrapper<BlogSummary>().eq(BlogSummary::getBlogId, blogId));
        if (bs == null || (bs.getStatus() != null && bs.getStatus() == 2)) {
            return Result.ok();
        }
        if (bs.getStatus() == null || bs.getStatus() == 0) {
            return Result.ok(Map.of("status", "processing"));
        }
        return Result.ok(Map.of("summary", bs.getSummary(), "status", "done"));
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
}
