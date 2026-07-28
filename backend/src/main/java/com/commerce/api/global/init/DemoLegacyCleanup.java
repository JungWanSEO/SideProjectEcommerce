package com.commerce.api.global.init;

import com.commerce.api.product.entity.Product;
import com.commerce.api.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 데모 DB에 남은 <b>초기 테스트 상품</b> 정리(dev 전용) — `DEMO-SEED-###`처럼 예전에 볼륨 테스트용으로 찍어낸 더미가
 * 상점의 대부분을 차지해 진짜 카탈로그를 덮는 것을 막는다. 지금은 {@link DemoDataSeeder}가 카탈로그를 직접
 * 만들므로 이 더미들은 역할이 끝났다.
 *
 * <p><b>주문에 물린 상품은 남긴다</b> — 주문 항목은 상품을 ID로 참조하고(FK 없음) 표시는 스냅샷으로 하지만,
 * 리뷰 자격(`hasActivePurchase`)·추천처럼 상품을 되짚는 경로가 있어 이력이 남은 상품은 지우지 않는 편이 안전하다.
 *
 * <p>삭제는 <b>JPQL 벌크</b>로 한다. 프로덕션 리포지토리에 dev 전용 delete 메서드를 심지 않으려고
 * {@link EntityManager}를 직접 쓴다 — 정리는 일회성 유지보수 작업이지 도메인 기능이 아니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
class DemoLegacyCleanup {

    private final EntityManager em;
    private final ProductRepository productRepository;

    /**
     * 이름이 접두어로 시작하는 상품과 그 참조 행을 정리한다.
     *
     * @return 실제로 삭제한 상품 수
     */
    int purgeProductsNamedWith(List<String> namePrefixes) {
        List<Product> matched = productRepository.findAll().stream()
                .filter(p -> namePrefixes.stream().anyMatch(prefix -> p.getName().startsWith(prefix)))
                .toList();
        if (matched.isEmpty()) {
            return 0;
        }
        Set<Long> candidateIds = matched.stream().map(Product::getId).collect(Collectors.toSet());
        Set<Long> ordered = Set.copyOf(em.createQuery(
                        "select distinct oi.productId from OrderItem oi where oi.productId in :ids", Long.class)
                .setParameter("ids", candidateIds).getResultList());

        List<Product> deletable = matched.stream().filter(p -> !ordered.contains(p.getId())).toList();
        if (deletable.isEmpty()) {
            return 0;
        }
        Set<Long> ids = deletable.stream().map(Product::getId).collect(Collectors.toSet());

        // 상품을 ID로 참조하는 행들(FK가 없어 자동 정리되지 않는다). 옵션·이미지는 상품 cascade로 함께 지워진다.
        bulkDelete("delete from ActivityLog a where a.productId in :ids", ids);
        bulkDelete("delete from Recommendation r where r.productId in :ids", ids);
        bulkDelete("delete from ProductCoOccurrence c where c.productId in :ids or c.referenceProductId in :ids", ids);
        bulkDelete("delete from Wishlist w where w.productId in :ids", ids);
        bulkDelete("delete from Review r where r.productId in :ids", ids);
        bulkDelete("delete from CartItem ci where ci.productId in :ids", ids);
        bulkDelete("delete from StockReservation s where s.optionId in "
                + "(select o.id from ProductOption o where o.product.id in :ids)", ids);

        productRepository.deleteAll(deletable);
        em.flush();   // 이어지는 카탈로그 INSERT보다 DELETE가 먼저 나가도록(벌크/영속성 컨텍스트 순서 함정)
        if (!ordered.isEmpty()) {
            log.info("[demo-seed] 초기 테스트 상품 {}건 삭제 · 주문 이력이 있는 {}건은 보존", deletable.size(), ordered.size());
        } else {
            log.info("[demo-seed] 초기 테스트 상품 {}건 삭제", deletable.size());
        }
        return deletable.size();
    }

    private void bulkDelete(String jpql, Set<Long> ids) {
        em.createQuery(jpql).setParameter("ids", ids).executeUpdate();
    }
}
