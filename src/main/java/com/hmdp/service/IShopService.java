package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据 id 查询商铺：先查 Redis 缓存，未命中再查数据库并回写缓存
     *
     * @param id 商铺 id
     * @return 商铺详情
     */
    Result queryById(Long id);

    /**
     * 更新商铺信息，并删除对应的缓存（缓存更新策略：先写库，再删缓存）
     *
     * @param shop 商铺数据
     * @return 更新结果
     */
    Result update(Shop shop);

    /**
     * 把商铺数据以「逻辑过期」的格式预热到 Redis（供测试/后台任务调用）
     *
     * @param id            商铺 id
     * @param expireSeconds 逻辑过期时间（秒），从当前时间往后推
     */
    void saveShop2Redis(Long id, Long expireSeconds);

    /**
     * 按类型分页查询商铺；传了经纬度就按距离排序（附近商户）
     *
     * @param typeId  商铺类型
     * @param current 页码
     * @param x       经度，可为空
     * @param y       纬度，可为空
     * @return 商铺列表（带 distance）
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
