package com.commerce.api.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 취소·반품 사유 집계(#8 후속) — "왜 이탈했는가"를 사유·귀책 축으로 요약한다.
 *
 * <p>사유가 자유텍스트였다면 못 셌을 통계다. {@code CancelReason}으로 구조화해 둔 덕에 group-by 두 번으로 끝난다
 * (취소는 {@code order_item.cancel_reason}, 반품은 {@code return_request.reason_code}).
 *
 * <p><b>귀책(fault)</b>은 사유 enum이 들고 있는 메타(CUSTOMER/SELLER/PLATFORM/NONE)다. 셀러 귀책 비중이
 * 높아지면 정산 귀책·왕복 배송비 부담 정책으로 이어질 지점이라, 지금은 <b>보이게만</b> 한다(돈 경로 무연동).
 *
 * <p>사유는 add-only·nullable로 도입돼 <b>이전 데이터엔 사유가 없다</b> → 미기록 건수를 따로 노출해
 * "사유별 합계 < 전체 건수"가 오류로 보이지 않게 한다.
 */
@Schema(description = "취소·반품 사유 집계")
public record CancelReasonStatsResponse(

        @Schema(description = "취소된 주문 항목 총 건수(사유 무관)", example = "42")
        long totalCancelledItems,

        @Schema(description = "반품/교환 요청 총 건수(상태 무관)", example = "7")
        long totalReturns,

        @Schema(description = "사유가 기록되지 않은 취소 항목 수(사유 도입 이전 데이터·시스템 취소)", example = "5")
        long unrecordedCancels,

        @Schema(description = "사유가 기록되지 않은 반품 요청 수", example = "1")
        long unrecordedReturns,

        @Schema(description = "사유별 집계(건수 많은 순)")
        List<ReasonCount> byReason,

        @Schema(description = "귀책별 집계(건수 많은 순)")
        List<FaultCount> byFault
) {

    /** 사유 1건 — 취소·반품을 나눠 보여주고 합계로 정렬한다(같은 사유가 두 경로에서 온다). */
    @Schema(description = "사유별 건수")
    public record ReasonCount(
            @Schema(description = "사유 코드", example = "CHANGE_OF_MIND") String reason,
            @Schema(description = "귀책", example = "CUSTOMER") String fault,
            @Schema(description = "취소 건수", example = "12") long cancelCount,
            @Schema(description = "반품 건수", example = "3") long returnCount,
            @Schema(description = "합계", example = "15") long total
    ) {
    }

    /** 귀책 1건 — 사유들을 귀책으로 접은 값. */
    @Schema(description = "귀책별 건수")
    public record FaultCount(
            @Schema(description = "귀책(CUSTOMER/SELLER/PLATFORM/NONE)", example = "SELLER") String fault,
            @Schema(description = "합계", example = "9") long total
    ) {
    }
}
