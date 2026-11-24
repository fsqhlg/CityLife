package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询笔记详情（带作者昵称、头像、当前用户是否点赞）
     *
     * @param id 笔记 id
     * @return 笔记详情
     */
    Result queryById(Long id);

    /**
     * 点赞 / 取消点赞（一人一赞，用 Redis ZSet 记录）
     *
     * @param id 笔记 id
     * @return 操作结果
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞排行榜（点赞时间倒序的前 5 个用户）
     *
     * @param id 笔记 id
     * @return 用户列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 查询某个用户的笔记（分页）
     *
     * @param userId  用户 id
     * @param current 页码
     * @return 笔记列表
     */
    Result queryBlogOfUser(Long userId, Integer current);

    /**
     * 查询热门笔记（按点赞数倒序，带作者信息和当前用户点赞状态）
     *
     * @param current 页码
     * @return 笔记列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 发布笔记，并推送到粉丝的收件箱
     *
     * @param blog 笔记内容
     * @return 笔记 id
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注流（滚动分页）
     *
     * @param max    上一页最后一条的时间戳，第一页传当前时间
     * @param offset 与 max 同分的已读条数
     * @return 笔记列表 + 下一页的 minTime / offset
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
