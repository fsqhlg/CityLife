# CityLife 本地生活服务平台

> 基于黑马点评课程项目二次开发的本地生活服务项目，重点改造秒杀链路的**一致性、可靠性与可观测性**，把课程版中未闭合的边界补齐，而不是停留在“跟着视频敲过一遍”。

## 1. 项目定位

原版黑马点评是一个优惠券/店铺点评 Demo：已经讲了 Redis 缓存、Lua 秒杀、阻塞队列异步下单、GEO、Feed 流。但课程版有几个明显短板：

- 秒杀 Lua 扣减和 `LPUSH` 入队是两次 Redis 调用，不是原子操作；
- 队列消费没有 ACK、重试上限和死信，消费者挂掉消息可能丢；
- 订单只有“创建”动作，没有支付、取消、超时关单的状态机；
- 没有唯一索引/幂等兜底，极端情况下会重复下单；
- 缺少对账机制，Redis 扣了库存但 DB 没落库时没人知道；
- 缓存锁的释放是直接 `DEL`，存在误删别人锁的经典问题。

这个项目保留原版的业务骨架，把上述问题逐条补上，并引入 **RocketMQ** 承担秒杀订单的削峰与可靠消费：

```text
Lua 原子扣减 + Redis Stream outbox
        ↓  SeckillOutboxRelay 转发
RocketMQ seckill-order-topic
        ↓  SeckillRocketMQConsumer
MySQL 订单 + 库存
        ↓
Redis 扣减流水 + 定时对账补偿
```

## 2. 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Spring Boot 2.3.12、Spring MVC、AOP、Validation |
| 持久层 | MyBatis-Plus 3.4.3、MySQL 8.0 |
| 缓存 | Redis 5.0（Lua、Stream outbox、Bitmap、ZSet、GEO） |
| 消息队列 | RocketMQ 4.9.x（本地 Broker 4.9.8，客户端 4.9.1，Starter 2.2.1） |
| 可观测性 | Spring Boot Actuator + Micrometer |
| 构建 | Maven、JDK 21 编译/运行（项目源码兼容 JDK 8） |

## 3. 核心改造

### M1 订单状态机 + 超时关单

订单状态与 `tb_voucher_order.status` 对齐：

- `1` 待支付、`2` 已支付、`3` 已核销、`4` 已取消、`5` 退款中、`6` 已退款。

改造点：

- 支付、取消、超时关单都用**条件 UPDATE**（`WHERE id=? AND status=1`）做状态机并发控制，避免“支付”和“取消”同时成功；
- 下单成功写入 ZSet 延迟队列 `seckill:order:delay`，score 为应关闭时间；
- `OrderTimeoutTask` 每分钟扫描到期订单，关单后调用幂等回补；
- 取消/超时回补 Redis 库存的同时，同步把 `tb_seckill_voucher.stock` `+1`，避免 Redis 与 DB 库存越差越多；
- 回补用 `SADD seckill:returned` 做幂等，同一订单只回补一次。

### M2 RocketMQ 可靠消息（Redis Stream outbox）

请求线程只做两件事：**Lua 原子扣减 + 写 Redis outbox**，然后立刻返回 `orderId`，不碰数据库。RocketMQ 才是真正的订单消费队列。

为什么不让 Lua 直接发 RocketMQ？因为 Lua 运行在 Redis 里，不能直接调用 MQ；如果拆成“Lua 扣减 + Java 发 MQ”，中间会有“库存扣了、MQ 没发出去”的窗口。项目用 **Redis Stream 做本地 outbox**：

1. Lua 内完成：库存校验、一人一单、扣库存、写已购集合、`XADD seckill:outbox`、写扣减流水；
2. `SeckillOutboxRelay` 用消费者组读取 outbox，调用 RocketMQ producer 发送 `seckill-order-topic`；
3. 发送成功才 `XACK`；发送失败保留 pending，空闲 60 秒用 `XPENDING + XCLAIM` 认领重试；
4. `SeckillRocketMQConsumer` 消费 topic，成功返回；异常抛出，RocketMQ 按 `maxReconsumeTimes=3` 重试，超过后进入 `%DLQ%seckill-order-consumer`；
5. 消费端按 `orderId` 幂等，重复消息不会重复建单；
6. 前端拿 `orderId` 轮询 `/voucher-order/result/{orderId}`，区分排队中/成功/失败。

一句话：**Redis Stream 只做“本地消息 outbox”，RocketMQ 负责“削峰、重试、死信、扩容”**。

### M3 扣减流水与定时对账

Lua 扣减时同步写 Hash 流水：

```text
key   = seckill:ledger:{voucherId}
field = orderId
value = userId|voucherId|status|createTimeMillis|retry
```

状态：`PENDING / DONE / FAILED / ROLLED_BACK / CANCELED`。

`SeckillReconcileTask` 每分钟扫描 `PENDING/FAILED` 且超过 90 秒的流水：

