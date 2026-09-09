package cn.huntercat.lieshoucloudpro.user.security;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import cn.huntercat.lieshou.framework.common.api.BaseException;
import cn.huntercat.lieshou.framework.domain.Role;
import cn.huntercat.lieshou.framework.service.RoleService;

/**
 * 角色授予守卫（2026-09-09 · 低级管理员授高级角色漏洞修复）.
 *
 * <p>规则（按调用者角色定"可授予集" G）：PLATFORM_ADMIN → 全部角色；TENANT_ADMIN → 仅 scope=TENANT
 * 角色（不在角色表中的未知码视作 TENANT scope，放行给业务层报 INVALID_ROLE）；其余（USER/值班员/无角色）→
 * 空集。请求 roles 为空或与目标当前角色集合相等（资料编辑原样回传）→ 放行；否则要求 current ∪ requested ⊆ G，
 * 违反抛 403 {@code ROLE_ASSIGN_FORBIDDEN}。
 */
@Component
public class RoleAssignmentGuard {

  private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
  private static final String TENANT_ADMIN = "TENANT_ADMIN";

  private final RoleService roleService;

  public RoleAssignmentGuard(RoleService roleService) {
    this.roleService = roleService;
  }

  /**
   * 角色集合变更校验。
   *
   * @param callerRolesHeader X-User-Roles（逗号分隔，可 null/空）
   * @param currentRoles 目标用户当前角色（来自 DB，非 null）
   * @param requestedRoles 请求希望设置的角色码（非 null 但可能为空）
   */
  public void assertCanChangeRoles(
      String callerRolesHeader, List<Role> currentRoles, List<String> requestedRoles) {
    if (requestedRoles == null || requestedRoles.isEmpty()) {
      return;
    }
    Set<String> current = roleCodes(currentRoles);
    Set<String> requested = new HashSet<>(requestedRoles);
    if (current.equals(requested)) {
      return; // 角色集合不变 = 资料编辑（ProfilePage 原样回传）
    }
    Set<String> callerRoles = parseRoles(callerRolesHeader);
    Map<String, Role.Scope> scopeByCode = scopeByCode();
    Set<String> all = new HashSet<>(current);
    all.addAll(requested); // current ∪ requested：任何一侧越界都拒绝（含"降级掉平台管理员角色"）
    for (String code : all) {
      if (!grantable(callerRoles, scopeByCode, code)) {
        throw forbidden();
      }
    }
  }

  /** 调用者是否可授予/持有单角色码（未知码不在表中，按 TENANT scope 放行，由业务层报 INVALID_ROLE）. */
  private boolean grantable(Set<String> callerRoles, Map<String, Role.Scope> scopeByCode, String roleCode) {
    if (callerRoles.contains(PLATFORM_ADMIN)) {
      return true;
    }
    if (callerRoles.contains(TENANT_ADMIN)) {
      // get()==null（未知码）≠ PLATFORM → 放行；PLATFORM 域角色（含自定义）仅平台管理员可授
      return scopeByCode.get(roleCode) != Role.Scope.PLATFORM;
    }
    return false; // USER / 值班员 / 无角色：不可授予或调整任何角色（仅集合不变的资料编辑放行）
  }

  private BaseException forbidden() {
    return new BaseException(
        "ROLE_ASSIGN_FORBIDDEN",
        HttpStatus.FORBIDDEN,
        "无权分配/调整该用户的角色：平台级角色仅平台管理员可授予，租户级角色需租户管理员及以上");
  }

  private Map<String, Role.Scope> scopeByCode() {
    Map<String, Role.Scope> map = new HashMap<>();
    for (Role r : roleService.list()) {
      map.put(r.getCode(), r.getScope());
    }
    return map;
  }

  private static Set<String> roleCodes(List<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return Set.of();
    }
    return roles.stream().map(Role::getCode).collect(Collectors.toSet());
  }

  private static Set<String> parseRoles(String rolesHeader) {
    if (rolesHeader == null || rolesHeader.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(rolesHeader.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }
}
