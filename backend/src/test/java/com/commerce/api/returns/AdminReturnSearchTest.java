package com.commerce.api.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.returns.dto.ReturnResponse;
import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import com.commerce.api.returns.repository.ReturnRequestRepository;
import com.commerce.api.returns.service.ReturnQueryService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 반품 검색 — <b>인가</b>와 <b>필터</b>.
 *
 * <p>구매자/셀러 목록은 소유(memberId·sellerId)로 좁혀 스코프가 쿼리에 박혀 있지만, 이 API는 <b>전혀 좁히지
 * 않는다</b>(운영자는 셀러를 넘나든다). 그래서 경로 인가(ADMIN)가 유일한 방어선이고, 그게 뚫리면 곧 전체 노출이다
 * — 매처가 살아 있는지 HTTP 레벨로 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminReturnSearchTest {

    private static final long SELLER_A = 9101L;
    private static final long SELLER_B = 9102L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ReturnQueryService returnQueryService;
    @Autowired private ReturnRequestRepository returnRequestRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Cookie[] loginAs(String email, Role role) throws Exception {
        memberRepository.save(Member.builder()
                .email(email).password(passwordEncoder.encode("pass12345"))
                .nickname("t").role(role).build());
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pass12345\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookies();
    }

    private ReturnRequest save(long seq, Long sellerId, ReturnType type) {
        return returnRequestRepository.save(ReturnRequest.create(
                seq, seq, seq, sellerId, 100L, type, "사유", null, 1,
                type == ReturnType.EXCHANGE ? 77L : null));
    }

    @Test
    @DisplayName("ADMIN만 전체 반품을 조회할 수 있다 — USER는 403(소유로 좁히지 않는 유일한 반품 조회)")
    void onlyAdminCanSearchAllReturns() throws Exception {
        mockMvc.perform(get("/api/returns/admin"))
                .andExpect(status().isUnauthorized());   // 비로그인

        Cookie[] user = loginAs("ret-user@commerce.com", Role.USER);
        mockMvc.perform(get("/api/returns/admin").cookie(user))
                .andExpect(status().isForbidden());      // 로그인해도 USER면 차단

        Cookie[] admin = loginAs("ret-admin@commerce.com", Role.ADMIN);
        mockMvc.perform(get("/api/returns/admin").cookie(admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("필터 — 상태·유형·셀러로 좁히고, 생략하면 전체(셀러 경계를 넘어 본다)")
    void filtersByStatusTypeAndSeller() {
        ReturnRequest mineA = save(9201L, SELLER_A, ReturnType.RETURN);
        ReturnRequest mineB = save(9202L, SELLER_B, ReturnType.RETURN);
        ReturnRequest exchange = save(9203L, SELLER_A, ReturnType.EXCHANGE);

        PageRequest page = PageRequest.of(0, 200);
        // 좁히지 않으면 셀러 경계를 넘어 함께 본다(구매자·셀러 목록과 결정적으로 다른 점)
        assertThat(idsOf(returnQueryService.searchForAdmin(null, null, null, page)))
                .contains(mineA.getId(), mineB.getId());
        // 셀러 필터 — A만
        assertThat(idsOf(returnQueryService.searchForAdmin(null, null, SELLER_A, page)))
                .contains(mineA.getId(), exchange.getId())
                .doesNotContain(mineB.getId());
        // 유형 필터
        assertThat(returnQueryService.searchForAdmin(null, ReturnType.EXCHANGE, null, page).content())
                .allSatisfy(r -> assertThat(r.type()).isEqualTo(ReturnType.EXCHANGE));
        // 상태 필터 — 방금 만든 것은 모두 REQUESTED
        assertThat(returnQueryService.searchForAdmin(ReturnStatus.REQUESTED, null, SELLER_A, page).content())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(ReturnStatus.REQUESTED));
        assertThat(returnQueryService.searchForAdmin(ReturnStatus.REFUNDED, null, SELLER_A, page).content())
                .isEmpty();
    }

    private java.util.List<Long> idsOf(PageResponse<ReturnResponse> page) {
        return page.content().stream().map(ReturnResponse::id).toList();
    }
}
