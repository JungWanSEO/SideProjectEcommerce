package com.commerce.api.dashboard.dto;

import com.commerce.api.product.dto.LowStockOption;
import java.util.List;

/**
 * 재고 임박·품절 리포트 (어드민).
 *
 * <p>카운트는 <b>전체</b> 기준이고 items는 상위 limit개만 담는다 — "품절 12건 중 상위 10건" 식으로
 * 위젯이 규모와 목록을 함께 보여줄 수 있게. 판매중지 상품은 제외(채울 이유가 없다).
 *
 * @param threshold     임박 기준 재고(이하)
 * @param soldOutCount  품절(재고 0) 옵션 수
 * @param lowStockCount 임박(1 ~ threshold) 옵션 수
 * @param items         재고 적은 순 상위 목록(품절이 맨 위)
 */
public record LowStockResponse(
        int threshold,
        long soldOutCount,
        long lowStockCount,
        List<LowStockOption> items
) {
}
