package com.commerce.api.order.repository;

import com.commerce.api.order.dto.OrderSearchCondition;
import com.commerce.api.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 주문 동적 검색(QueryDSL) — 어드민 주문 검색 + 셀러 콘솔("내 주문")의 공통 토대. 구현은 {@link OrderRepositoryImpl}.
 */
public interface OrderRepositoryCustom {

    /** 조건(수령인·주문번호·회원·상태·기간·금액·셀러)으로 주문을 페이지 조회한다. 정렬은 Pageable(기본 최신순). */
    Page<Order> search(OrderSearchCondition condition, Pageable pageable);
}
