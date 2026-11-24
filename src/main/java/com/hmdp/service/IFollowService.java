package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注 / 取关
     *
     * @param followUserId 被关注的用户 id
     * @param isFollow     true 关注，false 取关
     * @return 操作结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否关注了某人
     *
     * @param followUserId 被关注的用户 id
     * @return true / false
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户和某人的共同关注
     *
     * @param id 另一个用户 id
     * @return 共同关注的用户列表
     */
    Result followCommons(Long id);
}
