package com.commerce.api.seller.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.seller.dto.SellerResponse;
import com.commerce.api.seller.entity.SellerStatus;
import com.commerce.api.seller.service.SellerService;
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
 * SellerController 통합 테스트 (@WebMvcTest + MockMvc, 보안 필터 비활성).
 */
@WebMvcTest(SellerController.class)
@AutoConfigureMockMvc(addFilters = false)
class SellerControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private SellerService sellerService;

    private SellerResponse sample(Long id, String name, SellerStatus status) {
        return new SellerResponse(id, name, 0.10, status, "신한 110-123-456789", "123-45-67890",
                LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /api/sellers - 목록 200")
    void getSellers_success() throws Exception {
        given(sellerService.getSellers())
                .willReturn(List.of(sample(1L, "셀러A", SellerStatus.ACTIVE)));

        mockMvc.perform(get("/api/sellers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("셀러A"))
                .andExpect(jsonPath("$.data[0].commissionRate").value(0.10));
    }

    @Test
    @DisplayName("GET /api/sellers/{id} - 단건 200")
    void getSeller_success() throws Exception {
        given(sellerService.getSeller(1L)).willReturn(sample(1L, "셀러A", SellerStatus.ACTIVE));

        mockMvc.perform(get("/api/sellers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /api/sellers - 등록 201")
    void create_success() throws Exception {
        given(sellerService.create(any())).willReturn(sample(1L, "셀러A", SellerStatus.ACTIVE));

        mockMvc.perform(post("/api/sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"셀러A","commissionRate":0.10,"payoutAccount":"신한 110-123-456789","businessNumber":"123-45-67890"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("셀러A"));
    }

    @Test
    @DisplayName("POST /api/sellers - 셀러명 누락 시 400")
    void create_blankName() throws Exception {
        mockMvc.perform(post("/api/sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"commissionRate\":0.10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/sellers - 수수료율 1 이상이면 400")
    void create_rateTooHigh() throws Exception {
        mockMvc.perform(post("/api/sellers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"셀러A\",\"commissionRate\":1.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PUT /api/sellers/{id} - 수정 200")
    void update_success() throws Exception {
        given(sellerService.update(eq(1L), any())).willReturn(sample(1L, "셀러A-수정", SellerStatus.ACTIVE));

        mockMvc.perform(put("/api/sellers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"셀러A-수정\",\"commissionRate\":0.15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("셀러A-수정"));
    }

    @Test
    @DisplayName("PUT /api/sellers/{id}/suspend - 정지 200")
    void suspend_success() throws Exception {
        given(sellerService.suspend(1L)).willReturn(sample(1L, "셀러A", SellerStatus.SUSPENDED));

        mockMvc.perform(put("/api/sellers/1/suspend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("PUT /api/sellers/{id}/activate - 재개 200")
    void activate_success() throws Exception {
        given(sellerService.activate(1L)).willReturn(sample(1L, "셀러A", SellerStatus.ACTIVE));

        mockMvc.perform(put("/api/sellers/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }
}
