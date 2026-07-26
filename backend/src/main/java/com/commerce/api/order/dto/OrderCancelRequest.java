package com.commerce.api.order.dto;

import com.commerce.api.global.common.CancelReason;

/**
 * 주문/항목 취소 요청 본문(#8) — 구조화된 취소 사유. 기록·집계 전용(돈 경로 무영향).
 *
 * <p>본문·필드 모두 <b>선택</b>(nullable) — 사유 없이 취소해도 되지만(기존 호환), 사유를 주면 항목에 기록돼
 * 집계·필터에 쓰인다. 컨트롤러가 {@code @RequestBody(required = false)}로 받아 null이면 사유 없음으로 처리한다.
 *
 * @param reason 취소 사유 코드(없으면 null)
 */
public record OrderCancelRequest(
        CancelReason reason
) {
}
