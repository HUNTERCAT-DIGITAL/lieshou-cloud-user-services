-- ============================================================
-- V49 · 律所岗位角色字典（9 种 · 用户-角色关联复用 user_roles）
--
-- 岗位作为 roles（scope=TENANT · 非 system · 可删），用户经 user_roles 关联。
-- 后续审批规则支持按岗位匹配（用章行政/执行合伙人等）。
--
-- 2026-09-09 收紧：仅当本库存在 LEGALMIND 版别租户（edition='LEGALMIND'）时才种入——
-- roles 为全局字典，无条件 INSERT 会把律所岗位污染到 GENERIC/其他版别客户
-- （如 dwjk 电网监控，租户管理员下拉出现财务/律师等无关角色）。
-- 单套库一个产品线部署下：legalmind 库含 LEGALMIND 租户 → 正常种入；其它客户库不种。
-- ============================================================

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
