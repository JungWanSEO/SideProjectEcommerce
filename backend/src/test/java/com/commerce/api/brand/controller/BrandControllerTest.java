package com.commerce.api.brand.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.brand.dto.BrandResponse;
import com.commerce.api.brand.service.BrandService;
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
 * BrandController 통합 테스트 (@WebMvcTest + MockMvc, 보안 필터 비활성).
 */
@WebMvcTest(BrandController.class)
@AutoConfigureMockMvc(addFilters = false)
class BrandControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private BrandService brandService;

    @Test
    @DisplayName("GET /api/brands - 목록 200")
    void getBrands_success() throws Exception {
        given(brandService.getBrands())
                .willReturn(List.of(new BrandResponse(1L, "Nike", 5L), new BrandResponse(2L, "Adidas", null)));

        mockMvc.perform(get("/api/brands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Nike"))
                .andExpect(jsonPath("$.data[1].id").value(2));
    }

    @Test
    @DisplayName("POST /api/brands - 등록 성공 201")
    void create_success() throws Exception {
        given(brandService.create(any())).willReturn(new BrandResponse(1L, "Nike", null));

        mockMvc.perform(post("/api/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nike\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("Nike"));
    }

    @Test
    @DisplayName("POST /api/brands - 이름이 비면 400")
    void create_validationFail() throws Exception {
        mockMvc.perform(post("/api/brands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PUT /api/brands/{id}/seller - 셀러 귀속 200")
    void assignSeller_success() throws Exception {
        given(brandService.assignSeller(eq(1L), eq(7L)))
                .willReturn(new BrandResponse(1L, "Nike", 7L));

        mockMvc.perform(put("/api/brands/1/seller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sellerId").value(7));
    }
}
