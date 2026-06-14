package com.commerce.api.settlement.repository;

import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementEntry;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 정산 동적 조회(QueryDSL) — 셀러/상태/기간 필터 + 셀러별 집계.
 */
public interface SettlementRepositoryCustom {

    /** 조건(셀러·상태·기간)에 맞는 정산 항목 페이지. */
    Page<SettlementEntry> search(SettlementSearchCondition condition, Pageable pageable);

    /** 조건 범위 안에서 셀러별로 매출/수수료/실수령을 집계(정산서). sellerName은 서비스가 enrich. */
    List<SellerSettlementSummary> summarizeBySeller(SettlementSearchCondition condition);
}
