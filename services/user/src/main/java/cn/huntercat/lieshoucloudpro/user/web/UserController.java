package cn.huntercat.lieshoucloudpro.user.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import cn.huntercat.lieshou.framework.common.dto.UserAuthView;
import cn.huntercat.lieshou.framework.common.web.TenantHeaders;
import cn.huntercat.lieshou.framework.domain.AuditLog;
import cn.huntercat.lieshou.framework.domain.User;
import cn.huntercat.lieshou.framework.service.AuditService;
import cn.huntercat.lieshou.framework.service.UserService;
import cn.huntercat.lieshoucloudpro.user.security.RoleAssignmentGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;

/**
 * 用户管理端点（多租户 · ADR-0022 · 薄壳装配）.
 *
 * <p>业务（创建三分支 / 查重 / 密码编码 / 租户隔离 / 认证视图）在 framework-service {@link UserService}（ADR-0044 阶段
 * 3）；本层仅保留：跨租户查看的 PLATFORM_ADMIN 权限校验、审计记录（req 依赖）、REST 响应组装。错误码契约由 service 抛 {@code BaseException}
 * → GlobalExceptionHandler 统一转 {error, message}。
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "User", description = "User CRUD + auth views (tenant-scoped)")
public class UserController {

  private final UserService userService;
  private final AuditService audit;
  private final RoleAssignmentGuard roleAssignmentGuard;

  public UserController(
      UserService userService, AuditService audit, RoleAssignmentGuard roleAssignmentGuard) {
    this.userService = userService;
    this.audit = audit;
    this.roleAssignmentGuard = roleAssignmentGuard;
  }

  @Operation(
      summary = "List users",
      description = "Tenant-scoped: if X-Tenant-Id header present, only that tenant's users.")
  @ApiResponse(responseCode = "200", description = "List of users (may be empty)")
  @GetMapping
  public ResponseEntity<?> list(
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
      @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader) {
    Long tid = TenantHeaders.parseLong(tenantHeader);
    // 租户内请求强制过滤（ADR-0022 安全关键）：只能看自己租户的用户
    if (tid != null) {
      return ResponseEntity.ok(userService.list(tid));
    }
    // 无租户上下文 = 跨租户平台查看 → 需 PLATFORM_ADMIN（RBAC · ADR-0024）
    if (!AuthRoles.hasAny(rolesHeader, AuthRoles.PLATFORM_ADMIN)) {
      return forbidden();
    }
    return ResponseEntity.ok(userService.list(null));
  }

  @Operation(summary = "Count users")
  @GetMapping("/count")
  public ResponseEntity<?> count(
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
      @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader) {
    Long tid = TenantHeaders.parseLong(tenantHeader);
    if (tid != null) {
      return ResponseEntity.ok(userService.count(tid));
    }
    if (!AuthRoles.hasAny(rolesHeader, AuthRoles.PLATFORM_ADMIN)) {
      return forbidden();
    }
    return ResponseEntity.ok(userService.count(null));
  }

  @Operation(
      summary = "Get user by id",
      description = "Tenant-scoped: cross-tenant access returns 404.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "User found"),
    @ApiResponse(responseCode = "404", description = "User not found (or cross-tenant)")
  })
  @GetMapping("/{id}")
  public ResponseEntity<User> get(
      @Parameter(description = "User id", example = "1") @PathVariable Long id,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
    return ResponseEntity.ok(userService.get(id, TenantHeaders.parseLong(tenantHeader)));
  }

  @Operation(
      summary = "Create user",
      description =
          "Body must include username + displayName. password optional(未设密码首次登录用验证码激活 · 2026-08). email/phone optional.")
  @ApiResponse(responseCode = "200", description = "Created user with assigned id")
  @PostMapping
  public ResponseEntity<?> create(
      @Valid @RequestBody CreateUserRequest body,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
      @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
      HttpServletRequest req) {
    UserService.CreateResult result =
        userService.create(
            body.username(),
            body.displayName(),
            resolvePassword(body.password()),
            body.email(),
            body.phone(),
            body.tenantCode(),
            body.inviteCode(),
            TenantHeaders.parseLong(tenantHeader));
    User saved = result.user();
    // 审计（HTTP 层）：创建用户
    audit.recordSuccess(
        saved.getTenantId(),
        parseUserId(userIdHeader),
        AuditLog.Action.CREATE,
        "USER",
        saved.getId(),
        "创建用户 " + saved.getUsername(),
        req);
    // 返回带租户信息（tenantCode/tenantName/tenantEdition）——auth-service 注册后直接签发 JWT 需要
    return ResponseEntity.ok(
        Map.of(
            "id", saved.getId(),
            "tenantId", saved.getTenantId(),
            "tenantCode", result.tenant().getCode(),
            "tenantName", result.tenant().getName(),
            "tenantEdition",
                result.tenant().getEdition() == null
                    ? "GENERIC"
                    : result.tenant().getEdition().name(),
            "username", saved.getUsername(),
            "displayName", saved.getDisplayName()));
  }

  @Operation(
      summary = "Update user (partial)",
      description =
          "Update displayName/email/phone/status/roles; password only when provided. username immutable.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated user"),
    @ApiResponse(responseCode = "404", description = "User not found"),
    @ApiResponse(responseCode = "400", description = "Invalid status value"),
    @ApiResponse(responseCode = "403", description = "无权分配/调整角色（ROLE_ASSIGN_FORBIDDEN）")
  })
  @PutMapping("/{id}")
  public ResponseEntity<?> update(
      @Parameter(description = "User id", example = "1") @PathVariable Long id,
      @Valid @RequestBody UpdateUserRequest body,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
      @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader,
      @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
      HttpServletRequest req) {
    // 角色授予守卫（2026-09-09 · 低级管理员授高级角色漏洞）：仅在请求会触发角色写入时校验；
    // 空数组与框架 UserService.update 语义一致 = 不改角色，跳过校验
    // 前置 get 仅为取"当前角色"做授权判定（授权基于请求时快照，结果以请求 roles 为准，极端并发窄窗可接受）
    if (body.roles() != null && body.roles().length > 0) {
      User current = userService.get(id, TenantHeaders.parseLong(tenantHeader));
      roleAssignmentGuard.assertCanChangeRoles(
          rolesHeader, current.getRoles(), List.of(body.roles()));
    }
    User saved =
        userService.update(
            id,
            TenantHeaders.parseLong(tenantHeader),
            body.displayName(),
            body.email(),
            body.phone(),
            body.status(),
            body.roles(),
            body.password());
    audit.recordSuccess(
        saved.getTenantId(),
        parseUserId(userIdHeader),
        AuditLog.Action.UPDATE,
        "USER",
        saved.getId(),
        "更新用户 " + saved.getUsername(),
        req);
    return ResponseEntity.ok(saved);
  }

  @Operation(summary = "Delete user by id")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted"),
    @ApiResponse(responseCode = "404", description = "User not found")
  })
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @Parameter(description = "User id") @PathVariable Long id,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
      @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
      HttpServletRequest req) {
    User deleted = userService.delete(id, TenantHeaders.parseLong(tenantHeader));
    audit.recordSuccess(
        deleted.getTenantId(),
        parseUserId(userIdHeader),
        AuditLog.Action.DELETE,
        "USER",
        id,
        "删除用户 " + deleted.getUsername(),
        req);
    return ResponseEntity.noContent().build();
  }

  /** 给 admin 模块本地调用：根据 username 查 User（不含密码 hash）. */
  @Operation(summary = "Get user by username (admin local)")
  @ApiResponse(responseCode = "200", description = "User found")
  @ApiResponse(responseCode = "404", description = "User not found")
  @GetMapping("/by-username/{username}")
  public ResponseEntity<User> byUsername(
      @Parameter(description = "Username", example = "futurewl") @PathVariable String username) {
    return ResponseEntity.ok(userService.findByUsername(username));
  }

  /**
   * 跨租户查该 username 可登录的租户（供登录页同用户名多租户选择）. 仅 service-to-service；gateway 白名单 {@code
   * /api/users/auth/**} 已覆盖。
   */
  @Operation(summary = "Tenant options by username (service-to-service)")
  @GetMapping("/auth/tenant-options")
  public ResponseEntity<?> tenantOptions(
      @Parameter(description = "Username") @org.springframework.web.bind.annotation.RequestParam
          String username) {
    return ResponseEntity.ok(userService.tenantOptions(username));
  }

  /**
   * Phase 5 + Phase 8: 给 auth 模块本地调用：按租户 + username 查鉴权视图（含 passwordHash）.
   *
   * <p>仅 service-to-service 调用；通过 gateway 白名单 {@code /api/users/auth/**} 路径实现.
   */
  @Operation(summary = "Get user auth view by tenant (service-to-service, contains passwordHash)")
  @GetMapping("/auth/by-tenant/{tenantCode}/{username}")
  public ResponseEntity<?> authByTenantAndUsername(
      @Parameter(description = "Tenant code", example = "huntercat") @PathVariable
          String tenantCode,
      @Parameter(description = "Username") @PathVariable String username) {
    return ResponseEntity.ok(userService.authViewByTenantAndUsername(tenantCode, username));
  }

  /** Phase 8: 给 auth-service 用：按租户 + 手机号查鉴权视图（验证码登录 · 手机号租户内唯一 · 2026-08）. */
  @Operation(summary = "Get user auth view by tenant + phone (service-to-service)")
  @GetMapping("/auth/by-tenant-phone/{tenantCode}/{phone}")
  public ResponseEntity<UserAuthView> authByTenantPhone(
      @Parameter(description = "Tenant code", example = "default") @PathVariable String tenantCode,
      @Parameter(description = "Phone", example = "13800000000") @PathVariable String phone) {
    return ResponseEntity.ok(userService.authViewByTenantAndPhone(tenantCode, phone));
  }

  /** Phase 8: 给 auth-service 用：按邮箱查鉴权视图（验证码登录 · ADR-0023）. */
  @Operation(summary = "Get user auth view by email (service-to-service)")
  @GetMapping("/auth/by-email/{email}")
  public ResponseEntity<UserAuthView> authByEmail(
      @Parameter(description = "Email", example = "user@huntercat.cn") @PathVariable String email) {
    return ResponseEntity.ok(userService.authViewByEmail(email));
  }

  /** Phase 6: auth-service 登录成功后回写最近登录时间（幂等，用户不存在静默忽略）. */
  @Operation(summary = "Mark last login (service-to-service, called by auth-service)")
  @PostMapping("/{id}/login-marker")
  public ResponseEntity<Void> markLastLogin(@PathVariable Long id) {
    userService.markLastLogin(id);
    return ResponseEntity.noContent().build();
  }

  /** Phase 5: 占位 health 端点（admin 模块本地调用）. */
  @Operation(summary = "Health probe")
  @GetMapping("/_health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "service", "user");
  }

  private ResponseEntity<Object> forbidden() {
    return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
        .body(Map.of("error", "FORBIDDEN"));
  }

  /** 密码兜底（对齐前端契约：管理员建用户可不设密码，首次登录用手机验证码激活）： 空 → 生成随机强密码（满足 ≥8 位含字母数字，用户不知，仅验证码可登录）。 */
  private static String resolvePassword(String password) {
    if (password != null && !password.isBlank()) {
      return password;
    }
    StringBuilder sb = new StringBuilder(16);
    String letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    String digits = "23456789";
    java.util.Random r = new java.security.SecureRandom();
    for (int i = 0; i < 8; i++) {
      sb.append(letters.charAt(r.nextInt(letters.length())));
    }
    for (int i = 0; i < 8; i++) {
      sb.append(digits.charAt(r.nextInt(digits.length())));
    }
    return sb.toString();
  }

  /** X-User-Id header → Long（gateway 从 JWT uid 注入）；空/非法 → null */
  private static Long parseUserId(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(header.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Phase 5 + Phase 8: CreateUserRequest DTO（tenantCode 可选默认 huntercat；inviteCode 可选则租户/角色来自邀请码）
   */
  public record CreateUserRequest(
      @jakarta.validation.constraints.NotBlank String username,
      @jakarta.validation.constraints.NotBlank String displayName,
      // password 可选(2026-08):管理员建用户可不设密码,首次登录用验证码激活设置
      String password,
      String email,
      String phone,
      String tenantCode,
      String inviteCode) {}

  /** Phase 7: UpdateUserRequest DTO（内联；字段均可选，传入才更新；password 传入才改） */
  public record UpdateUserRequest(
      String displayName,
      String email,
      String phone,
      String status,
      String[] roles,
      String password) {}

  /**
   * 自助修改密码（本人 · 校验原密码）。
   *
   * <p>业务在 framework {@code UserService.changePassword}（OLD_PASSWORD_MISMATCH / INVALID_PASSWORD /
   * USER_NOT_FOUND → GlobalExceptionHandler 统一转 {error, message}）。 不写审计（本人低频操作；如需要可后续补）。
   */
  @Operation(
      summary = "Change own password",
      description = "Self-service: verify old password then update.")
  @PutMapping("/me/password")
  public ResponseEntity<?> changePassword(
      @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
      @RequestBody ChangePasswordRequest body) {
    Long uid = parseUserId(userIdHeader);
    if (uid == null) {
      return ResponseEntity.status(401)
          .body(Map.of("error", "AUTH_REQUIRED", "message", "未登录或身份校验失败"));
    }
    userService.changePassword(uid, body.oldPassword(), body.newPassword());
    return ResponseEntity.ok(Map.of("success", true));
  }

  /** 自助修改密码请求 DTO */
  public record ChangePasswordRequest(
      @jakarta.validation.constraints.NotBlank String oldPassword,
      @jakarta.validation.constraints.NotBlank String newPassword) {}
}
