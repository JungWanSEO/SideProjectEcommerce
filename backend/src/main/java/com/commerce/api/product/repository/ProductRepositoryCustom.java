package com.commerce.api.product.repository;

import com.commerce.api.product.dto.LowStockOption;
import com.commerce.api.product.dto.ProductSearchCondition;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * QueryDSL로 직접 구현하는 커스텀 리포지토리.
 * (메서드 이름 규칙으로 만들기 어려운 "동적 조건" 쿼리를 담는다)
 *
 * <p><b>Spring Data 규칙(중요):</b> {@link ProductRepository}가 이 인터페이스를 함께 상속하고,
 * 구현 클래스 이름이 반드시 <b>{@code ProductRepositoryImpl}</b>(리포지토리 인터페이스명 + "Impl")
 * 이어야 스프링 데이터가 자동으로 찾아 끼워 넣는다. 이름 규칙을 어기면 연결되지 않는다.
 */
public interface ProductRepositoryCustom {

    /**
     * 가시 상태 + 검색 조건으로 상품을 페이지 조회한다.
     *
     * @param visibleStatuses 공개 목록에 노출할 상태(예: ON_SALE, SOLD_OUT) — 항상 적용
     * @param condition       선택적 검색 조건(키워드/가격대)
     * @param pageable        페이지 번호·크기·정렬
     */
    Page<Product> search(Collection<ProductStatus> visibleStatuses,
                         ProductSearchCondition condition,
                         Pageable pageable);

    /**
     * 재고가 임계치 이하인 옵션 목록 (재고 임박·품절 리포트). 재고 적은 순.
     *
     * @param statuses  대상 상품 상태(판매중·품절만 — 판매중지 상품은 채울 이유가 없다)
     * @param threshold 이 값 <b>이하</b>인 재고만(0=품절 포함)
     * @param limit     최대 건수
     */
    List<LowStockOption> findLowStockOptions(Collection<ProductStatus> statuses, int threshold, int limit);

    /**
     * 재고가 [min, max] 구간인 옵션 수 (리포트 상단 카운트).
     * 품절 = countOptionsWithStockBetween(statuses, 0, 0), 임박 = (statuses, 1, threshold).
     */
    long countOptionsWithStockBetween(Collection<ProductStatus> statuses, int min, int max);
}
