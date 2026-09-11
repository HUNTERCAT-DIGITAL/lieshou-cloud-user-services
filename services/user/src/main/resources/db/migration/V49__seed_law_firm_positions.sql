-- ============================================================
-- V49 · 律所岗位角色字典（9 种 · 用户-角色关联复用 user_roles）
--
-- 岗位作为 roles（scope=TENANT · 非 system · 可删），用户经 user_roles 关联。
-- 后续审批规则支持按岗位匹配（用章行政/执行合伙人等）。
-- ============================================================

INSERT INTO roles (code, name, scope, description, is_system) VALUES
  ('MANAGING_PARTNER', '执行合伙人', 'TENANT', '律所最高决策/最终审批', false),
  ('ADMIN_STAFF',      '行政',       'TENANT', '行政事务/行政审批',   false),
  ('FINANCE',          '财务',       'TENANT', '财务/回款',            false),
  ('HR',               '人事',       'TENANT', '人事/招聘',            false),
  ('CASHIER',          '出纳',       'TENANT', '出纳/收付款',          false),
  ('FRONT_DESK',       '前台',       'TENANT', '前台接待',             false),
  ('LAWYER',           '律师',       'TENANT', '执业律师/办案',        false),
  ('PARTNER',          '合伙人',     'TENANT', '合伙人',               false),
  ('LEGAL_SECRETARY',  '法律秘书',   'TENANT', '法律秘书/办案辅助',    false)
ON CONFLICT (code) DO NOTHING;
