package com.example.ssds;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Spring context 能不能組起來。
 *
 * <p><b>Flyway 必須關掉</b>：這支測試連的是共用的 Supabase 資料庫，開著 Flyway 會讓
 * 「跑一次單元測試」變成「對共用資料庫套用 migration」——2026-09-11 就這樣把一支
 * 還在審查中的 migration 套進去了。application.properties 的
 * {@code SSDS_FLYWAY_ENABLED} 預設為 false，但套用 migration 的人會在自己的
 * .env 設成 true，於是那台機器上跑測試就會意外動到共用 schema。
 *
 * <p>在這裡寫死 false 而不是靠環境變數：測試不該因為「誰在跑」而有不同的副作用。
 * 要套用 migration 就明確地跑 migration，不要順便。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class SsdsApplicationTests {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void contextLoads() {}

    @Test
    void protectedApiRejectsAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/v1/ai/tasks/999999").contextPath("/api/v1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedUserPassesMethodSecurity() throws Exception {
        mockMvc.perform(get("/api/v1/ai/tasks/999999")
                        .contextPath("/api/v1")
                        .with(user("test-user").roles("BUYER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void buyerCannotInspectKeywordUsageForToggle() throws Exception {
        mockMvc.perform(get("/api/v1/trends/keywords/999999/usage")
                        .contextPath("/api/v1")
                        .with(user("buyer@example.com").roles("BUYER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotUpdateKeywordEnabledState() throws Exception {
        mockMvc.perform(put("/api/v1/trends/keywords/999999/enabled")
                        .contextPath("/api/v1")
                        .contentType("application/json")
                        .content("{\"enabled\":false}")
                        .with(user("viewer@example.com").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dataAdminCanInspectKeywordUsage() throws Exception {
        mockMvc.perform(get("/api/v1/trends/keywords/999999/usage")
                        .contextPath("/api/v1")
                        .with(user("data-admin@example.com").roles("DATA_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sysAdminCanReachKeywordEnabledCommand() throws Exception {
        mockMvc.perform(put("/api/v1/trends/keywords/999999/enabled")
                        .contextPath("/api/v1")
                        .contentType("application/json")
                        .content("{\"enabled\":false}")
                        .with(user("sys-admin@example.com").roles("SYS_ADMIN")))
                .andExpect(status().isNotFound());
    }

}
