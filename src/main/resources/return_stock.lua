-- 库存回补（取消/超时关单/建单失败回滚）
-- KEYS[1] = seckill:stock:{voucherId}
-- KEYS[2] = seckill:order:{voucherId}
-- KEYS[3] = seckill:returned
-- ARGV[1] = orderId
-- ARGV[2] = userId
-- ARGV[3] = keepQualification：1 保留购买资格（取消/超时，一人一单不允许复购）；0 移除资格（建单失败回滚）
-- 返回：1 本次真正回补；0 已经回补过，直接忽略

if redis.call('sadd', KEYS[3], ARGV[1]) == 1 then
    -- 取消/超时不能把用户从已购集合移除，否则同一用户还能再抢一次，
    -- 但数据库唯一索引会拦住，最终出现“Redis 成功、DB 没订单”的假成功。
    if ARGV[3] ~= '1' then
        redis.call('srem', KEYS[2], ARGV[2])
    end
    redis.call('incr', KEYS[1])
    return 1
end
return 0
