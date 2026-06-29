package com.commerce.api.global.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 레이트 리밋 HTTP 통합 — 같은 이메일로 로그인을 한도 초과로 때리면 429가 나오는지(컨트롤러↔리미터 배선).
 * 다른 테스트는 ratelimit OFF지만 여기선 @TestPropertySource로 켠다(고유 이메일이라 카운터 충돌 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.ratelimit.enabled=true")
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("로그인 - 같은 이메일 6회째는 429(레이트 리밋, 인증 결과보다 먼저)")
    void login_rateLimited() throws Exception {
        String body = "{\"email\":\"rl-" + System.nanoTime() + "@commerce.com\",\"password\":\"wrongpass1\"}";

        for (int i = 0; i < 5; i++) {   // 1~5회: 한도 내(인증 실패하지만 429는 아님)
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());   // 6회째 429
    }
}
