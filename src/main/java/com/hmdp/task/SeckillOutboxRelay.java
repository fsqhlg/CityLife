package com.hmdp.task;

import com.hmdp.dto.SeckillMessage;
import com.hmdp.mq.SeckillMessageProducer;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SeckillLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Stream outbox -> RocketMQ 投递器。
 * <p>
 * 秒杀 Lua 仍然把消息 XADD 到 Redis Stream，但 Stream 不再承担“建单队列”的职责，
 * 而是作为本地 outbox：保证扣减和消息写入原子完成。由本类把 outbox 消息转发到
 * RocketMQ，RocketMQ 才是真正削峰、重试、死信的消费队列。
 *
 * 这样设计的原因：Lua 不能直接发 RocketMQ，直接“Lua 扣减 + Java 发 MQ”会有
 * “库存扣了、MQ 没发出去”的窗口。outbox 把窗口收在 Redis 内部，再异步转发。
 */
@Slf4j
@Component
public class SeckillOutboxRelay {

    private static final String OUTBOX = RedisConstants.SECKILL_OUTBOX_KEY;
    private static final String GROUP = RedisConstants.SECKILL_OUTBOX_GROUP;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillMessageProducer seckillMessageProducer;

    @Resource
    private SeckillLedger seckillLedger;

    @Value("${seckill.outbox.consumer-count:1}")
    private int consumerCount;

    @Value("${seckill.max-retry:3}")
    private int maxRetry;

    @Value("${seckill.outbox.pending-idle-seconds:60}")
    private long pendingIdleSeconds;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger threadSeq = new AtomicInteger();
    private ExecutorService executor;

    @PostConstruct
    public void start() {
        createGroupIfAbsent();
        executor = Executors.newFixedThreadPool(Math.max(1, consumerCount), r -> {
            Thread t = new Thread(r, "seckill-outbox-relay-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < Math.max(1, consumerCount); i++) {
            String consumerName = "relay-" + i;
            executor.submit(() -> relayLoop(consumerName));
        }
        log.info("秒杀 outbox 转发器启动，consumerCount={}, group={}", consumerCount, GROUP);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void createGroupIfAbsent() {
        try {
            stringRedisTemplate.opsForStream().createGroup(OUTBOX, ReadOffset.from("0-0"), GROUP);
            log.info("创建 outbox 消费者组成功，outbox={}, group={}", OUTBOX, GROUP);
        } catch (Exception e) {
            // BUSYGROUP：组已存在，属于正常情况
            log.info("outbox 消费者组已存在或创建跳过，outbox={}, group={}, reason={}",
                    OUTBOX, GROUP, e.getMessage());
        }
    }

    private void relayLoop(String consumerName) {
        while (running.get()) {
            try {
                // 先处理上次没有 ACK 的消息，再读新消息
                processPending(consumerName);
                processNewMessages(consumerName);
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("outbox 转发循环异常，consumer={}, reason={}", consumerName, e.getMessage());
                    sleepQuietly(1000);
                }
            }
        }
    }

    private void processNewMessages(String consumerName) {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                StreamOffset.create(OUTBOX, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, String> map = new HashMap<>();
            record.getValue().forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
            processRecord(record.getId(), map, 1, consumerName);
        }
    }

    private void processPending(String consumerName) {
        PendingMessages pending = stringRedisTemplate.opsForStream()
                .pending(OUTBOX, GROUP, Range.unbounded(), 20);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().getSeconds() < pendingIdleSeconds) {
                continue;
            }
            List<ByteRecord> claimed = claim(message.getId(), consumerName);
            for (ByteRecord record : claimed) {
                Map<String, String> map = new HashMap<>();
                record.getValue().forEach((k, v) ->
                        map.put(new String(k, StandardCharsets.UTF_8), new String(v, StandardCharsets.UTF_8)));
                processRecord(record.getId(), map, (int) message.getTotalDeliveryCount(), consumerName);
            }
        }
    }

    private List<ByteRecord> claim(RecordId recordId, String consumerName) {
        byte[] streamKey = OUTBOX.getBytes(StandardCharsets.UTF_8);
        return stringRedisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                connection.streamCommands().xClaim(
                        streamKey,
                        GROUP,
                        consumerName,
                        XClaimOptions.minIdle(Duration.ofSeconds(pendingIdleSeconds)).ids(recordId)));
    }

    private void processRecord(RecordId recordId, Map<String, String> map, int deliveryCount, String consumerName) {
        SeckillMessage message = SeckillMessage.from(map);
        if (message == null) {
            log.error("非法秒杀消息，进入 outbox 死信，recordId={}, body={}", recordId, map);
            moveToDlq(recordId, map, "非法消息体");
            ack(recordId);
            return;
        }

        // 已经 DONE/取消/回滚的流水不再重复投递，减轻下游无意义消费
        SeckillLedger.Entry ledger = seckillLedger.get(message.getVoucherId(), message.getOrderId());
        if (ledger != null && (SeckillLedger.DONE.equals(ledger.getStatus())
                || SeckillLedger.CANCELED.equals(ledger.getStatus())
                || SeckillLedger.ROLLED_BACK.equals(ledger.getStatus()))) {
            log.info("outbox 消息对应流水已终结，跳过投递，orderId={}, status={}",
                    message.getOrderId(), ledger.getStatus());
            ack(recordId);
            return;
        }

        boolean sent = seckillMessageProducer.send(message);
        if (sent) {
            ack(recordId);
            log.info("outbox 消息已转发 RocketMQ，orderId={}, consumer={}",
                    message.getOrderId(), consumerName);
            return;
        }

        log.warn("outbox 消息转发 RocketMQ 失败，保留 pending 待重试，orderId={}, deliveryCount={}",
                message.getOrderId(), deliveryCount);
        if (deliveryCount >= maxRetry) {
            // Broker 长时间不可用：进 outbox 死信，避免无限占用 pending；
            // 对账任务仍会从 ledger 重新投递，保证最终收敛。
            moveToDlq(recordId, map, "RocketMQ 投递连续失败");
            ack(recordId);
            log.error("outbox 消息进入死信，orderId={}, recordId={}", message.getOrderId(), recordId);
        }
    }

    private void moveToDlq(RecordId recordId, Map<String, String> map, String error) {
        Map<String, String> dlq = new HashMap<>(map);
        dlq.put("originRecordId", recordId.getValue());
        dlq.put("error", truncate(error, 500));
        dlq.put("failedAt", String.valueOf(System.currentTimeMillis()));
        dlq.put("stage", "outbox-relay");
        stringRedisTemplate.opsForStream().add(RedisConstants.SECKILL_OUTBOX_DLQ_KEY, dlq);
    }

    private void ack(RecordId recordId) {
        stringRedisTemplate.opsForStream().acknowledge(OUTBOX, GROUP, recordId);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
