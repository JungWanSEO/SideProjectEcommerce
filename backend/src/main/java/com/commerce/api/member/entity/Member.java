package com.commerce.api.member.entity;

import com.commerce.api.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 엔티티 (member 테이블).
 *
 * - @NoArgsConstructor(PROTECTED): JPA는 기본 생성자가 필요하지만,
 *   외부에서 무분별하게 빈 객체를 만들지 못하도록 protected로 막는다.
 * - 생성은 @Builder를 통해서만. password는 서비스에서 BCrypt로 해싱한 값을 전달받는다.
 */
@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    // 소셜 로그인 유저는 로컬 비밀번호가 없으므로 nullable. (LOCAL 유저만 BCrypt 해시 보유)
    @Column
    private String password;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** 인증 제공자 (LOCAL / GOOGLE / KAKAO / NAVER). 기본 LOCAL. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    /** 소셜 제공자의 고유 ID(예: 구글 sub). LOCAL은 null. */
    private String providerId;

    /**
     * 셀러(입점사) 운영자일 때 운영하는 셀러 ID(ID 참조, nullable). SELLER 역할에만 의미가 있다.
     * 셀러 콘솔은 이 값으로 "내 정산만" 스코핑한다(다른 애그리거트라 객체 연관이 아닌 ID 참조).
     */
    @Column(name = "seller_id")
    private Long sellerId;

    @Builder
    private Member(String email, String password, String nickname, Role role,
                   AuthProvider provider, String providerId) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = role;
        this.provider = provider != null ? provider : AuthProvider.LOCAL;   // 미지정 시 LOCAL
        this.providerId = providerId;
    }

    /** ADMIN이 이 회원을 셀러 운영자로 지정 — 역할을 SELLER로 올리고 셀러에 연결한다. */
    public void assignAsSeller(Long sellerId) {
        this.role = Role.SELLER;
        this.sellerId = sellerId;
    }
}
