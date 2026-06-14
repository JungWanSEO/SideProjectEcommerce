package com.commerce.api.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.service.MemberService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MemberController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터는 비활성(addFilters = false)하여 컨트롤러 로직에 집중한다.
 */
@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("POST /api/members - 회원가입 성공 시 201, password는 응답에 없음")
    void signup_success() throws Exception {
        given(memberService.signup(any())).willReturn(
                new MemberResponse(1L, "alice@commerce.com", "alice", Role.USER, null, LocalDateTime.now()));

        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@commerce.com","password":"password123","nickname":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/members - 잘못된 이메일·짧은 비번이면 400")
    void signup_validationFail() throws Exception {
        mockMvc.perform(post("/api/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"123","nickname":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/members/{id} - 조회 성공 시 200")
    void getMember_success() throws Exception {
        given(memberService.getMember(1L)).willReturn(
                new MemberResponse(1L, "alice@commerce.com", "alice", Role.USER, null, LocalDateTime.now()));

        mockMvc.perform(get("/api/members/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("alice"));
    }

    @Test
    @DisplayName("GET /api/members/{id} - 없는 회원이면 404")
    void getMember_notFound() throws Exception {
        given(memberService.getMember(999L))
                .willThrow(new BusinessException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/members/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
