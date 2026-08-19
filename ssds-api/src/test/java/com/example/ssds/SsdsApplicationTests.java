package com.example.ssds;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "ssds.security.dev.username=test-user",
        "ssds.security.dev.password=test-password"
})
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
    void apiRejectsMissingBasicAuth() throws Exception {
        mockMvc.perform(get("/api/v1/ai/tasks/999999"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validBasicAuthPassesSecurityFilter() throws Exception {
        mockMvc.perform(get("/api/v1/ai/tasks/999999")
                        .with(httpBasic("test-user", "test-password")))
                .andExpect(status().isNotFound());
    }

}
