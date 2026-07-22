package com.commerce.api.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberSearchCondition;
import com.commerce.api.member.dto.MemberSignupRequest;
import com.commerce.api.member.dto.MemberUpdateRequest;
import com.commerce.api.member.dto.MyProfileResponse;
import com.commerce.api.member.dto.PasswordChangeRequest;
import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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

    private Member socialMemberWithoutPassword(Long id) {
        Member member = Member.builder()
                .email("kakao_" + id + "@social.local").nickname("소셜")
                .role(Role.USER).provider(AuthProvider.KAKAO).providerId(String.valueOf(id)).build();  // password null
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Test
    @DisplayName("내 프로필 수정 - 닉네임 변경(dirty checking)")
    void updateMyProfile_success() {
        Member member = memberWithId(1L, "a@c.com", "old");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        MyProfileResponse response = memberService.updateMyProfile(1L, new MemberUpdateRequest("새닉네임"));

        assertThat(response.nickname()).isEqualTo("새닉네임");
        assertThat(member.getNickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("내 정보 조회 - provider(LOCAL)·hasPassword(true) 노출")
    void getMyProfile_includesProviderAndHasPassword() {
        given(memberRepository.findById(1L))
                .willReturn(Optional.of(memberWithId(1L, "a@c.com", "alice")));   // password "ENCODED"

        MyProfileResponse response = memberService.getMyProfile(1L);

        assertThat(response.provider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(response.hasPassword()).isTrue();
    }

    @Test
    @DisplayName("비밀번호 변경(로컬) - 현재 비번 일치 시 새 비번으로 설정")
    void changeMyPassword_localSuccess() {
        Member member = memberWithId(1L, "a@c.com", "alice");   // password "ENCODED"
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("current123", "ENCODED")).willReturn(true);
        given(passwordEncoder.encode("newpass123")).willReturn("NEW_ENCODED");

        memberService.changeMyPassword(1L, new PasswordChangeRequest("current123", "newpass123"));

        assertThat(member.getPassword()).isEqualTo("NEW_ENCODED");
        verify(passwordEncoder).encode("newpass123");
    }

    @Test
    @DisplayName("비밀번호 변경 실패(로컬) - 현재 비번 불일치면 400, 변경 안 함")
    void changeMyPassword_wrongCurrent() {
        Member member = memberWithId(1L, "a@c.com", "alice");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.matches("wrong", "ENCODED")).willReturn(false);

        assertThatThrownBy(() ->
                memberService.changeMyPassword(1L, new PasswordChangeRequest("wrong", "newpass123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("현재 비밀번호가 올바르지 않습니다");
        assertThat(member.getPassword()).isEqualTo("ENCODED");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("비밀번호 설정(소셜) - 비번 없는 계정은 현재 비번 없이 바로 설정")
    void changeMyPassword_socialSetsWithoutCurrent() {
        Member social = socialMemberWithoutPassword(1L);   // password null
        given(memberRepository.findById(1L)).willReturn(Optional.of(social));
        given(passwordEncoder.encode("newpass123")).willReturn("SET_ENCODED");

        memberService.changeMyPassword(1L, new PasswordChangeRequest(null, "newpass123"));

        assertThat(social.getPassword()).isEqualTo("SET_ENCODED");
        verify(passwordEncoder, never()).matches(anyString(), anyString());   // 현재 비번 검증 안 함
    }

    // === 어드민 회원 관리 (검색 · 권한 변경) ===

    @Test
    @DisplayName("회원 검색(ADMIN) - 리포지토리 결과를 PageResponse로 감싸 반환한다")
    void search_wrapsPage() {
        Member found = memberWithId(7L, "alice@commerce.com", "alice");
        Pageable pageable = PageRequest.of(0, 20);
        given(memberRepository.search(any(MemberSearchCondition.class), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(found), pageable, 1));

        PageResponse<MemberResponse> page =
                memberService.search(new MemberSearchCondition("alice", null), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).extracting(MemberResponse::email).containsExactly("alice@commerce.com");
    }

    @Test
    @DisplayName("권한 변경 성공 - USER를 ADMIN으로 올린다")
    void changeRole_promote() {
        Member target = memberWithId(2L, "bob@commerce.com", "bob");
        given(memberRepository.findById(2L)).willReturn(Optional.of(target));

        MemberResponse response = memberService.changeRole(1L, 2L, Role.ADMIN);

        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
        assertThat(response.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("권한 변경 - SELLER에서 내려오면 셀러 연결(sellerId)도 끊는다")
    void changeRole_demoteSellerClearsSellerId() {
        Member seller = memberWithId(2L, "seller@commerce.com", "seller");
        seller.assignAsSeller(9L);   // SELLER + sellerId=9
        given(memberRepository.findById(2L)).willReturn(Optional.of(seller));

        memberService.changeRole(1L, 2L, Role.USER);

        assertThat(seller.getRole()).isEqualTo(Role.USER);
        assertThat(seller.getSellerId()).isNull();   // 권한 없는 유령 링크가 남지 않는다
    }

    @Test
    @DisplayName("권한 변경 실패 - 자기 자신은 변경 불가(409, 관리자 잠금 방지)")
    void changeRole_self() {
        assertThatThrownBy(() -> memberService.changeRole(1L, 1L, Role.USER))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(memberRepository, never()).findById(any());
    }

    @Test
    @DisplayName("권한 변경 실패 - SELLER 지정은 셀러 운영자 지정 API로(400)")
    void changeRole_sellerNotAllowed() {
        assertThatThrownBy(() -> memberService.changeRole(1L, 2L, Role.SELLER))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(memberRepository, never()).findById(any());
    }

    @Test
    @DisplayName("권한 변경 실패 - 없는 회원이면 404")
    void changeRole_notFound() {
        given(memberRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.changeRole(1L, 999L, Role.ADMIN))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === 소셜 로그인 find-or-create (OAuth2 성공 핸들러가 호출) ===

    @Test
    @DisplayName("소셜 - (provider, providerId)로 이미 있으면 그대로 반환(생성 안 함)")
    void findOrCreateSocialMember_existingByProvider_returnsAsIs() {
        Member existing = memberWithId(5L, "kim@google.com", "김구글");
        given(memberRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "g-1"))
                .willReturn(Optional.of(existing));

        Member result = memberService.findOrCreateSocialMember(AuthProvider.GOOGLE, "g-1", "kim@google.com", "김구글");

        assertThat(result).isSameAs(existing);
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("소셜 - providerId 신규지만 같은 email 계정이 있으면 그 계정으로 연동(생성 안 함)")
    void findOrCreateSocialMember_linksByEmail() {
        Member existingLocal = memberWithId(6L, "same@mail.com", "기존회원");
        given(memberRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "g-2"))
                .willReturn(Optional.empty());
        given(memberRepository.findByEmail("same@mail.com")).willReturn(Optional.of(existingLocal));

        Member result = memberService.findOrCreateSocialMember(AuthProvider.GOOGLE, "g-2", "same@mail.com", "구글이름");

        assertThat(result).isSameAs(existingLocal);
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("소셜 - 신규 + 같은 email 없음 → 준 email로 USER 계정 생성(닉네임=제공자 이름)")
    void findOrCreateSocialMember_createsNewWithGivenEmail() {
        given(memberRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "g-3"))
                .willReturn(Optional.empty());
        given(memberRepository.findByEmail("new@google.com")).willReturn(Optional.empty());
        given(memberRepository.save(any(Member.class))).willAnswer(inv -> inv.getArgument(0));

        Member result = memberService.findOrCreateSocialMember(
                AuthProvider.GOOGLE, "g-3", "new@google.com", "새구글");

        assertThat(result.getEmail()).isEqualTo("new@google.com");
        assertThat(result.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(result.getProviderId()).isEqualTo("g-3");
        assertThat(result.getRole()).isEqualTo(Role.USER);
        assertThat(result.getNickname()).isEqualTo("새구글");
        assertThat(result.getPassword()).isNull();   // 소셜 전용(비번 없음)
    }

    @Test
    @DisplayName("소셜 - email 미제공(카카오 email-free) → 플레이스홀더 email 생성, 닉네임=이름")
    void findOrCreateSocialMember_emailFree_usesPlaceholder() {
        given(memberRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "k-9"))
                .willReturn(Optional.empty());
        given(memberRepository.save(any(Member.class))).willAnswer(inv -> inv.getArgument(0));

        Member result = memberService.findOrCreateSocialMember(AuthProvider.KAKAO, "k-9", null, "카카오친구");

        // email 없으면 providerId로 유일한 플레이스홀더(실제 메일 아님) — NOT NULL·UNIQUE 충족
        assertThat(result.getEmail()).isEqualTo("kakao_k-9@social.local");
        assertThat(result.getNickname()).isEqualTo("카카오친구");
        verify(memberRepository, never()).findByEmail(anyString());   // email 없으니 연동 조회 자체를 안 한다
    }
}
