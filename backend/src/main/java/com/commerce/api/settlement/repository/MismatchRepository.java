package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.entity.Mismatch;
import com.commerce.api.settlement.entity.MismatchStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MismatchRepository extends JpaRepository<Mismatch, Long> {

    /** 이미 처리된(RESOLVED/IGNORED) 불일치 — 재대사에서 같은 거래키를 다시 OPEN으로 만들지 않으려 조회. */
    List<Mismatch> findByStatusIn(Collection<MismatchStatus> statuses);

    /** 직전 OPEN 스냅샷만 비운다(처리된 건은 보존) — 전체 대사용. */
    void deleteByStatus(MismatchStatus status);

    /**
     * 주어진 거래키들의 OPEN 스냅샷만 비운다 — 일자별 윈도우 대사용.
     * 윈도우 안 거래키만 지워, 다른 날짜(윈도우 밖)의 OPEN 불일치는 건드리지 않는다.
     */
    void deleteByStatusAndPgTransactionIdIn(MismatchStatus status, Collection<String> pgTransactionIds);

    /** 상태별 목록(예: OPEN만). */
    Page<Mismatch> findByStatus(MismatchStatus status, Pageable pageable);

    /** PG별 목록(예: KAKAOPAY만) — MPG-2 PG 필터. */
    Page<Mismatch> findByProvider(String provider, Pageable pageable);

    /** 상태+PG 둘 다 필터(예: KAKAOPAY의 OPEN만). */
    Page<Mismatch> findByStatusAndProvider(MismatchStatus status, String provider, Pageable pageable);
}
