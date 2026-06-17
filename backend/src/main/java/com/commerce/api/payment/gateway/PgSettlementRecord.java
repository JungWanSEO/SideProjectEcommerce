package com.commerce.api.payment.gateway;

import java.time.LocalDate;

/**
 * PG 정산 리포트의 한 줄 — "PG가 통보한 거래".
 *
 * <p>대사(reconciliation)에서 우리 {@code SettlementEntry}와 {@code pgTransactionId}로 매칭하는
 * 대상이다. PG가 진실의 한 출처고, 우리 기록이 다른 출처 — 둘을 대조해 불일치를 찾는다.
 *
 * <p><b>MPG-2:</b> 리포트는 자신이 어느 PG의 것인지({@code provider})를 함께 싣는다 — 다중 PG를 합쳐
 * 대조할 때 불일치를 PG별로 분류·표시하기 위함. 거래 ID 프리픽스(예: KAKAO-)와 provider(KAKAOPAY)가
 * 다를 수 있으므로 프리픽스 파싱이 아니라 PG가 직접 알려주는 값을 쓴다.
 *
 * <p>{@code settledOn}은 PG가 이 거래를 정산한 날짜다 — 대사를 <b>일자별 윈도우</b>로 좁힐 때
 * 우리 {@code SettlementEntry.settledDate}와 같은 기준으로 양측을 거른다(실무는 매일 전날치만 대조).
 *
 * @param provider        이 거래를 처리한 PG (예: TOSS, KAKAOPAY)
 * @param pgTransactionId 거래 식별자(조인 키 — 우리 결제의 pgTransactionId와 같은 값)
 * @param amount          PG가 보고한 거래 금액(원)
 * @param status          PG 관점의 상태(PAID/REFUNDED)
 * @param settledOn       PG 정산일(대사 일자별 윈도우의 PG측 기준)
 */
public record PgSettlementRecord(String provider, String pgTransactionId, long amount,
        PgSettlementStatus status, LocalDate settledOn) {
}
