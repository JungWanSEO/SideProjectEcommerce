package com.commerce.api.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberSignupRequest;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MemberService 단위 테스트.
 * 비밀번호는 PasswordEncoder(mock)로 해싱하고, 기본 권한은 USER.
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService;

    private Member memberWithId(Long id, String email, String nickname) {
        Member member = Member.builder()
                .email(email)
                .password("ENCODED")
                .nickname(nickname)
                .role(Role.USER)
                .build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Test
    @DisplayName("회원가입 성공 - 비밀번호를 해싱해 저장하고 회원 정보를 반환한다")
    void signup_success() {
        // given
        MemberSignupRequest request =
                new MemberSignupRequest("alice@commerce.com", "password123", "alice");
        given(memberRepository.existsByEmail("alice@commerce.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("ENCODED");
        given(memberRepository.save(any(Member.class)))
                .willReturn(memberWithId(1L, "alice@commerce.com", "alice"));

        // when
        MemberResponse response = memberService.signup(request);

        // then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("alice@commerce.com");
        assertThat(response.role()).isEqualTo(Role.USER);
        verify(passwordEncoder).encode("password123");   // 평문이 아닌 해싱 호출 검증
        verify(memberRepository).save(any(Member.class));
    }

    @Test
    @DisplayName("회원가입 실패 - 이미 사용 중인 이메일이면 예외, 저장은 호출되지 않는다")
    void signup_duplicateEmail() {
        // given
        MemberSignupRequest request =
                new MemberSignupRequest("dup@commerce.com", "password123", "dup");
        given(memberRepository.existsByEmail("dup@commerce.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 사용 중인 이메일");
        verify(memberRepository, never()).save(any(Member.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("회원 조회 성공")
    void getMember_success() {
        // given
        given(memberRepository.findById(1L))
                .willReturn(Optional.of(memberWithId(1L, "alice@commerce.com", "alice")));

        // when
        MemberResponse response = memberService.getMember(1L);

        // then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("alice@commerce.com");
    }

    @Test
    @DisplayName("회원 조회 실패 - 없는 회원이면 예외")
    void getMember_notFound() {
        // given
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> memberService.getMember(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("회원을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("셀러 지정 - USER 회원을 SELLER로 올리고 셀러에 연결")
    void assignAsSeller_success() {
        Member member = memberWithId(1L, "seller@commerce.com", "셀러운영자");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        MemberResponse response = memberService.assignAsSeller(1L, 5L);

        assertThat(response.role()).isEqualTo(Role.SELLER);
        assertThat(response.sellerId()).isEqualTo(5L);
        assertThat(member.getRole()).isEqualTo(Role.SELLER);
        assertThat(member.getSellerId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("셀러 지정 실패 - 관리자는 셀러로 지정할 수 없다(409)")
    void assignAsSeller_admin() {
        Member admin = Member.builder()
                .email("admin@commerce.com").password("ENCODED").nickname("admin").role(Role.ADMIN).build();
        ReflectionTestUtils.setField(admin, "id", 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(admin));

        assertThatThrownBy(() -> memberService.assignAsSeller(1L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("관리자는 셀러로 지정할 수 없습니다");
    }

    @Test
    @DisplayName("셀러 지정 실패 - 없는 회원이면 404")
    void assignAsSeller_notFound() {
        given(memberRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.assignAsSeller(99L, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("회원을 찾을 수 없습니다");
    }
}
