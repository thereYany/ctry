package com.wherelee.ctry.modules.operations.integration;

import com.wherelee.ctry.modules.commerce.integration.CommerceIntegrationTestSupport;
import com.wherelee.edu.modules.identity.security.JwtService;
import com.wherelee.edu.modules.identity.user.User;
import com.wherelee.edu.modules.identity.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P3 证据测试：接口鉴权由"角色"切换为"权限码"。
 *
 * <p>验证三件事：越权 403、管理员放行、自定义角色不改代码即可生效。</p>
 */
@AutoConfigureMockMvc
class RbacWebIntegrationTest extends CommerceIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("学员访问管理端接口被 403 拦截；管理员放行")
    void roleBasedAccessIsReplacedByPermission() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(fx.studentId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(fx.adminId())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("自定义角色 + 授权限码：无需改代码即可访问受控接口")
    void customRoleGrantsAccessWithoutCodeChange() throws Exception {
        String roleCode = "AUDITOR" + (System.nanoTime() % 100000);
        mockMvc.perform(post("/api/admin/roles")
                        .header("Authorization", bearer(fx.adminId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"审计员","description":"只读审计",
                                 "permissions":["admin:series:manage"]}
                                """.formatted(roleCode)))
                .andExpect(status().isOk());

        Long auditorId = insertUserDirect("auditor_" + roleCode.toLowerCase(), roleCode);
        // 先访问一次：权限为空，应 403（同时把"无权限"结果放进缓存）
        mockMvc.perform(get("/api/admin/content/series").header("Authorization", bearer(auditorId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/users/{id}/roles", auditorId)
                        .header("Authorization", bearer(fx.adminId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"%s\"]}".formatted(roleCode)))
                .andExpect(status().isOk());

        // 自定义角色被授权 admin:series:manage 后立即可访问；学员仍然 403
        mockMvc.perform(get("/api/admin/content/series").header("Authorization", bearer(auditorId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/content/series").header("Authorization", bearer(fx.studentId())))
                .andExpect(status().isForbidden());
    }

    private String bearer(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + jwtService.createToken(user);
    }

    private Long insertUserDirect(String username, String role) {
        jdbc.update("""
                INSERT INTO t_user (username, password_hash, nickname, role, status,
                                    must_change_password, token_version)
                VALUES (?, 'integration-not-used', ?, ?, 'ACTIVE', FALSE, 0)
                """, username, username, role);
        return jdbc.queryForObject("SELECT id FROM t_user WHERE username = ?", Long.class, username);
    }
}
