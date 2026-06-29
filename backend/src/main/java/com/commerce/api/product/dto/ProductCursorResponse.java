package com.commerce.api.product.dto;

import java.util.List;

/**
 * 커서 기반(no-offset) 상품 피드 응답 — 무한 스크롤용.
 *
 * <p>offset 페이지네이션(LIMIT/OFFSET)은 뒤 페이지로 갈수록 느려지고(앞 행을 다 스캔), 그 사이 삽입/삭제로
 * 행이 밀려 중복·누락이 생긴다. 커서(마지막으로 본 id)로 "그 다음부터"를 집으면 항상 인덱스 탐색 한 번이라
 * 페이지 깊이와 무관하게 빠르고, 삽입에도 안정적이다.
 *
 * @param items      이번 페이지 상품(최신순=id desc)
 * @param nextCursor 다음 페이지 요청에 넘길 커서(이번 페이지 마지막 상품 id). 더 없으면 null.
 * @param hasNext    다음 페이지 존재 여부
 */
public record ProductCursorResponse(
        List<ProductResponse> items,
        Long nextCursor,
        boolean hasNext
) {
}
