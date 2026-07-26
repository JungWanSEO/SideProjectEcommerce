package com.commerce.api.global.common;

import lombok.Getter;

/**
 * 취소·환불 사유 taxonomy(#8) — 주문/항목 취소와 반품/교환에 <b>공통</b> 적용하는 구조화된 사유 코드.
 *
 * <p><b>기록·집계 전용</b>(오너 결정): 자유텍스트 memo만으론 집계·정책 연동이 안 되던 것을 enum으로 구조화한다.
 * 정산 귀책·배송비 부담 등 <b>돈 경로엔 영향을 주지 않는다</b>(v1). 각 사유는 {@link Fault 귀책} 메타를 갖되
 * 이는 향후 정산 귀책/반품 왕복배송비 부담 연동 시 쓰기 위한 미연동 메타데이터다(현재는 기록·필터·집계용).
 */
@Getter
public enum CancelReason {

    CHANGE_OF_MIND(Fault.CUSTOMER),   // 단순 변심
    WRONG_ORDER(Fault.CUSTOMER),      // 주문 실수(잘못 주문)
    DELIVERY_DELAY(Fault.SELLER),     // 배송 지연
    OUT_OF_STOCK(Fault.SELLER),       // 품절
    DEFECTIVE(Fault.SELLER),          // 상품 불량
    WRONG_DELIVERY(Fault.SELLER),     // 오배송
    OTHER(Fault.NONE);                // 기타

    /** 귀책 주체 — 기록·집계용 메타(v1 돈 경로 미연동). */
    public enum Fault { CUSTOMER, SELLER, PLATFORM, NONE }

    private final Fault fault;

    CancelReason(Fault fault) {
        this.fault = fault;
    }
}
