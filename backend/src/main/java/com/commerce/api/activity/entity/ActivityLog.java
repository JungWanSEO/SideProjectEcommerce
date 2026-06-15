package com.commerce.api.activity.entity;

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
 * 행동 로그 — 회원의 상품 조회 이벤트 (activity_log 테이블).
 *
 * <ul>
 *   <li><b>append-only</b>: 같은 상품을 여러 번 봐도 매번 한 행. 반복 조회 자체가 관심도(빈도) 신호다.
 *       무한 증가는 추천 배치(Step 2)가 <b>최근 기간 윈도우</b>로 집계해 다룬다.
 *   <li>회원·상품은 다른 애그리거트 → <b>ID 참조</b>(memberId/productId), FK 없음.
 *   <li>인덱스 <code>(member_id, created_at)</code>로 "이 회원의 최근 조회" 윈도우 조회를 받친다(Flyway V29).
 * </ul>
 *
 * <p>.NET 비유: 도메인 이벤트를 별도 이벤트 스토어/로그 테이블에 적재하는 것과 같은 발상(이력 누적).
 */
@Getter
@Entity
@Table(name = "activity_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;    // 행동 주체(회원) → ID 참조

    @Column(nullable = false)
    private Long productId;   // 대상 상품 → ID 참조

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityType type;

    @Builder
    private ActivityLog(Long memberId, Long productId, ActivityType type) {
        this.memberId = memberId;
        this.productId = productId;
        this.type = type;
    }

    /** 상품 조회 1건 기록 생성. */
    public static ActivityLog view(Long memberId, Long productId) {
        return ActivityLog.builder()
                .memberId(memberId)
                .productId(productId)
                .type(ActivityType.VIEW)
                .build();
    }
}
