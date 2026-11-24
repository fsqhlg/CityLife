-- 滑动窗口限流（Redis 服务端时间，避免多实例时钟漂移）
-- KEYS[1] = rate:{prefix}:{dimension}:{id}
-- ARGV[1] = windowMillis
-- ARGV[2] = limit
-- ARGV[3] = member（建议 UUID，避免同一毫秒覆盖）
-- 返回：1 放行；0 拒绝
local t = redis.call('time')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local member = ARGV[3]

redis.call('zremrangebyscore', KEYS[1], 0, now - window)
local count = redis.call('zcard', KEYS[1])
if count >= limit then
    return 0
end
redis.call('zadd', KEYS[1], now, member)
redis.call('pexpire', KEYS[1], window)
return 1
