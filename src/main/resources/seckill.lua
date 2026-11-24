-- 秒杀原子脚本（M1/M2/M3 的核心）
-- KEYS[1] = seckill:stock:{voucherId}
-- KEYS[2] = seckill:order:{voucherId}
-- KEYS[3] = seckill:outbox（本地消息 outbox，不是最终消费队列）
-- KEYS[4] = seckill:ledger:{voucherId}
-- ARGV[1] = voucherId
-- ARGV[2] = userId
-- ARGV[3] = orderId
-- ARGV[4] = nowMillis
-- 返回：0 成功；1 库存不足；2 重复下单；3 活动不存在；4 Stream 类型异常

local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil then
    return 3
end
if stock <= 0 then
    return 1
end
if redis.call('sismember', KEYS[2], ARGV[2]) == 1 then
    return 2
end

-- 防止 seckill:outbox 被别的类型占住，导致 XADD 报错后库存已扣、消息没入队
local streamType = redis.call('type', KEYS[3])['ok']
if streamType ~= 'stream' and streamType ~= 'none' then
    return 4
end

-- 扣库存 + 一人一单
redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], ARGV[2])

-- 扣减和入队在同一个 Lua 里完成：不会出现“库存扣了、消息没入队”
redis.call('xadd', KEYS[3], '*',
        'orderId', ARGV[3],
        'userId', ARGV[2],
        'voucherId', ARGV[1])

-- 扣减流水，供对账/补偿任务使用
redis.call('hset', KEYS[4], ARGV[3],
        ARGV[2] .. '|' .. ARGV[1] .. '|PENDING|' .. ARGV[4] .. '|0')

return 0
