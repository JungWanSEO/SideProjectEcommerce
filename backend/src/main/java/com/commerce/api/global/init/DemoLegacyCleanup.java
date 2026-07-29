package com.commerce.api.global.init;

import com.commerce.api.product.entity.Product;
import com.commerce.api.product.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
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

    /**
     * 부하 테스트가 만든 회원(예: {@code load-1@commerce.com})과 그들이 만든 흔적을 정리한다.
     *
     * <p>k6 시나리오가 회원 200명을 가입시키고 쿠폰을 집게 했던 잔재로, 어드민 회원 목록이 이걸로 덮인다.
     * <b>돈·이력이 걸린 회원은 남긴다</b>: 주문·리뷰·감사 로그가 하나라도 있으면 건드리지 않는다
     * (부하 회원은 정의상 이런 게 없다 — 있으면 사람이 쓴 계정일 가능성이 크다).
     *
     * @return 실제로 삭제한 회원 수
     */
    int purgeMembersWithEmailPrefix(List<String> emailPrefixes) {
        List<Long> matched = em.createQuery(
                        "select m.id from Member m where " + likeAny("m.email", emailPrefixes), Long.class)
                .getResultList();
        if (matched.isEmpty()) {
            return 0;
        }
        Set<Long> keep = new HashSet<>();
        keep.addAll(idsIn("select distinct o.memberId from Order o where o.memberId in :ids", matched));
        keep.addAll(idsIn("select distinct r.memberId from Review r where r.memberId in :ids", matched));
        keep.addAll(idsIn("select distinct a.actorMemberId from AuditLog a where a.actorMemberId in :ids", matched));

        Set<Long> ids = matched.stream().filter(id -> !keep.contains(id)).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return 0;
        }
        Set<Long> touchedCoupons = Set.copyOf(em.createQuery(
                        "select distinct mc.couponId from MemberCoupon mc where mc.memberId in :ids", Long.class)
                .setParameter("ids", ids).getResultList());

        bulkDelete("delete from MemberCoupon mc where mc.memberId in :ids", ids);
        bulkDelete("delete from RefreshToken t where t.memberId in :ids", ids);
        bulkDelete("delete from Wishlist w where w.memberId in :ids", ids);
        bulkDelete("delete from ActivityLog a where a.memberId in :ids", ids);
        bulkDelete("delete from Recommendation r where r.memberId in :ids", ids);
        bulkDelete("delete from Address ad where ad.memberId in :ids", ids);
        bulkDelete("delete from CartItem ci where ci.cart.id in (select c.id from Cart c where c.memberId in :ids)", ids);
        bulkDelete("delete from Cart c where c.memberId in :ids", ids);
        bulkDelete("delete from Member m where m.id in :ids", ids);
        resyncIssuedCounts(touchedCoupons);   // 발급 이력을 지웠으니 쿠폰의 발급 수 카운터도 실제와 맞춘다
        em.flush();
        log.info("[demo-seed] 부하 테스트 회원 {}건 정리 (이력 보유 {}건은 보존)", ids.size(), keep.size());
        return ids.size();
    }

    /**
     * 부하 테스트가 만든 쿠폰과 그 발급 이력을 정리한다. <b>주문에 실제로 사용된 코드는 남긴다</b>
     * (주문의 couponCode는 스냅샷이라 참조가 깨지진 않지만, 사용 이력이 있는 프로모션은 흔적을 보존하는 편이 안전).
     */
    int purgeCouponsNamedWith(List<String> namePrefixes) {
        List<Long> matched = em.createQuery(
                        "select c.id from Coupon c where " + likeAny("c.name", namePrefixes), Long.class)
                .getResultList();
        if (matched.isEmpty()) {
            return 0;
        }
        Set<String> usedCodes = Set.copyOf(em.createQuery(
                "select distinct o.couponCode from Order o where o.couponCode is not null", String.class)
                .getResultList());
        Set<Long> ids = em.createQuery("select c.id from Coupon c where c.id in :ids and c.code not in :codes", Long.class)
                .setParameter("ids", matched)
                .setParameter("codes", usedCodes.isEmpty() ? Set.of("") : usedCodes)
                .getResultList().stream().collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return 0;
        }
        bulkDelete("delete from MemberCoupon mc where mc.couponId in :ids", ids);
        bulkDelete("delete from Coupon c where c.id in :ids", ids);
        em.flush();
        log.info("[demo-seed] 부하 테스트 쿠폰 {}건 정리", ids.size());
        return ids.size();
    }

    /** 스모크 테스트가 남긴 카테고리 정리 — <b>상품도 자식 카테고리도 없을 때만</b> 지운다. */
    int purgeEmptyCategoriesNamed(List<String> names) {
        List<Long> ids = em.createQuery(
                        "select c.id from Category c where c.name in :names "
                                + "and not exists (select 1 from Product p where p.categoryId = c.id) "
                                + "and not exists (select 1 from Category child where child.parentId = c.id)", Long.class)
                .setParameter("names", names).getResultList();
        if (ids.isEmpty()) {
            return 0;
        }
        bulkDelete("delete from Category c where c.id in :ids", Set.copyOf(ids));
        em.flush();
        log.info("[demo-seed] 빈 잔재 카테고리 {}건 정리", ids.size());
        return ids.size();
    }

    /** 남은 쿠폰의 issuedCount를 실제 보유 수로 재동기화(발급 이력을 지운 뒤 "소진됨"으로 보이는 것 방지). */
    private void resyncIssuedCounts(Set<Long> couponIds) {
        for (Long couponId : couponIds) {
            Long remaining = em.createQuery(
                            "select count(mc) from MemberCoupon mc where mc.couponId = :id", Long.class)
                    .setParameter("id", couponId).getSingleResult();
            em.createQuery("update Coupon c set c.issuedCount = :n where c.id = :id")
                    .setParameter("n", remaining.intValue())
                    .setParameter("id", couponId)
                    .executeUpdate();
        }
    }

    /** {@code field like 'p1%' or field like 'p2%'} — 접두어 목록을 JPQL 조건으로. */
    private String likeAny(String field, List<String> prefixes) {
        return prefixes.stream()
                .map(p -> field + " like '" + p.replace("'", "''") + "%'")
                .collect(Collectors.joining(" or "));
    }

    private List<Long> idsIn(String jpql, List<Long> ids) {
        return em.createQuery(jpql, Long.class).setParameter("ids", ids).getResultList();
    }

    private void bulkDelete(String jpql, Set<Long> ids) {
        em.createQuery(jpql).setParameter("ids", ids).executeUpdate();
    }
}
