package com.syit.hmdp.ws;

import com.syit.hmdp.entity.Blog;
import com.syit.hmdp.service.IBlogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BlogTools {

    @Autowired
    private IBlogService blogService;

    @Tool(description = "获取当前用户的所有博客列表，包含标题、点赞数、评论数、创建时间")
    public List<Blog> getMyBlogs() {
        Long userId = UserContext.get();
        log.debug("Tool 调用: getMyBlogs, userId={}", userId);
        return blogService.lambdaQuery()
                .eq(Blog::getUserId, userId)
                .orderByDesc(Blog::getCreateTime)
                .list();
    }

    @Tool(description = "根据博客ID获取单篇博客的完整内容，包含标题、正文、图片、点赞数、评论数")
    public Blog getBlogById(
            @ToolParam(description = "博客ID") Long blogId) {
        log.debug("Tool 调用: getBlogById, blogId={}", blogId);
        return blogService.getById(blogId);
    }
}
