package com.commerce.api.global.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 레이트 리밋 HTTP 통합 — 같은 이메일로 로그인을 한도 초과로 때리면 429가 나오는지.
 * 이제 제한은 컨트롤러 코드가 아니라 <b>{@code @RateLimit} + RateLimitAspect</b>가 건다 → 이 테스트가
 * "애너테이션이 실제 요청 경로에서 작동한다"는 배선 증거다(AOP는 @SpringBootTest에서만 살아 있다).
 *
 * <p>다른 테스트는 ratelimit OFF지만 여기선 @TestPropertySource로 켠다(고유 이메일이라 카운터 충돌 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.ratelimit.enabled=true")
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("로그인 - 같은 이메일 6회째는 429 + Retry-After(60초): 언제 다시 오면 되는지 알려준다")
    void login_rateLimited() throws Exception {
        String body = "{\"email\":\"rl-" + System.nanoTime() + "@commerce.com\",\"password\":\"wrongpass1\"}";

        for (int i = 0; i < 5; i++) {   // 1~5회: 한도 내(인증 실패하지만 429는 아님)
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())                        // 6회째 429
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));     // 윈도우 1분
    }
}