1. 订单已落库：把流水改成 `DONE`；
2. 订单未落库：重新投递 RocketMQ（不绕过 MQ 直接写 DB）；
3. 重投超过上限：回滚 Redis 库存，流水改成 `ROLLED_BACK`；
4. 检查 Redis 库存是否负数/超过初始值，并和数据库库存比对，异常打指标告警。

### M4 建单幂等

- `createVoucherOrder` 先按 `orderId` 查是否已落库，已存在直接成功；
- 再按 `(user_id, voucher_id)` 查历史订单，已有其他订单则拒绝建新单；
- 数据库库存用 `stock = stock - 1 WHERE stock > 0` 条件扣减；
- 最后 `tb_voucher_order` 的唯一索引 `uk_user_voucher(user_id, voucher_id)` 兜底；
- `DuplicateKeyException` 按“已成功”处理，保证消费者重试不会重复建单；
- 如果流水已经是 `ROLLED_BACK/CANCELED`，消费者直接跳过，避免“库存已加回但订单又创建”。

### M5 入口防护

- `@RateLimit` + Redis 滑动窗口 Lua：秒杀接口按用户 5 次/秒；
- 验证码接口：IP 5 次/小时 + 手机号 1 次/60 秒；
- 登录失败：15 分钟内累计 5 次锁定，成功登录清空失败计数；
- `LoginFormDTO` / `UserController` 增加 `@Valid`、`@Pattern` 参数校验。

### M6 缓存治理

- 店铺查询先过布隆过滤器 `bloom:shop`：不存在直接返回，不查缓存、不打 DB；
- 互斥锁 value 用 UUID，释放时走 `unlock.lua` 比较 value，解决误删别人的锁；
- 逻辑过期方案保留，异步重建缓存；
- 店铺缓存 TTL 加 0~9 分钟随机值，防止缓存雪崩；
- 店铺分类缓存增加 1 小时 TTL。

## 4. 关键 Redis Key

| Key | 类型 | 用途 |
| --- | --- | --- |
| `seckill:stock:{voucherId}` | String | 秒杀库存 |
| `seckill:stock:init:{voucherId}` | String | 初始库存，用于对账 |
| `seckill:order:{voucherId}` | Set | 已购用户集合，一人一单 |
| `seckill:outbox` | Stream | 本地 outbox，Lua 内 XADD |
| `seckill-outbox-cg` | Consumer Group | outbox 转发消费者组 |
| `seckill:outbox:dlq` | Stream | outbox 死信 |
| `seckill:ledger:{voucherId}` | Hash | 扣减流水 |
| `seckill:order:delay` | ZSet | 超时关单延迟队列 |
| `seckill:returned` | Set | 库存回补幂等标记 |
| `bloom:shop` | BitMap | 店铺布隆过滤器 |

RocketMQ 侧：

| 名称 | 值 |
| --- | --- |
| Topic | `seckill-order-topic` |
| Consumer Group | `seckill-order-consumer` |
| Producer Group | `citylife-seckill-producer` |
| 重试上限 | 3 次 |
| 死信 Topic | `%DLQ%seckill-order-consumer` |

## 5. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单，返回 orderId |
| GET | `/voucher-order/result/{orderId}` | 查询排队/成功/失败 |
| POST | `/voucher-order/pay/{orderId}` | 模拟支付 |
| POST | `/voucher-order/cancel/{orderId}` | 主动取消并回补库存 |
| GET | `/voucher-order/my?status=&current=` | 我的订单分页 |
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/metrics` | 指标列表 |

## 6. 本地启动

### 6.1 环境

- JDK 21（项目按 Java 8 语法编译，使用 JDK 21 运行没问题；Maven 编译时也要用 JDK 21）
- Maven 3.6+
- MySQL 8.0
- Redis 5.0+（**必须是 5.0 以上，3.x 不支持 Stream**）
- RocketMQ 4.9.x（本地用 4.9.8，需要 NameServer + Broker）

### 6.1.1 环境变量

仓库里的 `application.yaml` 不保存真实密码，启动前需要设置：

```powershell
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的 MySQL 密码"
$env:REDIS_PASSWORD="你的 Redis 密码"
```

如果本地密码为空，可以设置为空字符串；不要把真实密码提交回仓库。

### 6.2 数据库

```sql
-- 1. 建库/导入原项目 SQL 后，执行一致性迁移
source src/main/resources/db/migration/V2__seckill_consistency.sql;
```

迁移内容：`tb_voucher_order` 增加 `close_type`、唯一索引 `uk_user_voucher`、索引 `idx_status_create_time`。

### 6.3 Redis

按本地 `redis.conf` 启动 Redis，并确认设置了 `requirepass`：

```bash
redis-server.exe redis.conf
redis-cli.exe -a "$REDIS_PASSWORD" ping
```

`application.yaml` 默认连接 `localhost:6379`，密码由环境变量 `REDIS_PASSWORD` 提供；按自己的环境改。

### 6.4 RocketMQ

将仓库中的 `deploy/rocketmq/broker-citylife.conf` 复制到 RocketMQ 的 `conf` 目录后启动：

```bash
# 1. 启动 NameServer
mqnamesrv.cmd

