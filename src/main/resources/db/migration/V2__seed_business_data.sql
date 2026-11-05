-- V2：Agent 工具查询用的演示业务数据（§5）。
-- 确定性生成：每个字段由 hashtext('<字段前缀>' || 序号) 决定，同一版本的 PostgreSQL 上结果相同。
-- 时间锚点是执行迁移当天（Asia/Shanghai）的零点，数据覆盖它之前的 180 天，演示时问“最近 30 天”总能查到数据。
-- 区域分布刻意不均匀（EAST 最多），让按区域统计的结果有区别。

WITH anchor AS (
    SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai' AS t0
), src AS (
    SELECT g,
           abs(hashtext('c' || g)::bigint) AS hc,
           abs(hashtext('r' || g)::bigint) AS hr,
           abs(hashtext('k' || g)::bigint) AS hk,
           abs(hashtext('a' || g)::bigint) AS ha,
           abs(hashtext('s' || g)::bigint) AS hs,
           abs(hashtext('t' || g)::bigint) AS ht
    FROM generate_series(1, 6000) AS g
)
INSERT INTO biz_order(order_no, customer, region, category, amount, status, created_at)
SELECT 'ORD' || lpad(g::text, 7, '0'),
       '客户' || lpad((1 + hc % 800)::text, 4, '0'),
       (ARRAY['EAST', 'EAST', 'EAST', 'NORTH', 'NORTH', 'SOUTH', 'SOUTH', 'WEST', 'CENTRAL', 'CENTRAL'])[1 + hr % 10],
       (ARRAY['ELECTRONICS', 'APPLIANCE', 'APPAREL', 'FOOD', 'BOOKS'])[1 + hk % 5],
       round(20 + (ha % 300000) / 100.0, 2),
       CASE
           WHEN hs % 100 < 8 THEN 'CREATED'
           WHEN hs % 100 < 53 THEN 'PAID'
           WHEN hs % 100 < 88 THEN 'SHIPPED'
           WHEN hs % 100 < 96 THEN 'CANCELLED'
           ELSE 'REFUNDED'
       END,
       t0 - interval '180 days' + (ht % (180 * 86400)) * interval '1 second'
FROM src CROSS JOIN anchor;

WITH anchor AS (
    SELECT date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai' AS t0
), src AS (
    SELECT g,
           abs(hashtext('e' || g)::bigint) AS he,
           abs(hashtext('v' || g)::bigint) AS hv,
           abs(hashtext('r' || g)::bigint) AS hr,
           abs(hashtext('o' || g)::bigint) AS ho,
           abs(hashtext('u' || g)::bigint) AS hu,
           abs(hashtext('d' || g)::bigint) AS hd
    FROM generate_series(1, 1500) AS g
), ev AS (
    SELECT g,
           (ARRAY['PAYMENT_TIMEOUT', 'INVENTORY_MISMATCH', 'API_ERROR', 'LOGISTICS_DELAY'])[1 + he % 4] AS event_type,
           CASE WHEN hv % 100 < 40 THEN 1 WHEN hv % 100 < 75 THEN 2 WHEN hv % 100 < 93 THEN 3 ELSE 4 END AS severity,
           (ARRAY['EAST', 'EAST', 'EAST', 'NORTH', 'NORTH', 'SOUTH', 'SOUTH', 'WEST', 'CENTRAL', 'CENTRAL'])[1 + hr % 10] AS region,
           t0 - interval '180 days' + (ho % (180 * 86400)) * interval '1 second' AS occurred_at,
           hu, hd
    FROM src CROSS JOIN anchor
)
INSERT INTO biz_anomaly_event(event_type, severity, source_system, region, description, occurred_at, resolved_at)
SELECT event_type,
       severity,
       CASE event_type
           WHEN 'PAYMENT_TIMEOUT' THEN 'payment-gateway'
           WHEN 'INVENTORY_MISMATCH' THEN 'wms'
           WHEN 'API_ERROR' THEN 'order-service'
           ELSE 'tms'
       END,
       region,
       '演示事件 #' || g,
       occurred_at,
       -- 约 15% 未处理；已处理的在 5 分钟到 12 小时内解决
       CASE WHEN hu % 100 < 85 THEN occurred_at + (5 + hd % 715) * interval '1 minute' END
FROM ev;
