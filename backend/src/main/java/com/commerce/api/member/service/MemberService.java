package com.commerce.api.member.service;

import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.member.dto.MemberResponse;
import com.commerce.api.member.dto.MemberSignupRequest;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
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
