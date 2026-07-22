package com.commerce.api.order.dto;

import com.commerce.api.order.entity.OrderStatus;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 주문 검색 조건(@ParameterObject 바인딩) — 값이 없는 필드는 조건에서 빠진다(동적 where).
 *
 * <p>어드민 주문 검색(CS "어제 그 고객 주문 찾아줘")과 셀러 콘솔("내 셀러 상품이 든 주문")이 <b>같은 쿼리</b>를 쓴다.
 *
 * @param keyword   수령인명(부분일치) 또는 주문번호(숫자면 주문 ID 정확일치)
 * @param memberId  주문 회원 ID
 * @param status    주문 상태
 * @param from      이 날짜(포함) 00:00 이후 생성
 * @param to        이 날짜(포함) 24:00 이전 생성 — 그날 하루를 포함하도록 처리
 * @param minAmount 총액(gross) 하한
 * @param maxAmount 총액(gross) 상한
 * @param sellerId  이 셀러 상품이 포함된 주문만(셀러 콘솔 전용 — 어드민 검색에선 보통 null)
 */
public record OrderSearchCondition(
        String keyword,
        Long memberId,
        OrderStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        Long minAmount,
        Long maxAmount,
        Long sellerId
) {
}
