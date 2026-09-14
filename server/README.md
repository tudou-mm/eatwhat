# 「吃什么」后端 · Java Spring Boot

> 配套前端原型：仓库根目录的 `client/` `merchant/` `admin/`
> 接口契约见 `docs/05-后端接口契约.md`

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+ |
| MySQL | 8.0+（可选，默认用 H2 内存库，**开箱即跑**） |

## 快速开始

### 方式一：零依赖直接跑（推荐先用这个验证）

不装 MySQL 也能跑，默认用 H2 内存数据库并自动灌入演示数据：

```bash
cd server
mvn spring-boot:run
```

启动后访问 <http://localhost:8080/api/health> 应返回 `{"code":0,"data":"ok"}`。

### 方式二：接真实 MySQL

1. 建库：
   ```bash
   mysql -uroot -p -e "CREATE DATABASE eatwhat DEFAULT CHARSET utf8mb4;"
   ```

2. 执行建表脚本：
   ```bash
   mysql -uroot -p eatwhat < src/main/resources/db/schema.sql
   ```

3. 改 `src/main/resources/application-mysql.yml` 里的账号密码。

4. 用 mysql profile 启动：
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=mysql
   ```

## 前端如何接上

前端原型默认走 `assets/data/mock.js` 的本地假数据。要切换到真后端：

1. 启动后端（默认 8080）
2. 打开任意前端页面，在浏览器控制台执行：
   ```js
   localStorage.setItem('useApi', '1'); location.reload();
   ```
3. 想切回假数据：`localStorage.removeItem('useApi'); location.reload();`

适配层在 `assets/js/api.js`，它**保持与 `MOCK` 完全相同的同步函数签名**，
所以三端 22 个页面无需改动一行代码。

## 目录结构

```
server/
├── pom.xml
├── src/main/java/com/eatwhat/
│   ├── EatWhatApplication.java       启动类
│   ├── common/
│   │   ├── R.java                     统一响应包装 {code,msg,data}
│   │   ├── BizException.java          业务异常
│   │   └── GlobalExceptionHandler.java 全局异常处理
│   ├── config/
│   │   ├── CorsConfig.java            跨域（前端 file:// 或 localhost 都能调）
│   │   └── DataSeeder.java            首次启动灌演示数据
│   ├── entity/                        实体（对应表）
│   ├── repository/                    Spring Data JPA
│   ├── service/                       业务逻辑（核心规则都在这里）
│   └── controller/                    对外接口
└── src/main/resources/
    ├── application.yml                H2 默认配置
    ├── application-mysql.yml          MySQL 配置
    └── db/schema.sql                  建表 DDL
```

## 核心业务规则所在位置

规则不是散在 controller 里的，集中在 service：

| 规则 | 位置 |
|---|---|
| 每店 24h 限发 / 平台覆盖 | `DishService.checkPublishLimit()` |
| 价格自动归档档位 | `DishService.resolveTier()` |
| 时间衰减权重 | `RankService.timeDecay()` |
| 排序：置顶 > 权重 > 距离 > 衰减 | `RankService.sortShops()` |
| 随机流排除已浏览 | `FeedService.buildRandomFeed()` |
| 违规三级处理 | `ShopService.mute/ban()`、`UserService.warn/ban()` |
