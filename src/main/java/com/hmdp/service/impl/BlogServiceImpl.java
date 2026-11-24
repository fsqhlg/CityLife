package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 1. 查笔记
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        // 2. 补作者信息（昵称、头像）
        queryBlogUser(blog);

        // 3. 补当前用户是否点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 当前用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;

        // 2. 判断是否已经点过赞（ZSet 里有没有这个用户）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 3. 没点过：数据库点赞数 +1，同时把用户写进 ZSet（score 用时间戳，用于排行榜排序）
            boolean success = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4. 点过：取消点赞，数据库点赞数 -1，同时把用户从 ZSet 移除
            boolean success = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id)
                    .update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;

        // 1. 取点赞时间倒序的前 5 个用户 id
        Set<String> range = stringRedisTemplate.opsForZSet().reverseRange(key, 0, 4);
        if (range == null || range.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2. 转成 Long，并拼成 "1,2,3" 用于保持顺序
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        // 3. 按点赞顺序查用户（ORDER BY FIELD 保证顺序和 ZSet 一致）
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogOfUser(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<Blog> records = page.getRecords();
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        // 1. 保存笔记，作者从登录态取
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        boolean success = save(blog);
        if (!success) {
            return Result.fail("发布笔记失败！");
        }

        // 2. 查询作者的粉丝（follow_user_id = 作者）
        List<Follow> follows = followService.query()
                .eq("follow_user_id", userId)
                .list();

        // 3. 推送到每个粉丝的收件箱 feed:{fanId}
        for (Follow follow : follows) {
            Long fanId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(
                    FEED_KEY + fanId,
                    blog.getId().toString(),
                    System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        // 1. 从收件箱按 score（时间）倒序取，max 是上一页最后一条的时间，offset 是同分跳过数
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 2. 解析出 blogId 列表，并计算下一页的 minTime / offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 0;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                // 与上一条同分，下一页要跳过它
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        // 3. 按 feed 顺序查笔记
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")")
                .list();

        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        // 4. 返回滚动分页结果
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    /**
     * 给笔记补上作者昵称和头像
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前用户是否点赞过这篇笔记（未登录则跳过）
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录，不查点赞状态
            return;
        }

        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }
}
