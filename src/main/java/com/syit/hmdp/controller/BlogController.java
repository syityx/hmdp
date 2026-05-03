package com.syit.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.dto.UserDTO;
import com.syit.hmdp.entity.Blog;
import com.syit.hmdp.entity.User;
import com.syit.hmdp.service.IBlogService;
import com.syit.hmdp.service.IUserService;
import com.syit.hmdp.utils.SystemConstants;
import com.syit.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

//import javax.annotation.Resource;
import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制�?
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

//    ======================================不推荐的字段注入
//    @Autowired
//    private IBlogService blogService;
//    ======================================推荐使用构造器注入
    /**
     * 构造器注入的好处：<br>
     * 1. 可以在构造器中进行一些必要的校验，确保依赖项不为null
     * 2. 使得类的依赖关系更加明确，增强了代码的可读性和可维护性
     * 3. 方便进行单元测试，可以通过构造器传入mock对象进行测试
     */
    private final IBlogService blogService;
    public BlogController(IBlogService blogService) {
        this.blogService = blogService;
    }
//    ======================================

    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
//        blogService.update()
//                .setSql("liked = liked + 1").eq("id", id).update();
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数�?
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数�?
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            blog = blogService.isBlogLiked(blog);
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        });
        return Result.ok(records);
    }

    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable Long id) {
        Blog blog = blogService.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 查询用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        blog = blogService.isBlogLiked(blog);
        return Result.ok(blog);
    }

    @GetMapping("likes/{id}")
    public Result queryBlogLikes(@PathVariable String id){
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }
}
