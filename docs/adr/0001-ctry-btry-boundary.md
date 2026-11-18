# ADR 0001：ctry（网课垂直实现）与 btry（通用内核）的边界

- 状态：已采纳（2026-11-18）
- 背景：btry 原本是「录播课程平台」单体（identity / course / learning / commerce / operations 五模块 + platform 底座）。为让内核能力可被多个垂直业务复用，先做内核/垂直拆分：内核独立成 `btry-platform` / `btry-contracts` / `btry-identity` / `btry-payment`，网课业务全部迁到 ctry。

## 决策

### 1. 支付留在 btry，订单与权益放 ctry

**支付在 btry**：支付单、支付尝试、回调验签、事务性发件箱、退款重试、渠道对账，全部与"卖的是什么"无关。它们只接受 `bizType + bizNo + amount + currency + subject`，不认识课程、订单状态机、权益有效期。任何垂直域（课程订单、资料包、会员……）都应复用同一套支付安全能力（幂等键、金额校验、回调去重、发件箱 at-least-once、退款补偿、对账）。

**订单/权益在 ctry**：订单是课程商品语义（课程快照、价格快照、一次下单一次支付、取消/过期关单），权益是"谁因为哪张订单获得哪门课的观看权、有效期多长、是否回收"。二者是网课业务规则，只有 ctry 该知道。

**交互方式（支付反转）**：

```text
ctry 下单 → PaymentOperations.initiate(bizType="COURSE_ORDER", bizNo=orderNo)
          → btry 建支付单/尝试并返回收银台地址（paymentNo 落回 t_order.payment_no）
渠道回调/查单 → btry 确认支付单 → 写 t_payment_outbox（PAYMENT_SUCCEEDED / FAILED / EXPIRED）
          → PaymentOutboxDispatcher（at-least-once）投递 PaymentSettledEvent
          → ctry 的 OrderPaymentSettledListener 消费事件：markPaid → OrderFulfillmentService → grantOrder（幂等）
```

依赖方向只允许 `ctry → btry`（contracts / payment.api / identity 契约）。btry 不依赖、不 import 任何 ctry 类或课程表；新增垂直域只需要实现 `PaymentSettledListener` 并按 `bizType` 过滤自己的事件。

### 2. 身份留在 btry，网课业务角色/权限码放 ctry

账号、登录、JWT、RBAC、邀请（`UserInvitation` 已通用化为 `roleCode`）是内核能力。btry 只内置 `ADMIN` 角色与内核权限码；`TEACHER` / `STUDENT` 角色、`course:*` / `order:*` / `learning:*` / `teacher:*` / `admin:order|series:*` 权限码及其角色授权，由 ctry 的 Flyway 迁移（`V904__business_roles_permissions.sql`）以**数据**方式写入——内核代码不认识这些语义，只做权限码校验。

ctry 的运营/教师接口通过内核 `AccountQuery` / `UserAccountOperations` 契约读写账号，不直连 `t_user`（`bootstrap` 种子数据例外，与内核种子同层，见 §5）。

### 3. 数据边界：独立库 + 各建各的表

- ctry 使用独立库 `btry_ctry`；内核表（`t_user`、`t_user_invitation`、`t_role`、`t_permission`、`t_payment_*`、`t_recon_*`、`t_refund`、`t_alert_log`、`t_admin_audit_log` 等）由 btry 各模块 jar 内置迁移创建（V1xx/V2xx/V3xx）。
- ctry 自己的表（`t_series`、`t_series_chapter`、`t_series_video`、`t_order`、`t_student_series`、`t_learning_progress`、`t_playback_log`）由本仓库迁移创建（`db/migration/ctry`，V9xx 避开内核版本段）。
- 订单表只保存 `payment_no` 引用（外加 `pay_channel` / `provider_trade_no` 支付结果快照，便于运营展示与排查），不复制支付尝试/回调明细；对账、退款明细从内核契约查询。
- 租户拦截：内核表内置在 btry `TenantSqlRewriter`，ctry 表通过 `app.tenant.extra-tables` 声明，SQL 未带 `tenant_id` 时强制拒绝。

### 4. 对象存储/播放地址

COS 客户端与签名 URL 缓存（单飞锁、Redis + 本地缓存、降级）已通用化为 btry `platform.storage`：`CosObjectStorage` + `SignedUrlCache`，通过 `app.storage.enabled=true` 启用。ctry 只做垂直语义：视频对象 key 规则、上传元数据校验、播放归属校验、播放日志/统计。

### 5. 已知折中（有意保留，可后续拆分）

1. **Mock 收银台/模拟支付场景在 ctry**：`OrderMockPaymentService` 需要订单标题/金额展示，并调用内核公开的 `PaymentConfirmationService` + `PaymentOutboxDispatcher` 完成"模拟渠道事件"。这是测试/演示用途的适配层；支付宝真实回调完全在 btry。
2. **运营端账号/角色/对账接口在 ctry 留有适配层**（`modules.operations.admin`）：沿用旧运营台前端路径，内部全部调用内核契约/服务，不持有内核表 SQL。生产可以把这些接口收敛到独立运营台。
3. **种子数据直写表**：`bootstrap` 的演示账号/课程种子与 btry 内核种子一样，属于装配层，直接写 `t_user`/课程表；非种子代码禁止直连内核表（由 ctry 架构测试与 btry 数据边界门禁约束）。

## 后果

- 新增垂直域（资料包/会员等）无需改动支付、身份内核，只需新的 bizType + PaymentSettledListener + 自己的库表与业务角色。
- 课程订单/权益演进不影响内核；支付安全能力集中维护、所有垂直域受益。
- ctry 与 btry 必须一起部署同一版本线的内核 jar（`ctry-parent` 依赖 `btry-parent:0.1.0` 的模块版本）；升级内核需要回归 ctry 全链路测试。
