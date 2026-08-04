package com.commerce.api.returns.repository;

import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
import com.commerce.api.returns.entity.ReturnType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 반품/교환 요청 DB 접근(#3).
 */
public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    /** 전이 시 요청 행을 비관적 락으로 — 부모 주문 락과 함께 다단계 전이를 직렬화(보조 방어). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnRequest r where r.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") Long id);

    /** returnId → orderId 스칼라(엔티티 미로딩) — 부모 주문을 먼저 락으로 잡기 위해(락 순서 ORDER→RETURN 일관·1차 캐시 stale 회피). */
    @Query("select r.orderId from ReturnRequest r where r.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /** 이 주문 항목에 진행 중(미종료) 반품이 있는가 — 중복 요청 가드. */
    List<ReturnRequest> findByOrderItemIdAndStatusIn(Long orderItemId, Collection<ReturnStatus> statuses);

    /**
     * 이 항목에 특정 타입·상태의 반품이 있는가 — 교환 완료(EXCHANGE·COMPLETED) 항목의 재-반품 차단용(#3 적대적리뷰 교정).
     * 교환 후 원 항목은 ACTIVE로 남고 자격 게이트는 원배송(DELIVERED)만 보므로, 이 가드가 없으면 교환품을 받고도
     * 다시 환불받는 이중지급이 뚫린다.
     */
    boolean existsByOrderItemIdAndTypeAndStatus(Long orderItemId, ReturnType type, ReturnStatus status);

    /** 셀러 콘솔: 내 셀러의 반품 목록(상태 필터). */
    Page<ReturnRequest> findBySellerId(Long sellerId, Pageable pageable);

    /** 구매자: 내 반품 목록. */
    Page<ReturnRequest> findByMemberId(Long memberId, Pageable pageable);

    /**
     * 반품/교환 요청의 <b>사유별</b> 건수 — [reasonCode, count]. 상태 무관(거부된 요청도 사유 통계엔 의미가 있다)이며
     * reasonCode는 nullable이라 미기록 건은 null 행으로 나온다. 취소 사유 집계(OrderRepository)와 짝이다(#8 후속).
     */
    @Query("select r.reasonCode, count(r) from ReturnRequest r group by r.reasonCode")
    List<Object[]> countByReasonCode();

    /**
     * 어드민 반품/교환 검색 — 전체 스코프(구매자/셀러 목록과 달리 소유 제한이 없다).
     *
     * <p>필터는 <b>nullable 바인딩</b>: null이면 그 조건을 건너뛴다(대사 윈도우 쿼리와 같은 방식). 상태·유형·셀러로
     * 좁히는 게 운영 동선(예: "REQUESTED만 모아 대행 승인")이라 이 셋만 둔다. 기간은 후속 — 반품은 건수가 적고
     * 최신순 페이지로 충분하다.
     */
    @Query("select r from ReturnRequest r where "
            + "(:status is null or r.status = :status) and "
            + "(:type is null or r.type = :type) and "
            + "(:sellerId is null or r.sellerId = :sellerId)")
    Page<ReturnRequest> searchForAdmin(@Param("status") ReturnStatus status,
            @Param("type") ReturnType type,
            @Param("sellerId") Long sellerId,
            Pageable pageable);
}
