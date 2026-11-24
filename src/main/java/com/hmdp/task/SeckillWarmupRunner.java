package com.hmdp.task;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisBloomFilter;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * M6 + M2：启动预热。
 * <p>
 * 1. 把店铺 id 灌进布隆过滤器（只做一次，ready key 存在就跳过）；
 * 2. 把秒杀券库存从数据库同步到 Redis（setIfAbsent，重启不会覆盖已扣减的库存）。
 */
@Slf4j
@Component
@Order(1)
public class SeckillWarmupRunner implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisBloomFilter redisBloomFilter;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        warmupBloomFilter();
        warmupSeckillStock();
    }

    private void warmupBloomFilter() {
        try {
            List<Shop> shops = shopService.list();
            List<Long> ids = shops.stream().map(Shop::getId).collect(Collectors.toList());
            redisBloomFilter.initIfAbsent(ids);
            log.info("店铺布隆过滤器预热完成，shopCount={}", ids.size());
        } catch (Exception e) {
            log.error("店铺布隆过滤器预热失败，将在请求时 fail-open", e);
        }
    }

    private void warmupSeckillStock() {
        try {
            List<SeckillVoucher> vouchers = seckillVoucherService.list();
            for (SeckillVoucher voucher : vouchers) {
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
                String initKey = RedisConstants.SECKILL_STOCK_INIT_KEY + voucher.getVoucherId();
                String stock = String.valueOf(voucher.getStock());
                stringRedisTemplate.opsForValue().setIfAbsent(stockKey, stock);
                stringRedisTemplate.opsForValue().setIfAbsent(initKey, stock);
            }
            log.info("秒杀库存预热完成，voucherCount={}", vouchers.size());
        } catch (Exception e) {
            log.error("秒杀库存预热失败", e);
        }
    }
}
