package com.commerce.api.audit.entity;

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
 * 어드민 감사 로그 — 운영자 변경(mutation) 1건의 발자국.
 *
 * <p>{@link com.commerce.api.audit.aspect.AuditAspect}가 {@code @Auditable}이 붙은 메서드를 감싸
 * 자동으로 적재한다(도메인 서비스는 이 엔티티를 몰라도 된다 = 횡단 관심사 분리).
 * append-only 이력이라 수정하지 않는다.
 */
@Entity
@Getter
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행위자(로그인 회원 ID). 인증 정보를 못 얻으면 null(시스템/미인증). 회원은 다른 애그리거트라 ID 참조. */
    private Long actorMemberId;

    /** 감사 액션 코드 — 예: "PRODUCT_UPDATE" ({@code @Auditable.action}). */
    @Column(nullable = false, length = 64)
    private String action;

    /** 대상 리소스 종류 — 예: "PRODUCT". 없으면 null. */
    @Column(length = 64)
    private String targetType;

    /** 대상 식별자(문자열) — 예: "42". 단일 대상이 없는 액션이면 null. */
    @Column(length = 64)
    private String targetId;

    /** 부가 설명 — 기본은 "HTTP메서드 URI"(예: "PUT /api/products/42"). */
    @Column(length = 500)
    private String detail;

    /** 성공/실패. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditResult result;

    @Builder
    private AuditLog(Long actorMemberId, String action, String targetType,
                     String targetId, String detail, AuditResult result) {
        this.actorMemberId = actorMemberId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.result = result;
    }
}