# 2. 启动 Broker（示例配置里 namesrvAddr=127.0.0.1:9876）
mqbroker.cmd -c conf\broker-citylife.conf

# 3. 创建秒杀 topic（4 读 4 写）
mqadmin.cmd updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t seckill-order-topic -r 4 -w 4
```

Windows + JDK 17/21 运行 RocketMQ 4.9.8 时，需要把 `runserver.cmd` / `runbroker.cmd` 里的 CMS 和旧 GC 日志参数改成 G1，否则会报 `Unrecognized VM option 'UseConcMarkSweepGC'`。仓库外环境按自己的 JDK 调整。

### 6.5 启动应用

```bash
mvn -DskipTests clean package
java -Dfile.encoding=UTF-8 -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

启动后 `SeckillWarmupRunner` 会自动：

- 把店铺 id 灌入布隆过滤器（`bloom:shop:ready` 存在则跳过）；
- 把 `tb_seckill_voucher.stock` 同步到 Redis（`setIfAbsent`，不会覆盖已扣减的库存）。

### 6.6 演示数据

`src/main/resources/db/demo/V3__demo_seckill.sql` 提供了一张库存 100 的秒杀券，可直接导入做演示。

## 7. 压测结果（本地单机）

测试条件：Windows 本机、MySQL/Redis/RocketMQ/应用同机、Redis 5.0.14.1、RocketMQ 4.9.8、库存 100、200 个不同登录用户、线程栅栏同时发起请求。

| 指标 | 结果 |
| --- | --- |
| 成功下单 | 100 |
| 库存不足 | 100 |
| 重复下单 | 0 |
| 其他异常 | 0 |
| DB 订单数 | 100 |
| DB 去重用户数 | 100 |
| DB 库存 | 0 |
| Redis 库存 | 0 |
| Redis 已购集合 | 100 |
| 扣减流水条数 | 100 |
| outbox pending | 0 |
| 请求端到端耗时 | 约 574ms |
| 吞吐 | 约 348 req/s（本机单实例，客户端同机） |

结论：**没有超卖、没有重复订单、消息全部被消费**。这个数字不是“生产 QPS”，只是本地单机可行性验证，不应把它当成线上峰值。

## 8. 已知边界

- RocketMQ 只起了单 Master Broker，没有做集群/主从；生产环境需要 NameServer 集群、Broker 主从、多队列和多消费实例；
- Redis 是单机，没有做 Cluster/分片；生产环境需要把 Lua 的多 key 放到同一个 slot，或改用分片键设计；
- Redis Stream 只作为本地 outbox，单机单 relay；生产环境可以把 outbox 改成 MySQL 本地消息表，或直接用 RocketMQ 事务消息；
- 没有分布式锁 Redisson，互斥锁是手写 `SET NX EX + Lua`，适合讲原理，不适合直接上生产；
- 支付是模拟支付，没有对接真实支付回调；
- `seckill:returned` 是永久 Set，生产环境需要定期归档，避免无限增长。

## 9. 目录

```text
src/main/java/com/hmdp
├── annotation/RateLimit.java          # 限流注解
├── aspect/RateLimitAspect.java        # 限流切面
├── common/                            # ErrorCode/BizException/OrderStatus
├── config/WebExceptionAdvice.java     # 全局异常
├── controller/                        # 用户、店铺、秒杀订单
├── dto/                               # Result/SeckillMessage/OrderStatusDTO
├── mq/
│   ├── RocketMQConstants.java         # Topic/ConsumerGroup 常量
│   ├── SeckillMessageProducer.java    # RocketMQ 生产者
│   └── SeckillRocketMQConsumer.java   # RocketMQ 消费者（重试/死信）
├── service/impl/
│   ├── VoucherOrderServiceImpl.java    # 秒杀核心：Lua+outbox+状态机+幂等
│   ├── ShopServiceImpl.java            # 缓存穿透/击穿/雪崩 + 布隆
│   ├── ShopTypeServiceImpl.java        # 分类缓存 TTL
│   └── UserServiceImpl.java            # 验证码频控/登录锁定
├── task/
│   ├── SeckillOutboxRelay.java         # Redis outbox -> RocketMQ
│   ├── OrderTimeoutTask.java           # 超时关单
│   ├── SeckillReconcileTask.java       # 对账 + 重投 RocketMQ
│   └── SeckillWarmupRunner.java        # 启动预热
└── utils/
    ├── RedisBloomFilter.java           # Redis BitMap 布隆
    ├── RedisRateLimiter.java           # 滑动窗口
    ├── SeckillLedger.java              # 扣减流水
    └── RedisIdWorker.java              # 全局 ID
```
