package com.commerce.api.settlement.entity;

/**
 * 정산 원장 항목의 <b>종류</b>(#8 후속) — {@code shipping} boolean 하나였던 종류 축을 enum으로 승격한 것.
 *
 * <p>왜 boolean을 늘리지 않고 enum으로 갔는가: 이 축은 {@code reverseRefunds}의 <b>버킷 분기</b>에 직접
 * 쓰인다. 역분개는 "지금의 목표(target) − 이미 정산된 합"만 남기는 방식이라, 어느 버킷에도 속하지 않은
 * 엔트리가 생기면 <b>매 실행마다 그 금액이 통째로 역분개돼 조용히 사라진다</b>. #4가 배송비를 도입할 때
 * 정확히 이 함정을 밟고 셀러 루프에서 {@code continue}로 막았다. boolean을 3개로 늘리면 유효 조합 4개를
 * 8가지로 표현할 수 있게 되어 "빠진 버킷"이 타입으로 막히지 않는다.
 *
 * <p>불변식: <b>모든 엔트리는 정확히 하나의 kind에 속하고, 모든 kind는 자기 target을 가진다.</b>
 * 새 kind를 추가할 때는 (a) 셀러 루프에서 배제 (b) 자기 target 계산 (c) 재실행 시 diff 0 — 셋을 모두 갖춰야 한다.
 */
public enum SettlementEntryKind {

    /** 셀러 매출. sellerId=셀러, gross=할인 후 셀러 몫. 역분개 target = 현재 활성 항목 기준 몫. */
    SALE,

    /**
     * 플랫폼 배송비 수익(#4). sellerId=null·platformFee=0.
     * 역분개 target = 배송비 유지면 shippingFee, 전량취소면 0.
     */
    SHIPPING,

    /**
     * 플랫폼 반품 회수비 수익(#8 후속). sellerId=null·platformFee=0.
     * 고객 귀책 반품에서 환불을 줄여 플랫폼이 보유한 금액이라 <b>PG 잔여에 실재하는 돈</b>이다
     * → gross에 실어 Σgross(원장 총액)를 복원한다. 역분개 target = 주문의 회수비 누계(단조 증가라 자연 수렴).
     */
    RETURN_SHIPPING,

    /**
     * 셀러 귀책 과금(#8 후속 P4). sellerId=셀러·<b>gross=0</b>·chargeAmount=금액 → net = −금액.
     * gross를 0으로 두는 이유: 셀러↔플랫폼 <b>내부 정산 조정액</b>이라 PG 원장에 대응 금액이 없다.
     * gross에 실으면 대사가 즉시 AMOUNT_MISMATCH로 튄다.
     */
    FAULT_CHARGE;

    /** 셀러 매출 원장에 속하는 종류인가 — 정방향 정산 멱등 게이트가 보는 기준. */
    public boolean isSaleLedger() {
        return this == SALE || this == SHIPPING;
    }
}
