package com.syit.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.dto.ScrollResult;
import com.syit.hmdp.dto.UserDTO;
import com.syit.hmdp.entity.Blog;
import com.syit.hmdp.entity.User;
import com.syit.hmdp.mapper.BlogMapper;
import com.syit.hmdp.service.IBlogService;
import com.syit.hmdp.service.IFollowService;
import com.syit.hmdp.service.IUserService;
import com.syit.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.syit.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.syit.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Autowired
    private IFollowService followService;

    @Override
    public Result likeBlog(Long id) {
//        获取登录用户
        Long userId = UserHolder.getUser().getId();
//        判断用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        如果没有点赞，可以点赞，数据库＋1
        if (score == null) {
            // 数据库＋1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                // 保存到redis
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 如果已经点赞，取消点赞，数据库-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                // 删除redis中的记录
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }


    public Blog isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return blog;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            blog.setIsLike(false);
        }else{
            blog.setIsLike(true);
        }
        return blog;
    }

    @Override
    public Result queryBlogLikes(String id) {
        String key = BLOG_LIKED_KEY + id;
//        查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = top5.stream().map(Long::valueOf).toList();
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {

        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
//        查询作者的粉丝
        followService.query().eq("follow_user_id", user.getId()).list().forEach(follow -> {
            // 推送消息给粉丝
            String key = FEED_KEY + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        });
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
//        从收件箱滚动分页查询
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int os = 1;  // offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
//            获取id
            String idStr = tuple.getValue();
            if (idStr != null) {
                ids.add(Long.valueOf(idStr));
            }
//            获取时间戳
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            if (time == minTime) {
                os += 1;
            }else {
                minTime = time;
                os = 1;
            }
        }
        ScrollResult r = new ScrollResult();
        r.setOffset(os);
        r.setMinTime(minTime);

//        List<Blog> blogs = listByIds(ids);
//        这种方式是按照MySQL的in来查找的，无序

        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        }

        r.setList(blogs);
        return Result.ok(r);
    }
}
