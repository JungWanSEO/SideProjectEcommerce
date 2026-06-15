package com.commerce.api.recommendation.service;

import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.entity.ProductCoOccurrence;
import com.commerce.api.recommendation.repository.ProductCoOccurrenceRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "함께 산 상품" 배치 — 기준 상품별 co-occurrence를 미리 계산해 product_cooccurrence 테이블에 저장(precompute).
 *
 * <p><b>규칙 기반(통계) v1</b>:
 * <ol>
 *   <li><b>쌍 집계</b> — 모든 PAID 주문을 돌며 한 주문에 함께 담긴 상품들의 (A↔B) 쌍에 +1.
 *       한 주문은 한 쌍에 최대 1회만 기여(distinct 상품) = "서로 다른 몇 개의 주문에서 함께 팔렸나"(표준 co-occurrence).
 *       <b>취소(CANCELLED) 항목은 제외</b> — 부분환불된 항목은 실제 구매가 아님(V24).
 *   <li><b>점수·후보</b> — 추천 후보는 ON_SALE 상품만(품절·판매중지는 추천하지 않음).
 *       점수 = 함께 산 횟수 × {@value #CO_BUY_WEIGHT} + 인기도(찜·평점). 함께 산 횟수가 지배, 인기도는 동률 타이브레이크.
 *   <li><b>저장</b> — 기준 상품별 상위 {@value #TOP_N}개. 전 테이블을 지우고 다시 넣는다(멱등적 전역 재계산).
 * </ol>
 *
 * <p>self-invocation 함정 회피: {@code @Scheduled}와 {@code @Transactional}을 <b>같은 run() 메서드</b>에 둔다
 * (스케줄러·컨트롤러 모두 프록시 경유 — 아웃박스 폴러 교훈). personalized 배치(매시 정각)와 30분 오프셋해 겹치지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class CoOccurrenceBatchService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductCoOccurrenceRepository coOccurrenceRepository;

    private static final int TOP_N = 10;
    /** 함께 산 횟수 가중치. 인기도(보통 한 자릿수)를 지배하도록 크게 — Recommendation의 AFFINITY_WEIGHT와 같은 발상. */
    private static final double CO_BUY_WEIGHT = 10.0;

    /** 함께 산 상품 재계산. 스케줄(매시 30분) 자동 + ADMIN 수동 트리거 공용. 반환=생성된 행 수. */
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public int run() {
        // 1) PAID 주문에서 쌍 집계: pairs.get(기준).get(함께산) = 함께 담긴 서로 다른 주문 수
        Map<Long, Map<Long, Integer>> pairs = new HashMap<>();
        for (Order order : orderRepository.findByStatus(OrderStatus.PAID)) {
            // 한 주문 안의 '활성' 항목들의 distinct 상품 ID
            //  - 취소(CANCELLED) 항목 제외, 옵션(사이즈)만 다른 같은 상품은 1개로 — "주문 단위 함께 샀나"가 기준.
            List<Long> productIds = order.getOrderItems().stream()
                    .filter(OrderItem::isActive)
                    .map(OrderItem::getProductId)
                    .distinct()
                    .toList();
            // 이 주문이 담은 모든 (A,B) 쌍에 양방향 +1 (자기 자신 쌍은 i<j 루프 구조상 생기지 않음)
            for (int i = 0; i < productIds.size(); i++) {
                for (int j = i + 1; j < productIds.size(); j++) {
                    addPair(pairs, productIds.get(i), productIds.get(j));
                    addPair(pairs, productIds.get(j), productIds.get(i));
                }
            }
        }

        // 2) 상품 카탈로그 한 번 로드(후보 ON_SALE 필터 + 인기도 타이브레이크용)
        Map<Long, Product> byId = productRepository.findAll().stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // 3) 기준 상품별 상위 N → 전 테이블 지우고 다시 넣기(멱등 재계산)
        coOccurrenceRepository.deleteAllInBatch();
        int total = 0;
        for (Map.Entry<Long, Map<Long, Integer>> entry : pairs.entrySet()) {
            List<ProductCoOccurrence> rows = topCoBought(entry.getKey(), entry.getValue(), byId);
            coOccurrenceRepository.saveAll(rows);
            total += rows.size();
        }
        return total;
    }

    /** 기준 상품의 함께 산 상품 상위 N개 산출. 후보는 카탈로그에 있고 ON_SALE인 상품만. */
    private List<ProductCoOccurrence> topCoBought(Long referenceProductId, Map<Long, Integer> coBought,
                                                  Map<Long, Product> byId) {
        return coBought.entrySet().stream()
                .filter(e -> {
                    Product candidate = byId.get(e.getKey());
                    return candidate != null && candidate.getStatus() == ProductStatus.ON_SALE;
                })
                .map(e -> {
                    Long productId = e.getKey();
                    int coBuyCount = e.getValue();
                    double score = coBuyCount * CO_BUY_WEIGHT + popularity(byId.get(productId));
                    return ProductCoOccurrence.of(referenceProductId, productId, coBuyCount, score);
                })
                .sorted(Comparator.comparingDouble(ProductCoOccurrence::getScore).reversed()
                        .thenComparing(ProductCoOccurrence::getProductId))
                .limit(TOP_N)
                .toList();
    }

    /** 인기도 = 찜 수 + 리뷰 수×0.5 + 평점평균. 함께 산 횟수 동률 시 인기 상품을 위로(추천 도메인 공통 발상). */
    private double popularity(Product p) {
        return p.getWishlistCount() + p.getRatingCount() * 0.5 + p.getRatingAverage();
    }

    private void addPair(Map<Long, Map<Long, Integer>> map, Long ref, Long other) {
        map.computeIfAbsent(ref, k -> new HashMap<>()).merge(other, 1, Integer::sum);
    }
}
