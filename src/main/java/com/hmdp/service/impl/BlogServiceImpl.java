package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserServiceImpl userService;

    @Resource
    private FollowServiceImpl followService;

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        // 保存探店博文
        save(blog);
        // 获取博主的粉丝
        List<Follow> follows = followService.query().eq("followUserId", userId).list();
        // 给粉丝推送博文id
        for (Follow follow : follows) {
            Long fanId = follow.getId();
            stringRedisTemplate.opsForZSet().add(FEED_KEY + fanId, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result likeBlog(Long id) {
        // 获取当前用户id
        Long userid = UserHolder.getUser().getId();
        // 用set看是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userid);
        // 没点赞，加入set，数据库更新like数
        if (score == null) {
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(userid), System.currentTimeMillis());
            update().eq("id", id).setSql("liked = liked + 1").update();
        }
        // 点赞了，移出set，数据库更新like数
        else {
            stringRedisTemplate.opsForZSet().remove(key, String.valueOf(userid));
            update().eq("id", id).setSql("liked = liked - 1").update();
        }
        return Result.ok();
    }


    @Override
    public Result queryBlogById(Long id) {
        Blog blog = query().eq("id", id).one();
        // 获取当前用户，看是否点赞了
        Long userid = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userid);
        // 点赞了
        if (score != null) {
            blog.setIsLike(true);
        }
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 返回点赞时间最早的用户（前5
        Set<String> ids = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (ids == null && ids.isEmpty()) {
            return Result.ok();
        }
        List<Long> top5ids = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(top5ids);
        List<UserDTO> userDTO = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTO);
    }

    //    public Result likeBlog2(Long id) {
//        // 获取当前用户id
//        Long userid = UserHolder.getUser().getId();
//        // 用set看是否点赞
//        String key = BLOG_LIKED_KEY + id;
//        Boolean isLike = stringRedisTemplate.opsForSet().isMember(key, userid);
//        // 没点赞，加入set，数据库更新like数
//        if (!BooleanUtil.isTrue(isLike)) {
//            stringRedisTemplate.opsForSet().add(key, String.valueOf(userid));
//            update().eq("id", id).setSql("liked = liked + 1").update();
//        }
//        // 点赞了，移出set，数据库更新like数
//        else {
//            stringRedisTemplate.opsForSet().remove(key, String.valueOf(userid));
//            update().eq("id", id).setSql("liked = liked - 1").update();
//        }
//        return Result.ok();
//    }
}
