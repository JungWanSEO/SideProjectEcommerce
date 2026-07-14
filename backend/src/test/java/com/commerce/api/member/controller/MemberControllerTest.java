package com.commerce.api.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberSearchCondition;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.service.MemberService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MemberController 통합 테스트 (@WebMvcTest + MockMvc).
 * 보안 필터는 비활성(addFilters = false)하여 컨트롤러 로직에 집중한다.
 * 어드민 권한 변경은 행위자(SecurityContext의 memberId)를 서비스로 넘기므로 인증 컨텍스트를 심어 둔다.
 */
@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @BeforeEach
    void setAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

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

    // === 어드민 회원 관리 ===

    @Test
    @DisplayName("GET /api/members/admin - 목록 200 + keyword·role 조건과 기본 페이지(20)가 바인딩된다")
    void searchMembers_success() throws Exception {
        PageResponse<MemberResponse> page = new PageResponse<>(
                List.of(new MemberResponse(1L, "alice@commerce.com", "alice", Role.USER, null, LocalDateTime.now())),
                0, 20, 1L, 1, false);
        given(memberService.search(any(MemberSearchCondition.class), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get("/api/members/admin").param("keyword", "alice").param("role", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].email").value("alice@commerce.com"))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        ArgumentCaptor<MemberSearchCondition> condition = ArgumentCaptor.forClass(MemberSearchCondition.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(memberService).search(condition.capture(), pageable.capture());
        assertThat(condition.getValue().keyword()).isEqualTo("alice");
        assertThat(condition.getValue().role()).isEqualTo(Role.USER);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);   // @PageableDefault
    }

    @Test
    @DisplayName("PATCH /api/members/{id}/role - 권한 변경 200, 행위자는 현재 로그인 회원(1L)")
    void changeRole_success() throws Exception {
        given(memberService.changeRole(1L, 2L, Role.ADMIN)).willReturn(
                new MemberResponse(2L, "bob@commerce.com", "bob", Role.ADMIN, null, LocalDateTime.now()));

        mockMvc.perform(patch("/api/members/2/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        verify(memberService).changeRole(1L, 2L, Role.ADMIN);   // actor=SecurityContext의 memberId
    }

    @Test
    @DisplayName("PATCH /api/members/{id}/role - 자기 자신이면 409")
    void changeRole_self() throws Exception {
        given(memberService.changeRole(1L, 1L, Role.USER))
                .willThrow(new BusinessException(HttpStatus.CONFLICT, "자기 자신의 권한은 변경할 수 없습니다."));

        mockMvc.perform(patch("/api/members/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"USER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/members/{id}/role - role이 없으면 400")
    void changeRole_validationFail() throws Exception {
        mockMvc.perform(patch("/api/members/2/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
