-- 演示用秒杀券：库存 100，活动时间覆盖当前时间
-- 导入后重启应用，SeckillWarmupRunner 会自动把库存同步到 Redis。
INSERT INTO tb_voucher (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
VALUES (100, 1, '100元代金券（演示秒杀）', '库存100张，先到先得', '仅限堂食\n不兑现、不找零', 5000, 10000, 1, 1)
ON DUPLICATE KEY UPDATE
    title = VALUES(title),
    sub_title = VALUES(sub_title),
    rules = VALUES(rules),
    pay_value = VALUES(pay_value),
    actual_value = VALUES(actual_value),
    type = VALUES(type),
    status = VALUES(status);

INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time)
VALUES (100, 100, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 7 DAY)
ON DUPLICATE KEY UPDATE
    stock = VALUES(stock),
    begin_time = VALUES(begin_time),
    end_time = VALUES(end_time);
