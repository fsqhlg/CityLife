# hmdp 手敲清单（登录 / 缓存 / 秒杀）

> 这是给你**照着读、然后自己敲**的代码清单，不是学习笔记。
> 目标：把前 3 个模块里最值得练的 9 段代码，亲手写一遍。

---

## 一、怎么用这份清单

1. 先通读对应文件里的代码，理解每一步在干什么；
2. 打开项目里对应的 Java 文件，**把方法体清空**（先备份，见下）；
3. 合上这份清单，凭理解自己写；
4. 写完用文件末尾的「验收」跑一遍；
5. 卡住 15 分钟再找我，我只给提示，不给整段答案；
6. 写完告诉我方法名，我做 code review。

### 先备份

项目没有 Git 也没关系，直接复制一份：

```
D:\新建文件夹 (3)\资料\hm-dianping\hm-dianping
→ 复制成 hmdianping-backup
```

或者用 IDEA 的 Local History（右键文件 → Local History → Show History）随时回滚。

---

## 二、环境（已经帮你配好）

VS Code 里打开项目文件夹即可，`.vscode/` 已配置：

| 配置 | 内容 |
|---|---|
| JDK | `D:\123\IntelliJ IDEA Community Edition 2025.2.1\jbr`（JDK 21） |
| Maven | `D:\java_study\apache-maven-3.9.4` |
| 本地仓库 | `D:\java_study\apache-maven-3.9.4\mvn_repo` |
| Lombok | 通过 `.vscode/mvn-settings.xml` 临时覆盖为 1.18.46（项目自带 1.18.16 不兼容 JDK 21，**没改 pom**） |

VS Code 里可用的操作：

- `Ctrl+Shift+B` → `mvn compile`
- `F5` → 启动 `HmDianPingApplication`（launch.json 已配）
- 终端里手动跑：
  ```bat
  set JAVA_HOME=D:\123\IntelliJ IDEA Community Edition 2025.2.1\jbr
  mvn -s .vscode\mvn-settings.xml compile
  ```

> 前提：MySQL 和 Redis 要开着（Redis 用资料里的 `redis-server.exe redis.windows.conf`）。

---

## 三、清单与顺序

### 第 1 轮：缓存 + 登录（3~4 小时）

| # | 方法 | 文件 | 难度 |
|---|---|---|---|
| 1 | `queryWithPassThrough` | `ShopServiceImpl` | ⭐⭐ |
| 2 | `queryWithMutex` | `ShopServiceImpl` | ⭐⭐⭐⭐ |
| 3 | `RefreshTokenInterceptor` | `utils/RefreshTokenInterceptor` | ⭐⭐⭐ |

### 第 2 轮：秒杀（2~3 小时）

| # | 方法 | 文件 | 难度 |
|---|---|---|---|
| 4 | `RedisIdWorker.nextId` | `utils/RedisIdWorker` | ⭐⭐ |
| 5 | `seckill.lua` | `resources/seckill.lua` | ⭐⭐⭐ |
| 6 | `seckillVoucher` | `VoucherOrderServiceImpl` | ⭐⭐⭐ |
| 7 | `createVoucherOrder` | `VoucherOrderServiceImpl` | ⭐⭐⭐ |
| 8 | `Handler` + `handleVoucherOrder` | `VoucherOrderServiceImpl` | ⭐⭐⭐ |

### 第 3 轮：登录收尾 + 选做（1~2 小时）

| # | 方法 | 文件 | 难度 |
|---|---|---|---|
| 9 | `login` | `UserServiceImpl` | ⭐⭐⭐ |
| 10 | `sendCode` | `UserServiceImpl` | ⭐ |
| 11 | `update` / `tryLock` / `unlock` | `ShopServiceImpl` | ⭐ |
| 12 | `queryWithLogicalExpire` + `saveShop2Redis` | `ShopServiceImpl` | ⭐⭐⭐⭐ |
| 13 | `MvcConfig.addInterceptors` + `LoginInterceptor` | `config/MvcConfig` | ⭐⭐ |

---

## 四、时间紧就只敲这 5 段

1. `queryWithMutex`（分布式锁）
2. `RefreshTokenInterceptor`（token + ThreadLocal）
3. `seckill.lua`（原子扣减）
4. `seckillVoucher`（异步下单）
5. `createVoucherOrder`（事务 + 防超卖）

---

## 五、文件说明

| 文件 | 内容 |
|---|---|
| `01-登录.md` | 登录核心：`login`、`RefreshTokenInterceptor`，附 `sendCode` / `MvcConfig` |
| `02-缓存.md` | 穿透、互斥锁、逻辑过期、更新删缓存、锁工具方法 |
| `03-秒杀.md` | 全局 ID、Lua、异步队列、落库事务、库存预热 |
| `CHECKLIST.md` | 打勾用的进度表 |

---

## 六、写完之后

把方法名发给我（例如「我写完了 queryWithMutex」），我会读代码检查：

- 锁是否 `NX EX`、是否可能误删别人的锁、`finally` 位置对不对；
- 事务是否走代理、`@Transactional` 有没有生效；
- 缓存空值 TTL、`isBlank` 判断顺序；
- Lua 参数顺序、nil 判断、返回值映射；
- 有没有「先查后改」的并发漏洞。

每次只指出 1~2 个具体问题 + 一句改法，不整段重写。
