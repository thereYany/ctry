package com.wherelee.ctry.modules.operations.admin;

import com.wherelee.edu.modules.contracts.identity.RoleAdminOperations;
import com.wherelee.edu.modules.platform.aop.AdminAudit;
import com.wherelee.edu.modules.platform.aop.PermissionCodes;
import com.wherelee.edu.modules.platform.aop.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色与权限管理：租户可自定义角色、授权限、给用户授角色。
 *
 * <p>这是"新项目只改配置就能定义角色"的入口——新增角色不需要改代码。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class RoleAdminController {

    private final RoleAdminOperations roleAdmin;

    public RoleAdminController(RoleAdminOperations roleAdmin) {
        this.roleAdmin = roleAdmin;
    }

    @GetMapping("/roles")
    @RequirePermission(PermissionCodes.ADMIN_ROLE_MANAGE)
    public List<RoleAdminOperations.RoleView> roles() {
        return roleAdmin.listRoles();
    }

    @GetMapping("/permissions")
    @RequirePermission(PermissionCodes.ADMIN_ROLE_MANAGE)
    public List<RoleAdminOperations.PermissionView> permissions() {
        return roleAdmin.listPermissions();
    }

    @PostMapping("/roles")
    @RequirePermission(PermissionCodes.ADMIN_ROLE_MANAGE)
    @AdminAudit(action = "ROLE_CREATE", targetType = "ROLE")
    public RoleAdminOperations.RoleView createRole(@Valid @RequestBody CreateRoleRequest request) {
        return roleAdmin.createRole(request.code(), request.name(), request.description(),
                request.permissions());
    }

    @PutMapping("/roles/{code}/permissions")
    @RequirePermission(PermissionCodes.ADMIN_ROLE_MANAGE)
    @AdminAudit(action = "ROLE_PERMISSIONS_UPDATE", targetType = "ROLE")
    public RoleAdminOperations.RoleView updatePermissions(@PathVariable String code,
                                                          @Valid @RequestBody UpdatePermissionsRequest request) {
        return roleAdmin.updateRolePermissions(code, request.permissions());
    }

    @PutMapping("/users/{userId}/roles")
    @RequirePermission(PermissionCodes.ADMIN_ROLE_MANAGE)
    @AdminAudit(action = "USER_ROLES_UPDATE", targetType = "USER", targetIdArg = 0)
    public AssignResult assignRoles(@PathVariable Long userId,
                                    @Valid @RequestBody AssignRolesRequest request) {
        roleAdmin.assignRoles(userId, request.roles());
        return new AssignResult(userId, request.roles());
    }

    public record CreateRoleRequest(
            @NotBlank(message = "角色码不能为空")
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$", message = "角色码格式不正确")
            String code,
            @NotBlank(message = "角色名不能为空")
            @Size(max = 60, message = "角色名过长")
            String name,
            @Size(max = 200, message = "描述过长")
            String description,
            List<String> permissions) {
    }

    public record UpdatePermissionsRequest(List<String> permissions) {
    }

    public record AssignRolesRequest(@NotEmpty(message = "至少保留一个角色") List<String> roles) {
    }

    public record AssignResult(Long userId, List<String> roles) {
    }
}
