package com.commerce.api.global.init;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
import com.commerce.api.category.entity.Category;
import com.commerce.api.category.repository.CategoryRepository;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.wishlist.service.WishlistService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 시드 — 로컬 dev 프로파일에서만 동작(@Profile("dev")). 추천/함께 산 상품을 브라우저에서 보이게 하기 위한
 * 최소 데이터: 카테고리·브랜드 + 카탈로그 매핑 + 다중항목 PAID 주문(co-occurrence 신호).
 *
 * <p><b>멱등</b>: 자연키(이름·이메일)로 "없으면 생성"하고, 주문/신호는 이미 있으면 건너뛴다 → 재기동해도 중복 없음.
 * 스키마는 Flyway가, 데모 데이터는 이 시드 빈이 — 책임 분리. 운영 프로파일에선 빈 자체가 생성되지 않는다.
 *
 * <p>self-invocation 회피: 트랜잭션 경계인 {@link #seed()}는 {@link DemoDataInitializer}가
 * <b>다른 빈을 통해</b> 호출한다(같은 클래스 내부 호출이 아니라 프록시 경유 → @Transactional 적용).
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DemoDataSeeder {

    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;
    private final OrderRepository orderRepository;
    private final ActivityLogRepository activityLogRepository;
    private final RecommendationRepository recommendationRepository;
    private final WishlistService wishlistService;
    private final PasswordEncoder passwordEncoder;

    private static final List<String> CATEGORIES = List.of("아우터", "상의", "하의", "신발", "액세서리");
    private static final List<String> BRANDS = List.of("Maison Clay", "Daily Form", "Nord Atelier");

    /** 상품명 → [카테고리, 브랜드]. 기존 카탈로그(P01~P07)를 분류한다. */
    private static final Map<String, String[]> PRODUCT_TAXONOMY = Map.of(
            "P01-Cap", new String[]{"액세서리", "Daily Form"},
            "P02-Hoodie", new String[]{"상의", "Maison Clay"},
            "P03-Jacket", new String[]{"아우터", "Nord Atelier"},
            "P04-Socks", new String[]{"액세서리", "Daily Form"},
            "P05-Sneakers", new String[]{"신발", "Nord Atelier"},
            "P06-Tee", new String[]{"상의", "Maison Clay"},
            "P07-Pants", new String[]{"하의", "Daily Form"});

    /** 데모 구매 회원 이메일. password=demopass1234. */
    private static final List<String> DEMO_EMAILS =
            List.of("demo1@commerce.com", "demo2@commerce.com", "demo3@commerce.com");

    /** 데모 다중항목 PAID 주문: (회원 인덱스 0~2, 상품명들). 함께 산 쌍을 형성한다. */
    private static final List<DemoOrder> DEMO_ORDERS = List.of(
            new DemoOrder(0, List.of("P02-Hoodie", "P07-Pants")),
            new DemoOrder(0, List.of("P02-Hoodie", "P07-Pants", "P01-Cap")),
            new DemoOrder(1, List.of("P06-Tee", "P07-Pants", "P04-Socks")),
            new DemoOrder(1, List.of("P06-Tee", "P01-Cap")),
            new DemoOrder(2, List.of("P02-Hoodie", "P06-Tee")),
            new DemoOrder(2, List.of("P07-Pants", "P04-Socks")));

    private record DemoOrder(int memberIndex, List<String> productNames) {
    }

    @Transactional
    public void seed() {
        cleanupTestData();
        Map<String, Category> categories = ensureCategories();
        Map<String, Brand> brands = ensureBrands();
        Map<String, Product> products = mapProductTaxonomy(categories, brands);
        List<Member> demoMembers = ensureDemoMembers();
        seedDemoOrders(demoMembers, products);
        seedBuyerSignals(products);
    }

    /** 1) 테스트 잔재 제거(상품 RecProdA/B·RecTestBrand·RecTestCat + 그 상품 참조 행). 이미 없으면 no-op. */
    private void cleanupTestData() {
        List<Product> cruft = productRepository.findAll().stream()
                .filter(p -> p.getName().equals("RecProdA") || p.getName().equals("RecProdB"))
                .toList();
        if (cruft.isEmpty()) {
            return;
        }
        Set<Long> cruftIds = cruft.stream().map(Product::getId).collect(Collectors.toSet());
        // FK 없는 ID 참조라 참조 행을 수동 정리(삭제될 상품을 가리키는 활동/추천).
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(a -> cruftIds.contains(a.getProductId())).toList());
        recommendationRepository.deleteAll(recommendationRepository.findAll().stream()
                .filter(r -> cruftIds.contains(r.getProductId())).toList());
        productRepository.deleteAll(cruft);
        brandRepository.findAll().stream().filter(b -> b.getName().equals("RecTestBrand"))
                .findFirst().ifPresent(brandRepository::delete);
        categoryRepository.findAll().stream().filter(c -> c.getName().equals("RecTestCat"))
                .findFirst().ifPresent(categoryRepository::delete);
        log.info("[demo-seed] 테스트 잔재 정리 — 상품 {} + RecTestBrand/RecTestCat 삭제", cruftIds);
    }

    private Map<String, Category> ensureCategories() {
        Map<String, Category> byName = new HashMap<>();
        categoryRepository.findAll().forEach(c -> byName.put(c.getName(), c));
        for (String name : CATEGORIES) {
            byName.computeIfAbsent(name, n -> categoryRepository.save(Category.create(n)));
        }
        return byName;
    }

    private Map<String, Brand> ensureBrands() {
        Map<String, Brand> byName = new HashMap<>();
        brandRepository.findAll().forEach(b -> byName.put(b.getName(), b));
        for (String name : BRANDS) {
            byName.computeIfAbsent(name, n -> brandRepository.save(Brand.create(n)));
        }
        return byName;
    }

    /** 기존 카탈로그 상품에 카테고리/브랜드 부여(dirty-checking flush로 저장). 반환=상품명→상품 맵. */
    private Map<String, Product> mapProductTaxonomy(Map<String, Category> categories, Map<String, Brand> brands) {
        Map<String, Product> byName = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            byName.put(p.getName(), p);
            String[] tax = PRODUCT_TAXONOMY.get(p.getName());
            if (tax != null) {
                Category cat = categories.get(tax[0]);
                Brand brand = brands.get(tax[1]);
                p.assignTaxonomy(cat == null ? null : cat.getId(), brand == null ? null : brand.getId());
            }
        }
        return byName;
    }

    /** 데모 구매 회원(없으면 생성). password=demopass1234, role=USER. */
    private List<Member> ensureDemoMembers() {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < DEMO_EMAILS.size(); i++) {
            String email = DEMO_EMAILS.get(i);
            int idx = i + 1;
            Member m = memberRepository.findByEmail(email).orElseGet(() ->
                    memberRepository.save(Member.builder()
                            .email(email)
                            .password(passwordEncoder.encode("demopass1234"))
                            .nickname("데모회원" + idx)
                            .role(Role.USER)
                            .build()));
            members.add(m);
        }
        return members;
    }

    /** 데모 다중항목 PAID 주문 — 이미 있으면(첫 데모회원이 주문 보유) 건너뛴다. 재고는 건드리지 않는다(신호용 이력). */
    private void seedDemoOrders(List<Member> demoMembers, Map<String, Product> products) {
        if (demoMembers.isEmpty()) {
            return;
        }
        Member first = demoMembers.get(0);
        if (orderRepository.findByMemberId(first.getId(), PageRequest.of(0, 1)).hasContent()) {
            return; // 이미 시드됨
        }
        int created = 0;
        for (DemoOrder spec : DEMO_ORDERS) {
            Member member = demoMembers.get(spec.memberIndex());
            Order order = Order.create(member.getId());
            for (String name : spec.productNames()) {
                Product p = products.get(name);
                if (p == null || p.getOptions().isEmpty()) {
                    continue;
                }
                ProductOption opt = p.getOptions().get(0);
                order.addItem(OrderItem.builder()
                        .productId(p.getId())
                        .optionId(opt.getId())
                        .brandId(p.getBrandId())
                        .sellerId(null)
                        .productName(p.getName())
                        .size(opt.getSize())
                        .orderPrice(p.getPrice())
                        .quantity(1)
                        .build());
            }
            if (order.getOrderItems().size() < 2) {
                continue; // 함께 산 신호엔 2개 이상 필요
            }
            order.markPaid();
            orderRepository.save(order);
            created++;
        }
        log.info("[demo-seed] 데모 다중항목 PAID 주문 {}건 생성", created);
    }

    /** buyer 개인화 신호(없을 때만 초기 시드). 기존 신호가 있으면 보존한다. */
    private void seedBuyerSignals(Map<String, Product> products) {
        Member buyer = memberRepository.findByEmail("buyer@commerce.com").orElse(null);
        if (buyer == null) {
            return;
        }
        boolean hasActivity = activityLogRepository.findAll().stream()
                .anyMatch(a -> a.getMemberId().equals(buyer.getId()));
        if (hasActivity) {
            return; // 기존 행동 신호 보존
        }
        Product hoodie = products.get("P02-Hoodie");
        if (hoodie != null) {
            wishlistService.add(buyer.getId(), hoodie.getId()); // 찜(카운터 일관 — 서비스 경유)
        }
        for (String name : List.of("P06-Tee", "P07-Pants", "P01-Cap")) {
            Product p = products.get(name);
            if (p != null) {
                activityLogRepository.save(ActivityLog.view(buyer.getId(), p.getId()));
            }
        }
        log.info("[demo-seed] buyer 행동 신호 시드(찜 1 + 조회 3)");
    }
}
