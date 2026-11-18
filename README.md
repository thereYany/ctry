# ctry · 网课垂直实现

ctry 是一个**独立可运行**的网课业务应用：课程商店、课程订单、课程权益与学习空间（课程内容、播放、进度、教师端内容管理）。

它**不是**通用平台：登录/账号、角色权限、邀请、支付单/退款/对账、租户、审计、告警、对象存储抽象等**内核能力全部来自 btry**（Maven 依赖，不在本仓库）。边界决策见 [ADR 0001](docs/adr/0001-ctry-btry-boundary.md)。

```text
ctry（网课垂直域）                          btry（通用内核，独立仓库/独立部署）
├─ course  课程内容/治理/教师端              ├─ btry-platform  平台 starter（租户/审计/告警/存储/AOP）
├─ learning 学习空间/播放/进度               ├─ btry-contracts 契约（contracts.identity / contracts.payment）
├─ order    课程订单（下单/取消/详情）        ├─ btry-identity  账号/登录/JWT/RBAC/邀请
├─ entitlement 课程权益与履约                 └─ btry-payment   支付单/尝试/回调/发件箱/退款/对账
└─ operations 运营端课程/订单/账号管理
        │
        └── 依赖 contracts.payment.PaymentOperations 发起支付（bizType=COURSE_ORDER, bizNo=订单号）
            实现 payment.api.PaymentSettledListener 监听结算事件并自行履约
```

## 模块与包结构

Maven 父工程 `com.wherelee:ctry-parent` 继承已 install 的 `com.wherelee:btry-parent:0.1.0`，单一应用模块 `ctry-app`：

| 包 | 职责 |
|---|---|
| `com.wherelee.ctry` | `CtryApplication` 启动类（扫描 ctry 业务包 + btry 内核 identity/payment 包） |
| `com.wherelee.ctry.contracts.{course,commerce,learning}` | 垂直域内部跨模块契约（原 monolith 的 contracts.course/learning/commerce 迁入） |
| `com.wherelee.ctry.modules.course.*` | 系列/章节/视频、课程目录、教师端内容管理与上传、课程治理 |
| `com.wherelee.ctry.modules.learning.*` | 学习空间、播放地址、播放日志/统计、学习进度 |
| `com.wherelee.ctry.modules.commerce.order` | 课程商店、课程订单、支付编排（Mock 收银台）、履约补偿 |
| `com.wherelee.ctry.modules.commerce.entitlement` | 课程权益（人工分配/购买生效/过期/回收） |
| `com.wherelee.ctry.modules.operations.*` | 运营端课程/订单/账号管理、播放告警 |
| `com.wherelee.ctry.bootstrap` | 课程种子数据（演示账号/教师/学生/系列/章节/视频/分配） |

## 数据边界

- 独立库 `btry_ctry`（不与 btry 内核共用库表）。
- Flyway 迁移分两段：内核表由 btry 各模块 jar 自带（`db/migration/platform|identity|payment`，V1xx/V2xx/V3xx），本仓库只建自己的表与业务种子（`db/migration/ctry`，V9xx）：`t_series`、`t_series_chapter`、`t_series_video`、`t_order`、`t_student_series`、`t_learning_progress`、`t_playback_log` + 视图 + TEACHER/STUDENT 角色与业务权限。
- btry 不引用任何课程/订单/权益表；两仓库唯一耦合是 `contracts.payment` / `contracts.identity` 契约与支付事件。
- 租户拦截：内核表内置，ctry 表通过 `app.tenant.extra-tables` 声明。

## 快速开始

### 前置

- JDK 21、Maven 3.9+
- MySQL 8、Redis 6+
- 对象存储（腾讯云 COS）凭据；只想本地跑通链路可先用 `COS_SECRET_ID=dummy COS_SECRET_KEY=dummy`（预签名 URL 离线生成，不真正上传/播放）

### 1. 安装 btry 内核到本地 Maven 仓库

```bash
# btry 与本仓库同级，例如 /path/to/btry
cd /path/to/btry/server
mvn -B -DskipTests install
```

### 2. 建库

```sql
CREATE DATABASE btry_ctry CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'btry'@'localhost' IDENTIFIED BY 'btrypass';
GRANT ALL PRIVILEGES ON btry_ctry.* TO 'btry'@'localhost';
```

### 3. 启动

```bash
cd ctry
DB_URL='jdbc:mysql://127.0.0.1:3306/btry_ctry?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' \
DB_USER=btry DB_PASSWORD=btrypass \
COS_SECRET_ID=dummy COS_SECRET_KEY=dummy \
SEED_ADMIN_PASSWORD='Admin12345' SEED_TEACHER_PASSWORD='Teacher12345' SEED_STUDENT_PASSWORD='Student12345' \
mvn -B -pl ctry-app spring-boot:run
```

启动后：`http://127.0.0.1:8084`，健康检查 `/actuator/health`。
种子账号 `admin` / `teacher` / `student` 首次登录强制改密（`must_change_password=true`）；不配置 `SEED_*_PASSWORD` 时随机生成并只在启动日志打印一次。

### 4. 前端（可选）

```bash
cd web
npm install
npm run dev   # http://127.0.0.1:5175，/api 代理到 8084
```

## 测试

```bash
# 单元测试（不含 integration tag）
mvn -B test

# 集成测试：真实 MySQL + Redis + Mock 支付 + 对象存储预签名
IT_DB_URL='jdbc:mysql://127.0.0.1:3306/ctry_it?allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true' \
IT_DB_USER=btry IT_DB_PASSWORD=btrypass \
IT_REDIS_HOST=127.0.0.1 IT_REDIS_PORT=6379 IT_REDIS_DATABASE=15 \
COS_SECRET_ID=dummy COS_SECRET_KEY=dummy \
mvn -B -Pintegration test
```

**全链路验收测试**：`com.wherelee.ctry.integration.CtryEndToEndIntegrationTest`
「登录 → 课程商店 → 下单 → Mock 支付 → 权益发放 → 学习空间 → 播放地址 → 学习进度」，三步事实表互证（t_order FULFILLED / t_student_series ACTIVE / t_payment_order PAID / t_payment_outbox SENT）。

## CI

GitHub Actions：[`.github/workflows/ci.yml`](.github/workflows/ci.yml)
- checkout 本仓库 + 同级 `thereYany/btry`，先 `install` 内核
- MySQL/Redis service 起 `ctry_it`，跑单元测试 + `-Pintegration` 集成测试

## 相关文档

- [ADR 0001：ctry 与 btry 的边界](docs/adr/0001-ctry-btry-boundary.md)
- btry 内核仓库：<https://github.com/thereYany/btry>
