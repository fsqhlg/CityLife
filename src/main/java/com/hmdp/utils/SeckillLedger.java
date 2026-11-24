package com.hmdp.utils;

import com.hmdp.dto.SeckillMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_LEDGER_KEY;

/**
 * 秒杀扣减流水（M3）。
 * <p>
 * Lua 扣减库存时写入 PENDING；消费者建单成功后改成 DONE；失败回滚后改成 ROLLED_BACK。
 * 对账任务只需要扫这个 Hash，就能发现“Redis 扣了、DB 没落库”的半成品订单。
 */
@Component
public class SeckillLedger {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final String CANCELED = "CANCELED";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public static class Entry {
        private Long orderId;
        private Long userId;
        private Long voucherId;
        private String status;
        private long createTime;
        private int retry;

        public Long getOrderId() {
            return orderId;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getVoucherId() {
            return voucherId;
        }

        public String getStatus() {
            return status;
        }

        public long getCreateTime() {
            return createTime;
        }

        public int getRetry() {
            return retry;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setRetry(int retry) {
            this.retry = retry;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public void setVoucherId(Long voucherId) {
            this.voucherId = voucherId;
        }
    }

    private String key(Long voucherId) {
        return SECKILL_LEDGER_KEY + voucherId;
    }

    public Entry get(Long voucherId, Long orderId) {
        Object value = stringRedisTemplate.opsForHash().get(key(voucherId), orderId.toString());
        if (value == null) {
            return null;
        }
        return parse(voucherId, orderId, value.toString());
    }

    public void markDone(Long voucherId, Long orderId) {
        markStatus(voucherId, orderId, DONE);
    }

    public void markFailed(Long voucherId, Long orderId) {
        markStatus(voucherId, orderId, FAILED);
    }

    public void markRolledBack(Long voucherId, Long orderId) {
        markStatus(voucherId, orderId, ROLLED_BACK);
    }

    public void markCanceled(Long voucherId, Long orderId) {
        markStatus(voucherId, orderId, CANCELED);
    }

    public void markStatus(Long voucherId, Long orderId, String status) {
        Entry entry = get(voucherId, orderId);
        if (entry == null) {
            return;
        }
        entry.setStatus(status);
        write(entry);
    }

    public int bumpRetry(Long voucherId, Long orderId) {
        Entry entry = get(voucherId, orderId);
        if (entry == null) {
            return 0;
        }
        entry.setRetry(entry.getRetry() + 1);
        write(entry);
        return entry.getRetry();
    }

    public List<Entry> listPending(Long voucherId) {
        return listByStatus(voucherId, PENDING);
    }

    /**
     * 未完成流水：PENDING（还没落库）和 FAILED（落库失败但还没回滚）。
     * 对账任务对这两类都要处理。
     */
    public List<Entry> listUnfinished(Long voucherId) {
        List<Entry> result = new ArrayList<>();
        Set<Object> fields = stringRedisTemplate.opsForHash().keys(key(voucherId));
        for (Object field : fields) {
            Entry entry = get(voucherId, Long.valueOf(field.toString()));
            if (entry != null && (PENDING.equals(entry.getStatus()) || FAILED.equals(entry.getStatus()))) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<Entry> listByStatus(Long voucherId, String status) {
        List<Entry> result = new ArrayList<>();
        Set<Object> fields = stringRedisTemplate.opsForHash().keys(key(voucherId));
        for (Object field : fields) {
            Entry entry = get(voucherId, Long.valueOf(field.toString()));
            if (entry != null && status.equals(entry.getStatus())) {
                result.add(entry);
            }
        }
        return result;
    }

    public int pendingCount(Long voucherId) {
        return listPending(voucherId).size();
    }

    public int unfinishedCount(Long voucherId) {
        return listUnfinished(voucherId).size();
    }

    /**
     * 拿到所有有流水的 voucherId。单体/小数据量直接用 keys，
     * 生产环境应改成 SCAN 游标，避免阻塞 Redis。
     */
    public Set<Long> voucherIds() {
        Set<String> keys = stringRedisTemplate.keys(SECKILL_LEDGER_KEY + "*");
        Set<Long> result = new java.util.HashSet<>();
        if (keys == null) {
            return result;
        }
        for (String k : keys) {
            String suffix = k.substring(SECKILL_LEDGER_KEY.length());
            try {
                result.add(Long.valueOf(suffix));
            } catch (NumberFormatException ignored) {
                // 忽略异常 key
            }
        }
        return result;
    }

    public void write(Entry entry) {
        if (entry == null || entry.getOrderId() == null || entry.getVoucherId() == null) {
            return;
        }
        String value = entry.getUserId() + "|" + entry.getVoucherId() + "|" + entry.getStatus()
                + "|" + entry.getCreateTime() + "|" + entry.getRetry();
        stringRedisTemplate.opsForHash().put(key(entry.getVoucherId()), entry.getOrderId().toString(), value);
    }

    private Entry parse(Long voucherId, Long orderId, String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length < 5) {
            return null;
        }
        Entry entry = new Entry();
        entry.setOrderId(orderId);
        entry.setVoucherId(voucherId);
        entry.setUserId(Long.valueOf(parts[0]));
        entry.setStatus(parts[2]);
        entry.setCreateTime(Long.parseLong(parts[3]));
        entry.setRetry(Integer.parseInt(parts[4]));
        return entry;
    }

    public SeckillMessage toMessage(Entry entry) {
        return new SeckillMessage(entry.getOrderId(), entry.getUserId(), entry.getVoucherId());
    }

    public List<Entry> allPending() {
        List<Entry> result = new ArrayList<>();
        for (Long voucherId : voucherIds()) {
            result.addAll(listPending(voucherId));
        }
        Collections.sort(result, (a, b) -> Long.compare(a.getCreateTime(), b.getCreateTime()));
        return result;
    }
}
