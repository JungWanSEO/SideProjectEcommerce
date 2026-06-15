package com.commerce.api.activity.entity;

/**
 * 행동 로그 유형. 지금은 상품 조회(VIEW)만 기록한다.
 *
 * <p>찜·구매는 각 도메인 테이블(wishlist·order_item)에 이미 있으므로 여기서 중복 기록하지 않고,
 * 추천 배치(Step 2)가 세 신호(조회·찜·구매)를 가중 합산할 때 각 출처에서 읽어 온다.
 * (향후 SEARCH·CLICK 같은 유형이 생기면 여기에 추가 — enum 값은 알파벳순 유지.)
 */
public enum ActivityType {
    VIEW
}
