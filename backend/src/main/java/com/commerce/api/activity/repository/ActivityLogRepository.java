package com.commerce.api.activity.repository;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.entity.ActivityType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 행동 로그 DB 접근. (조회 기록 적재 + 검증용 카운트 + 추천 배치의 최근 윈도우 조회 + 최근 본 상품.)
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /** 이 회원이 남긴 조회 로그 수 — 기록 동작 확인/검증용. */
    long countByMemberId(Long memberId);

    /** 최근(since 이후) 조회 로그 — 추천 배치가 "최근 관심"만 신호로 쓰도록 기간 윈도우. */
    List<ActivityLog> findByCreatedAtAfter(LocalDateTime since);

    /**
     * "최근 본 상품" — 이 회원의 조회 로그를 <b>상품별 최신 1건으로 접어</b> 최근 순 productId만 반환한다.
     *
     * <p>로그는 append-only(같은 상품을 3번 보면 3행)라 그대로 정렬하면 같은 상품이 레일을 도배한다.
     * 그래서 {@code group by product_id} + {@code order by max(created_at) desc} — 상품별 마지막 조회 시각 기준.
     * 개수 제한은 {@link Pageable}(size)로 준다. 인덱스 {@code idx_activity_member(member_id, created_at)}가
     * member_id 조건을 커버한다.
     */
    @Query("""
            select a.productId
            from ActivityLog a
            where a.memberId = :memberId and a.type = :type
            group by a.productId
            order by max(a.createdAt) desc
            """)
    List<Long> findRecentlyViewedProductIds(@Param("memberId") Long memberId,
                                            @Param("type") ActivityType type,
                                            Pageable pageable);
}
