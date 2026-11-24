-- 分布式锁释放：只有 value 对得上才删，避免锁 TTL 到期后误删别人的锁
-- KEYS[1] = lock key
-- ARGV[1] = 加锁时写入的 UUID
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
