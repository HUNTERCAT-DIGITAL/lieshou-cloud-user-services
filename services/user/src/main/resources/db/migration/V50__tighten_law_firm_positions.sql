-- ============================================================
-- V50 · 律所岗位角色收紧（仅 LEGALMIND 版别库保留）
--
-- 背景：V49 曾被以“直接改写已应用迁移”的方式收紧（违反 Flyway 不可改写原则），
--       使所有已部署库在下次升级时校验失败（Migration checksum mismatch for version 49）。
--       本次修正：V49 恢复为最初应用的内容；收紧逻辑改由本迁移（V50）以幂等 SQL 承载，
--       因此任何时点建库的库（新旧库）都能收敛到同一状态。
--
-- 规则：roles 是全局字典（无 tenant_id），只有存在 LEGALMIND 版别租户的库应含律所岗位；
--       其它版别库（GENERIC 等）清理掉**未被任何用户引用**的律所岗位。
-- 幂等：可重复执行（INSERT ... ON CONFLICT DO NOTHING + 条件 DELETE）。
-- ============================================================

-- 1) LEGALMIND 库：确保 9 个律所岗位存在（等价 V49 收紧后的种子）
INSERT INTO roles (code, name, scope, description, is_system)
SELECT seed.code, seed.name, 'TENANT', seed.description, false
FROM (VALUES
  ('MANAGING_PARTNER', '执行合伙人', '律所最高决策/最终审批'),
  ('ADMIN_STAFF',      '行政',       '行政事务/行政审批'),
  ('FINANCE',          '财务',       '财务/回款'),
  ('HR',               '人事',       '人事/招聘'),
  ('CASHIER',          '出纳',       '出纳/收付款'),
  ('FRONT_DESK',       '前台',       '前台接待'),
  ('LAWYER',           '律师',       '执业律师/办案'),
  ('PARTNER',          '合伙人',     '合伙人'),
  ('LEGAL_SECRETARY',  '法律秘书',   '法律秘书/办案辅助')
) AS seed(code, name, description)
WHERE EXISTS (SELECT 1 FROM tenants WHERE edition = 'LEGALMIND')
ON CONFLICT (code) DO NOTHING;

-- 2) 非 LEGALMIND 库：清理历史已种入、且未被任何用户引用的律所岗位
--    （user_roles.role_id 外键亦为兜底：有引用时删除会直接失败，不会误删在用角色）
DELETE FROM roles r
WHERE r.code IN ('MANAGING_PARTNER', 'ADMIN_STAFF', 'FINANCE', 'HR', 'CASHIER',
                 'FRONT_DESK', 'LAWYER', 'PARTNER', 'LEGAL_SECRETARY')
  AND NOT EXISTS (SELECT 1 FROM tenants t WHERE t.edition = 'LEGALMIND')
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.role_id = r.id);
