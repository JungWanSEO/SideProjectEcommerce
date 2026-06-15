package com.commerce.api.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.coupon.dto.CouponResponse;
import com.commerce.api.coupon.entity.CouponFundedBy;
import com.commerce.api.coupon.entity.CouponStatus;
import com.commerce.api.coupon.entity.DiscountType;
import com.commerce.api.coupon.service.CouponService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CouponController 슬라이스 테스트 (@WebMvcTest + MockMvc, 보안 필터 비활성).
 * 실제 ADMIN 인가는 런타임/통합으로 검증(슬라이스는 컨트롤러 로직·검증·직렬화에 집중).
 */
@WebMvcTest(CouponController.class)
@AutoConfigureMockMvc(addFilters = false)
class CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponService couponService;

    private CouponResponse sample() {
        return new CouponResponse(1L, "WELCOME5000", "신규 5천원", DiscountType.FIXED_AMOUNT, 5000L, null,
                30000L, CouponFundedBy.PLATFORM, null,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 12, 31, 23, 59),
                CouponStatus.ACTIVE, LocalDateTime.now());
    }

    @Test
    @DisplayName("POST /api/coupons - 발급 성공 201")
    void create_success() throws Exception {
        given(couponService.create(any())).willReturn(sample());

        mockMvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"welcome5000","name":"신규 5천원","discountType":"FIXED_AMOUNT",
                                 "discountValue":5000,"minOrderAmount":30000,"fundedBy":"PLATFORM",
                                 "validFrom":"2026-06-01T00:00:00","validUntil":"2026-12-31T23:59:59"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("WELCOME5000"))
                .andExpect(jsonPath("$.data.fundedBy").value("PLATFORM"));
    }

    @Test
    @DisplayName("POST /api/coupons - 필수값(코드) 누락 시 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/coupons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"","name":"x","discountType":"FIXED_AMOUNT","discountValue":5000,
                                 "minOrderAmount":0,"fundedBy":"PLATFORM",
                                 "validFrom":"2026-06-01T00:00:00","validUntil":"2026-12-31T23:59:59"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/coupons - 목록 200")
    void getCoupons_success() throws Exception {
        given(couponService.getCoupons()).willReturn(List.of(sample()));

        mockMvc.perform(get("/api/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("WELCOME5000"))
                .andExpect(jsonPath("$.data[0].discountType").value("FIXED_AMOUNT"));
    }
}
