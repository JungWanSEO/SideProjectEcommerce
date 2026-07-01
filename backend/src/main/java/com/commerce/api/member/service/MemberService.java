package com.commerce.api.member.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberSignupRequest;
import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 비즈니스 로직.
 *
 * - @RequiredArgsConstructor: final 필드를 받는 생성자를 Lombok이 만들어 준다 → 생성자 주입.
 * - @Transactional(readOnly = true): 클래스 기본은 읽기 전용, 쓰기 메서드만 별도로 @Transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /** 회원가입: 이메일 중복 검사 후 비밀번호를 BCrypt로 해싱하여 저장 (기본 권한 USER) */
    @Transactional
    public MemberResponse signup(MemberSignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        Member member = Member.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .role(Role.USER)
                .build();

        return MemberResponse.from(memberRepository.save(member));
    }

    /**
     * 소셜 로그인 회원 find-or-create. 식별자는 (provider, providerId).
     * <ol>
     *   <li>(provider, providerId)로 있으면 그대로 반환.</li>
     *   <li>없고 같은 email 계정이 이미 있으면 그 계정으로 <b>연동</b>(로그인). 제공자가 검증한 email 기준
     *       (구글은 email 검증됨) — 데모용 자동 연동 정책. provider별 별개 계정을 원하면 이 분기를 제거한다.</li>
     *   <li>둘 다 아니면 신규 생성(password=null = 소셜 전용, 기본 USER).</li>
     * </ol>
     */
    @Transactional
    public Member findOrCreateSocialMember(AuthProvider provider, String providerId, String email, String name) {
        return memberRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> linkByEmailOrCreate(provider, providerId, email, name));
    }

    private Member linkByEmailOrCreate(AuthProvider provider, String providerId, String email, String name) {
        // email 있으면(구글·검수된 카카오) 같은 email 계정에 자동 연동. 없으면(카카오 email-free) 플레이스홀더로 생성.
        if (email != null && !email.isBlank()) {
            Optional<Member> linked = memberRepository.findByEmail(email);
            if (linked.isPresent()) {
                return linked.get();
            }
        } else {
            // email 미제공 소셜 계정 — email 컬럼 NOT NULL·UNIQUE 충족용 플레이스홀더(providerId로 유일). 실제 메일 아님.
            email = provider.name().toLowerCase() + "_" + providerId + "@social.local";
        }
        return memberRepository.save(Member.builder()
                .email(email)
                .nickname(resolveNickname(name, email))
                .role(Role.USER)
                .provider(provider)
                .providerId(providerId)
                .build());   // password 미지정(null) = 소셜 전용 계정
    }

    /** 닉네임 = 제공자 이름(없으면 email 로컬파트). member.nickname length 30 제약에 맞춰 절단. */
    private String resolveNickname(String name, String email) {
        int at = email.indexOf('@');
        String base = (name != null && !name.isBlank()) ? name : (at > 0 ? email.substring(0, at) : email);
        return base.length() > 30 ? base.substring(0, 30) : base;
    }

    /** 단건 조회 */
    public MemberResponse getMember(Long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
        return MemberResponse.from(member);
    }

    /**
     * 회원을 셀러 운영자로 지정(ADMIN) — 역할을 SELLER로 올리고 셀러에 연결한다.
     * 관리자는 셀러로 강등하지 않는다(409). 없는 회원은 404.
     * (역할은 JWT에 박히므로 지정된 회원은 다음 로그인부터 SELLER 권한이 적용된다.)
     */
    @Transactional
    public MemberResponse assignAsSeller(Long memberId, Long sellerId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
        if (member.getRole() == Role.ADMIN) {
            throw new BusinessException(HttpStatus.CONFLICT, "관리자는 셀러로 지정할 수 없습니다.");
        }
        member.assignAsSeller(sellerId);   // 영속 엔티티 → dirty checking flush
        return MemberResponse.from(member);
    }
}
