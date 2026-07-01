package com.commerce.api.member.repository;

import com.commerce.api.member.entity.AuthProvider;
import com.commerce.api.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회원 DB 접근.
 * JpaRepository를 상속하면 save/findById/findAll/delete 등 기본 CRUD가 자동 제공된다.
 * 메서드 이름 규칙(existsByEmail/findByEmail)만으로 쿼리가 자동 생성된다.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByEmail(String email);

    Optional<Member> findByEmail(String email);

    /** 소셜 로그인 식별자 (provider, providerId)로 회원 조회 — email보다 안정적인 1차 식별자. */
    Optional<Member> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
