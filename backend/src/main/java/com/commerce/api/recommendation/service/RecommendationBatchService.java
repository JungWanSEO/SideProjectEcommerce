package com.commerce.api.recommendation.service;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.entity.Recommendation;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.wishlist.entity.Wishlist;
import com.commerce.api.wishlist.repository.WishlistRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "나를 위한 추천" 배치 — 회원별 추천을 미리 계산해 recommendation 테이블에 저장(precompute).
 *
 * <p><b>규칙 기반 v1</b>(ML/ES 아님):
 * <ol>
 *   <li><b>신호 수집</b> — 구매(×3)·찜(×2)·조회(최근 {@value #VIEW_WINDOW_DAYS}일 ×1)를 회원별 (상품→가중치)로.
 *   <li><b>선호 도출</b> — 상호작용 상품들의 <b>카테고리·브랜드별 가중 합</b> = 친화도.
 *   <li><b>후보·점수</b> — 선호 카테고리/브랜드의 ON_SALE 상품 중 <b>이미 산/찜한 건 제외</b> →
 *       점수 = 친화도×{@value #AFFINITY_WEIGHT} + 인기도(찜수·평점). 친화도가 지배, 인기도가 가산/타이브레이크.
 *   <li><b>저장</b> — 회원별 상위 {@value #TOP_N}개를 지우고-다시-넣기(멱등적 재계산).
 * </ol>
 *
 * <p>이력이 없는 회원(콜드스타트)은 여기서 건너뛰고, 읽기({@code RecommendationService})가 전체 인기순으로 폴백한다.
 *
 * <p>self-invocation 함정 회피: {@code @Scheduled}와 {@code @Transactional}을 <b>같은 run() 메서드</b>에 둔다 —
 * 스케줄러·컨트롤러 모두 프록시 경유로 호출하므로 트랜잭션이 정상 적용된다(아웃박스 폴러 교훈).
 */
@Service
@RequiredArgsConstructor
public class RecommendationBatchService {

    private final OrderRepository orderRepository;
    private final WishlistRepository wishlistRepository;
    private final ActivityLogRepository activityLogRepository;
    private final ProductRepository productRepository;
    private final RecommendationRepository recommendationRepository;

    private static final int PURCHASE_WEIGHT = 3;
    private static final int WISHLIST_WEIGHT = 2;
    private static final int VIEW_WEIGHT = 1;
    private static final int VIEW_WINDOW_DAYS = 30;
    private static final int TOP_N = 10;
    private static final double AFFINITY_WEIGHT = 10.0;

    /** 추천 재계산. 스케줄(매시 정각) 자동 실행 + ADMIN 수동 트리거 공용. 반환=생성된 추천 수. */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public int run() {
        LocalDateTime since = LocalDateTime.now().minusDays(VIEW_WINDOW_DAYS);

        // 1) 회원별 신호 수집: interactions = member -> (product -> 가중치), exclude = member -> 구매∪찜(추천에서 제외)
        Map<Long, Map<Long, Integer>> interactions = new HashMap<>();
        Map<Long, Set<Long>> exclude = new HashMap<>();
        for (Order order : orderRepository.findByStatusIn(OrderStatus.PURCHASED)) {
            for (OrderItem item : order.getOrderItems()) {
                addWeight(interactions, order.getMemberId(), item.getProductId(), PURCHASE_WEIGHT);
                exclude.computeIfAbsent(order.getMemberId(), k -> new HashSet<>()).add(item.getProductId());
            }
        }
        for (Wishlist w : wishlistRepository.findAll()) {
            addWeight(interactions, w.getMemberId(), w.getProductId(), WISHLIST_WEIGHT);
            exclude.computeIfAbsent(w.getMemberId(), k -> new HashSet<>()).add(w.getProductId());
        }
        for (ActivityLog a : activityLogRepository.findByCreatedAtAfter(since)) {
            addWeight(interactions, a.getMemberId(), a.getProductId(), VIEW_WEIGHT);
        }

        // 2) 상품 카탈로그(한 번 로드)
        List<Product> all = productRepository.findAll();
        Map<Long, Product> byId = all.stream().collect(Collectors.toMap(Product::getId, p -> p));
        List<Product> onSale = all.stream().filter(p -> p.getStatus() == ProductStatus.ON_SALE).toList();

        // 3) 회원별 추천 계산 + 저장(지우고 다시 넣기)
        int total = 0;
        for (Map.Entry<Long, Map<Long, Integer>> entry : interactions.entrySet()) {
            Long memberId = entry.getKey();
            List<Recommendation> recs = recommendFor(
                    memberId, entry.getValue(), exclude.getOrDefault(memberId, Set.of()), byId, onSale);
            recommendationRepository.deleteByMemberId(memberId);
            recommendationRepository.saveAll(recs);
            total += recs.size();
        }
        return total;
    }

    /** 한 회원의 추천 상위 N개 산출. 선호 카테고리/브랜드에 걸리고(친화도&gt;0) 보유하지 않은 ON_SALE 상품만. */
    private List<Recommendation> recommendFor(Long memberId, Map<Long, Integer> interacted, Set<Long> exclude,
                                              Map<Long, Product> byId, List<Product> onSale) {
        Map<Long, Integer> catScore = new HashMap<>();
        Map<Long, Integer> brandScore = new HashMap<>();
        for (Map.Entry<Long, Integer> pe : interacted.entrySet()) {
            Product p = byId.get(pe.getKey());
            if (p == null) {
                continue;
            }
            if (p.getCategoryId() != null) {
                catScore.merge(p.getCategoryId(), pe.getValue(), Integer::sum);
            }
            if (p.getBrandId() != null) {
                brandScore.merge(p.getBrandId(), pe.getValue(), Integer::sum);
            }
        }
        return onSale.stream()
                .filter(p -> !exclude.contains(p.getId()))
                .map(p -> Map.entry(p, affinity(p, catScore, brandScore)))
                // 친화도 0(선호 카테고리/브랜드 아님)은 제외 — 인기상품 폴백은 콜드스타트(읽기)에서만.
                .filter(e -> e.getValue() > 0)
                .map(e -> Recommendation.of(memberId, e.getKey().getId(),
                        e.getValue() * AFFINITY_WEIGHT + popularity(e.getKey())))
                .sorted(Comparator.comparingDouble(Recommendation::getScore).reversed()
                        .thenComparing(Recommendation::getProductId))
                .limit(TOP_N)
                .toList();
    }

    private int affinity(Product p, Map<Long, Integer> catScore, Map<Long, Integer> brandScore) {
        return catScore.getOrDefault(p.getCategoryId(), 0) + brandScore.getOrDefault(p.getBrandId(), 0);
    }

    /** 인기도 = 찜 수 + 리뷰 수×0.5 + 평점평균(0~5). 친화도 동률 시 인기 상품을 위로. */
    private double popularity(Product p) {
        return p.getWishlistCount() + p.getRatingCount() * 0.5 + p.getRatingAverage();
    }

    private void addWeight(Map<Long, Map<Long, Integer>> map, Long memberId, Long productId, int weight) {
        map.computeIfAbsent(memberId, k -> new HashMap<>()).merge(productId, weight, Integer::sum);
    }
}
