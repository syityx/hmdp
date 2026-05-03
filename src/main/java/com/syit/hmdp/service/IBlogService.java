package com.syit.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.Blog;

/**
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result likeBlog(Long id);

    Blog isBlogLiked(Blog blog);

    Result queryBlogLikes(String id);

    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
