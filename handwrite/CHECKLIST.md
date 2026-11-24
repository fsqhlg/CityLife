# 手敲进度表

> 每写完一个方法，把「完成」打勾，并跑一遍验收。
> 写完告诉我方法名，我做 code review。

## 第 1 轮：缓存 + 登录

| # | 方法 | 文件 | 预计 | 完成 | 验收通过 |
|---|---|---|---|---|---|
| 1 | `queryWithPassThrough` | `ShopServiceImpl` | 30 min | ☐ | ☐ |
| 2 | `queryWithMutex` | `ShopServiceImpl` | 60 min | ☐ | ☐ |
| 3 | `tryLock` / `unlock` / `randomTtl` | `ShopServiceImpl` | 15 min | ☐ | ☐ |
| 4 | `RefreshTokenInterceptor` | `utils/RefreshTokenInterceptor` | 45 min | ☐ | ☐ |

## 第 2 轮：秒杀

| # | 方法 | 文件 | 预计 | 完成 | 验收通过 |
|---|---|---|---|---|---|
| 5 | `RedisIdWorker.nextId` | `utils/RedisIdWorker` | 20 min | ☐ | ☐ |
| 6 | `seckill.lua` | `resources/seckill.lua` | 40 min | ☐ | ☐ |
| 7 | `seckillVoucher` | `VoucherOrderServiceImpl` | 45 min | ☐ | ☐ |
| 8 | `createVoucherOrder` | `VoucherOrderServiceImpl` | 40 min | ☐ | ☐ |
| 9 | `Handler` + `handleVoucherOrder` + `init` | `VoucherOrderServiceImpl` | 30 min | ☐ | ☐ |
| 10 | `addSeckillVoucher` 库存预热 | `VoucherServiceImpl` | 5 min | ☐ | ☐ |

## 第 3 轮：登录细节 + 选做

| # | 方法 | 文件 | 预计 | 完成 | 验收通过 |
|---|---|---|---|---|---|
| 11 | `login` | `UserServiceImpl` | 60 min | ☐ | ☐ |
| 12 | `sendCode` | `UserServiceImpl` | 10 min | ☐ | ☐ |
| 13 | `update` | `ShopServiceImpl` | 15 min | ☐ | ☐ |
| 14 | `queryWithLogicalExpire` + `saveShop2Redis` | `ShopServiceImpl` | 60 min | ☐ | ☐ |
| 15 | `MvcConfig` + `LoginInterceptor` | `config/MvcConfig` | 25 min | ☐ | ☐ |

---

## 记录区

| 日期 | 写了什么 | 卡住的地方 | review 结论 |
|---|---|---|---|
| | | | |
| | | | |
| | | | |
