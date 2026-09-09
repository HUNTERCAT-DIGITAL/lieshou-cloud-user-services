package cn.huntercat.lieshoucloudpro.user.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import cn.huntercat.lieshou.framework.common.api.BaseException;
import cn.huntercat.lieshou.framework.domain.Role;
import cn.huntercat.lieshou.framework.service.RoleService;

/** 角色授予守卫单测：谁可授予哪些角色（2026-09-09 设计 §2.1）. */
@ExtendWith(MockitoExtension.class)
// LENIENT：部分用例（requested 为空 / 角色集合不变）提前 return，不会触碰 roleService.list()；
// 中心化 stub 若用默认 STRICT_STUBS 会误报 UnnecessaryStubbing，故放宽。
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleAssignmentGuardTest {

  @Mock RoleService roleService;

  RoleAssignmentGuard guard;

  @BeforeEach
  void setUp() {
    when(roleService.list())
        .thenReturn(
            List.of(
                role("PLATFORM_ADMIN", "平台管理员", Role.Scope.PLATFORM, true),
                role("TENANT_ADMIN", "租户管理员", Role.Scope.TENANT, true),
                role("DUTY_OFFICER", "值班员", Role.Scope.TENANT, true),
                role("USER", "普通用户", Role.Scope.TENANT, true),
                role("CUSTOM_PLATFORM", "平台自定义", Role.Scope.PLATFORM, false)));
    guard = new RoleAssignmentGuard(roleService);
  }

  private static Role role(String code, String name, Role.Scope scope, boolean system) {
    return new Role(code, name, scope, name + "描述", system);
  }

  @Test
  void 平台管理员可授予平台域角色() {
    assertDoesNotThrow(
        () ->
            guard.assertCanChangeRoles(
                "PLATFORM_ADMIN", roles("USER"), List.of("PLATFORM_ADMIN")));
  }

  @Test
  void 平台管理员可授予租户域角色() {
    assertDoesNotThrow(
        () -> guard.assertCanChangeRoles("PLATFORM_ADMIN", roles("USER"), List.of("TENANT_ADMIN")));
  }

  @Test
  void 租户管理员可授予租户域角色() {
    assertDoesNotThrow(
        () -> guard.assertCanChangeRoles("TENANT_ADMIN", roles("USER"), List.of("DUTY_OFFICER")));
  }

  @Test
  void 租户管理员可授予TENANT_ADMIN() {
    assertDoesNotThrow(
        () -> guard.assertCanChangeRoles("TENANT_ADMIN", roles("USER"), List.of("TENANT_ADMIN")));
  }

  @Test
  void 租户管理员授平台域角色被拒() {
    assertThrows(
        BaseException.class,
        () ->
            guard.assertCanChangeRoles(
                "TENANT_ADMIN", roles("USER"), List.of("PLATFORM_ADMIN")));
  }

  @Test
  void 租户管理员改平台管理员用户角色被拒() {
    assertThrows(
        BaseException.class,
        () ->
            guard.assertCanChangeRoles(
                "TENANT_ADMIN", roles("PLATFORM_ADMIN"), List.of("USER")));
  }

  @Test
  void 普通用户改自己角色被拒() {
    assertThrows(
        BaseException.class,
        () -> guard.assertCanChangeRoles("USER", roles("USER"), List.of("DUTY_OFFICER")));
  }

  @Test
  void 普通用户角色集合不变放行() {
    assertDoesNotThrow(() -> guard.assertCanChangeRoles("USER", roles("USER"), List.of("USER")));
  }

  @Test
  void 无角色header角色集合不变放行() {
    assertDoesNotThrow(() -> guard.assertCanChangeRoles(null, roles("USER"), List.of("USER")));
  }

  @Test
  void requested为空不校验() {
    assertDoesNotThrow(() -> guard.assertCanChangeRoles("USER", roles("USER"), List.of()));
  }

  @Test
  void 未知角色码放行交给业务层报INVALID_ROLE() {
    assertDoesNotThrow(
        () ->
            guard.assertCanChangeRoles(
                "TENANT_ADMIN", roles("USER"), List.of("NOT_IN_TABLE")));
  }

  /** Role → List（便于测试内聚） */
  private static List<Role> roles(String... codes) {
    return java.util.Arrays.stream(codes)
        .map(c -> new Role(c, c, Role.Scope.TENANT, null, false))
        .toList();
  }
}
