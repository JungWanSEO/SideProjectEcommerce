package com.commerce.api.returns.repository;

import com.commerce.api.returns.entity.ReturnRequest;
import com.commerce.api.returns.entity.ReturnStatus;
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

    /** 이 주문 항목에 진행 중(미종료) 반품이 있는가 — 중복 요청 가드. */
    List<ReturnRequest> findByOrderItemIdAndStatusIn(Long orderItemId, Collection<ReturnStatus> statuses);

    /** 셀러 콘솔: 내 셀러의 반품 목록(상태 필터). */
    Page<ReturnRequest> findBySellerId(Long sellerId, Pageable pageable);

    /** 구매자: 내 반품 목록. */
    Page<ReturnRequest> findByMemberId(Long memberId, Pageable pageable);
}
