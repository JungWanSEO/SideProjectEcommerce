package com.commerce.api.order.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 배송비 정책(#4) — <b>정액 + 무료배송 임계</b>(오너 결정). 주문 단위 단일 배송비이며 셀러별이 아니다.
 *
 * <p>값은 기존 컨벤션대로 {@code @Value}로 주입한다(@ConfigurationProperties 미도입 — app.* 는 전부 @Value).
 * 무료배송 임계 판정 기준액은 <b>할인 후 상품금액</b>(소계 − 쿠폰할인, 배송비 제외)이다 — 쿠폰으로 실제 상품
 * 결제액이 임계 아래로 내려가면 배송비가 붙는다(고객이 "상품에 실제로 낸 금액" 기준).
 *
 * <p>계산 결과는 {@code Order.assignShippingFee}로 <b>주문 생성 시점에 스냅샷</b>되므로, 이후 정책값이 바뀌어도
 * 과거 주문의 결제액/환불/정산은 불변이다. 배송비는 플랫폼 수익이라 셀러 정산 net에는 포함되지 않는다.
 */
@Getter
@Component
public class ShippingPolicy {

    /** 정액 배송비(원). 무료임계 미달 시 부과. */
    private final long flatFee;
    /** 무료배송 임계액(원). 할인 후 상품금액이 이 값 이상이면 배송비 0. */
    private final long freeThreshold;
    /** 반품 회수비(원, #8 후속). 귀책에 따라 고객 또는 셀러가 부담한다. 무료배송 주문에도 동일 부과. */
    private final long returnFee;

    public ShippingPolicy(
            @Value("${app.order.shipping-fee:3000}") long flatFee,
            @Value("${app.order.free-shipping-threshold:50000}") long freeThreshold,
            @Value("${app.order.return-shipping-fee:3000}") long returnFee) {
        this.flatFee = flatFee;
        this.freeThreshold = freeThreshold;
        this.returnFee = returnFee;
    }

    /**
     * 배송비 계산 — 할인 후 상품금액(소계 − 할인)이 무료임계 이상이면 0, 아니면 정액.
     *
     * @param amountAfterDiscount 할인 후 상품금액(배송비 제외). 임계 판정 기준.
     */
    public long feeFor(long amountAfterDiscount) {
        return amountAfterDiscount >= freeThreshold ? 0L : flatFee;
    }

    /**
     * 반품 회수비의 <b>부담 매트릭스</b>(#8 후속, 오너 결정) — 귀책 주체가 낸다.
     *
     * <table>
     *   <tr><td>CUSTOMER</td><td>고객 부담 → 환불액에서 차감</td></tr>
     *   <tr><td>SELLER</td><td>셀러 부담 → 셀러 정산에서 과금(P4)</td></tr>
     *   <tr><td>PLATFORM · NONE · 미기록</td><td>플랫폼 흡수 → 아무에게도 청구하지 않음</td></tr>
     * </table>
     *
     * <p><b>원배송비는 이 규칙과 무관하다</b>(오너 결정: 전량취소에서만 환불, 셀러 귀책이어도 유지).
     * 원배송비 유지 판정({@code shippingRetained})은 payable·정산 run·정산 reverseRefunds 세 곳에 복제돼
     * 있고, 그 통일이 #4 적대적리뷰 HIGH 2건을 고쳐 얻은 자리다 — 귀책 축을 그쪽에 끌어들이지 않는다.
     *
     * <p>무료배송(임계 초과) 주문의 반품에도 동일하게 부과한다 — 실제 회수 물류비는 원배송이 무료였다고
     * 0이 되지 않기 때문이다. 대신 반품 신청 화면에서 반드시 사전 고지한다.
     *
     * <p>요율을 인자로 받는 이유: 계산에 쓰는 값은 <b>반품 신청 시점에 스냅샷된 요율</b>이어야 한다.
     * 지금 이 컴포넌트의 {@code returnFee}를 그대로 읽으면 정책을 올린 순간 진행 중인 반품의 부담액이
     * 바뀐다(고객이 신청 화면에서 본 금액과 실제 차감액이 달라진다).
     */
    public long customerChargeOf(long snapshotRate, com.commerce.api.global.common.CancelReason.Fault fault) {
        return fault == com.commerce.api.global.common.CancelReason.Fault.CUSTOMER ? snapshotRate : 0L;
    }

    /**
     * 셀러가 무는 회수비(P4) — 셀러 귀책일 때만. {@link #customerChargeOf}와 짝이며, 한 반품에서 둘 중
     * 최대 하나만 0이 아니다(귀책은 하나뿐이므로). 요율 스냅샷을 인자로 받는 이유도 같다.
     */
    public long sellerChargeOf(long snapshotRate, com.commerce.api.global.common.CancelReason.Fault fault) {
        return fault == com.commerce.api.global.common.CancelReason.Fault.SELLER ? snapshotRate : 0L;
    }
}
