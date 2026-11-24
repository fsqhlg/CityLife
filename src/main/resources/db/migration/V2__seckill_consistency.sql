-- M1/M4：秒杀订单增强（状态机 + 幂等 + 对账索引）
ALTER TABLE `tb_voucher_order`
    ADD COLUMN `close_type` TINYINT(1) NULL COMMENT '关闭类型：1 用户主动取消，2 超时未支付自动关闭' AFTER `status`,
    ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`),
    ADD KEY `idx_status_create_time` (`status`, `create_time`);
