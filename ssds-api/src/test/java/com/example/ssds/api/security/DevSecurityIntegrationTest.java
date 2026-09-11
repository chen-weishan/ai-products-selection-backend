package com.example.ssds.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class DevSecurityIntegrationTest {
    @Configuration
    @EnableWebMvc
    static class WebConfig {}

    @RestController
    static class ProtectedEndpoint {
        @GetMapping("/security-probe")
        @PreAuthorize("hasRole('SYS_ADMIN')")
        public String probe() { return "ok"; }
    }

    @Test
    void devPackagesProvideOneChainWithBasicAuthAndCors() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().setActiveProfiles("dev");
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "ssds.security.dev.username=sysadmin@ssds.dev",
                    "ssds.security.dev.password=test-password",
                    "ssds.cors.allowed-origins=http://localhost:4200");
            // Scan both former locations so duplicate configuration beans fail this test.
            context.scan("com.example.ssds.api.security", "com.example.ssds.api.common.config");
            context.register(WebConfig.class, ProtectedEndpoint.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(SecurityFilterChain.class).size());
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            mvc.perform(get("/security-probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/security-probe").with(httpBasic("sysadmin@ssds.dev", "wrong")))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/security-probe").with(httpBasic("sysadmin@ssds.dev", "test-password")))
                    .andExpect(status().isOk());
            mvc.perform(options("/security-probe")
                    .header("Origin", "http://localhost:4200")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        }
    }
}
