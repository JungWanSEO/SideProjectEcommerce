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
import com.commerce.api.order.entity.OrderStatus;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
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
    private final SellerRepository sellerRepository;
    private final PaymentRepository paymentRepository;   // 재귀속 안전장치(실결제 주문 식별)
    private final ActivityLogRepository activityLogRepository;
    private final RecommendationRepository recommendationRepository;
    private final WishlistService wishlistService;
    private final PasswordEncoder passwordEncoder;

    private static final List<String> CATEGORIES = List.of("아우터", "상의", "하의", "신발", "액세서리");
    private static final List<String> BRANDS = List.of("Maison Clay", "Daily Form", "Nord Atelier");

    /**
     * 데모 셀러(입점사) — 정산 수취 주체. 2곳을 두는 이유는 데모에서 <b>멀티셀러 주문</b>(한 주문 → 셀러별 shipment
     * 팬아웃·셀러별 알림)이 실제로 보이게 하기 위함이다. 셀러 1곳이 브랜드 2개를 운영해 seller 1:N brand도 함께 드러난다.
     */
    private static final List<DemoSeller> DEMO_SELLERS = List.of(
            new DemoSeller("메종클레이", 0.10, "데모은행 110-000-100001", "123-45-67890"),
            new DemoSeller("노드폼컴퍼니", 0.12, "데모은행 110-000-100002", "234-56-78901"));

    /**
     * 브랜드 → 셀러 귀속. 결제 시점 셀러 스냅샷이 타는 바로 그 링크다
     * ({@code OrderProcessor}: 상품 → brandId → {@code Brand.sellerId} → {@code OrderItem.sellerId}).
     * 이 매핑이 비어 있으면 실제 체크아웃으로 주문해도 항목이 미귀속(null)이라 셀러 콘솔이 텅 빈다.
     */
    private static final Map<String, String> BRAND_SELLER = Map.of(
            "Maison Clay", "메종클레이",
            "Daily Form", "노드폼컴퍼니",
            "Nord Atelier", "노드폼컴퍼니");

    /** 셀러 콘솔 로그인용 운영자 계정 — 이메일 → 셀러명. password=demopass1234, role=SELLER. */
    private static final Map<String, String> SELLER_ACCOUNTS = Map.of(
            "seller1@commerce.com", "메종클레이",
            "seller2@commerce.com", "노드폼컴퍼니");

    private record DemoSeller(String name, double commissionRate, String payoutAccount, String businessNumber) {
    }

    /** 상품명 → [카테고리, 브랜드]. 기존 카탈로그(P01~P07)를 분류한다. */
    private static final Map<String, String[]> PRODUCT_TAXONOMY = Map.of(
            "P01-Cap", new String[]{"액세서리", "Daily Form"},
            "P02-Hoodie", new String[]{"상의", "Maison Clay"},
            "P03-Jacket", new String[]{"아우터", "Nord Atelier"},
            "P04-Socks", new String[]{"액세서리", "Daily Form"},
            "P05-Sneakers", new String[]{"신발", "Nord Atelier"},
            "P06-Tee", new String[]{"상의", "Maison Clay"},
            "P07-Pants", new String[]{"하의", "Daily Form"});

    /** 데모 세일 상품(정가=판매가×markup) — %OFF·취소선·SALE 필터/할인율 정렬을 데모에서 보이게. */
    private static final Map<String, Double> DEMO_SALES = Map.of(
            "P02-Hoodie", 1.30,     // ≈23% off
            "P05-Sneakers", 1.50,   // ≈33% off
            "P07-Pants", 1.20);     // ≈17% off

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
        Map<String, Seller> sellers = ensureSellers();
        Map<String, Brand> brands = ensureBrands(sellers);
        Map<String, Product> products = mapProductTaxonomy(categories, brands);
        ensureSellerAccounts(sellers);
        List<Member> demoMembers = ensureDemoMembers();
        seedDemoOrders(demoMembers, products, brands);
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

    /** 데모 셀러(없으면 생성). 이름이 자연키(UNIQUE)라 재기동해도 중복 생성되지 않는다. */
    private Map<String, Seller> ensureSellers() {
        Map<String, Seller> byName = new HashMap<>();
        for (DemoSeller spec : DEMO_SELLERS) {
            Seller seller = sellerRepository.findByName(spec.name())
                    .orElseGet(() -> sellerRepository.save(Seller.create(
                            spec.name(), spec.commissionRate(), spec.payoutAccount(), spec.businessNumber())));
            byName.put(spec.name(), seller);
        }
        return byName;
    }

    /**
     * 데모 브랜드(없으면 생성) + <b>셀러 귀속</b>. 카테고리/브랜드 매핑과 같은 철학으로 매 기동 시 기대값으로 맞춘다
     * (더티 체킹 flush). 이 귀속이 있어야 이후 체크아웃이 항목에 sellerId 스냅샷을 남긴다.
     */
    private Map<String, Brand> ensureBrands(Map<String, Seller> sellers) {
        Map<String, Brand> byName = new HashMap<>();
        brandRepository.findAll().forEach(b -> byName.put(b.getName(), b));
        for (String name : BRANDS) {
            Brand brand = byName.computeIfAbsent(name, n -> brandRepository.save(Brand.create(n)));
            Seller owner = sellers.get(BRAND_SELLER.get(name));
            if (owner != null && !owner.getId().equals(brand.getSellerId())) {
                brand.assignSeller(owner.getId());
            }
        }
        return byName;
    }

    /**
     * 셀러 콘솔 로그인용 운영자 계정(없으면 생성) — role=SELLER + sellerId 연결.
     * 승격은 반드시 {@link Member#assignAsSeller(Long)}로 한다(셀러 연결 없는 SELLER = 빈 스코프로 콘솔이 깨짐).
     */
    private void ensureSellerAccounts(Map<String, Seller> sellers) {
        SELLER_ACCOUNTS.forEach((email, sellerName) -> {
            Seller seller = sellers.get(sellerName);
            if (seller == null) {
                return;
            }
            Member operator = memberRepository.findByEmail(email).orElseGet(() ->
                    memberRepository.save(Member.builder()
                            .email(email)
                            .password(passwordEncoder.encode("demopass1234"))
                            .nickname(sellerName + " 운영자")
                            .role(Role.SELLER)
                            .build()));
            if (!seller.getId().equals(operator.getSellerId())) {
                operator.assignAsSeller(seller.getId());   // 신규 생성분의 sellerId 연결 + 기존 계정 재연결
            }
        });
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
            Double markup = DEMO_SALES.get(p.getName());
            if (markup != null) {
                p.applyOriginalPrice(Math.round(p.getPrice() * markup));   // 정가=판매가×markup → 할인 노출
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

    /**
     * 데모 다중항목 PAID 주문 — 이미 있으면 건너뛴다. 재고는 건드리지 않는다(신호용 이력).
     *
     * <p>항목의 sellerId는 실제 체크아웃과 <b>같은 규칙</b>(상품 → brandId → {@code Brand.sellerId})으로 채운다.
     * 이 스냅샷이 있어야 {@link Order#markPaid()}의 팬아웃이 셀러별 shipment를 만들고, 셀러 콘솔·셀러 알림·
     * 셀러 반품 화면에 데모 데이터가 실제로 보인다.
     */
    private void seedDemoOrders(List<Member> demoMembers, Map<String, Product> products, Map<String, Brand> brands) {
        if (demoMembers.isEmpty()) {
            return;
        }
        Map<Long, Long> sellerByProductId = sellerByProductId(products, brands);
        if (!ensureDemoOrdersAttributable(demoMembers, sellerByProductId)) {
            return; // 이미 시드됨(귀속 완료) 또는 사람이 만든 주문이 섞여 있음
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
                        .sellerId(sellerByProductId.get(p.getId()))   // 체크아웃과 동일한 셀러 귀속 스냅샷
                        .productName(p.getName())
                        .size(opt.getSize())
                        .orderPrice(p.getPrice())
                        .quantity(1)
                        .build());
            }
            if (order.getOrderItems().size() < 2) {
                continue; // 함께 산 신호엔 2개 이상 필요
            }
            order.markPaid();   // 셀러별 shipment 팬아웃(멀티셀러 주문이면 2건)
            orderRepository.save(order);
            created++;
        }
        log.info("[demo-seed] 데모 다중항목 PAID 주문 {}건 생성(셀러 귀속)", created);
    }

    /** 상품ID → 셀러ID. 체크아웃과 같은 경로(상품 → brandId → Brand.sellerId)를 미리 펼쳐둔 조회표. */
    private Map<Long, Long> sellerByProductId(Map<String, Product> products, Map<String, Brand> brands) {
        Map<Long, Long> sellerByBrandId = new HashMap<>();
        brands.values().stream()
                .filter(b -> b.getSellerId() != null)
                .forEach(b -> sellerByBrandId.put(b.getId(), b.getSellerId()));
        Map<Long, Long> byProduct = new HashMap<>();
        for (Product p : products.values()) {
            Long sellerId = p.getBrandId() == null ? null : sellerByBrandId.get(p.getBrandId());
            if (sellerId != null) {
                byProduct.put(p.getId(), sellerId);
            }
        }
        return byProduct;
    }

    /**
     * 데모 주문을 (재)생성해도 되는 상태로 만든다. 셀러 귀속 이전에 시드된 주문은 항목 sellerId가 전부 null이라
     * 셀러 콘솔·셀러 알림·셀러 반품이 <b>영구히 빈 화면</b>이 된다 — 그런 주문만 지우고 아래에서 다시 만든다.
     *
     * <p>안전장치(하나라도 어긋나면 아무것도 지우지 않는다): ① 데모 회원의 주문일 것 ② 상태가 PAID일 것
     * (배송·취소·반품이 진행된 주문 제외) ③ 결제행이 없을 것(= 정산·대사가 물린 실주문 제외) ④ 모든 항목이
     * 미귀속일 것 ⑤ 그중 최소 하나는 지금 귀속 가능할 것(브랜드에 셀러가 붙어 있을 것). 사람이 만든 주문이
     * 하나라도 섞여 있으면 <b>전체를 보존</b>하고 건너뛴다(부분 삭제로 데모가 반쪽 나는 것 방지).
     *
     * @return true면 "데모 주문 없음" 상태이므로 새로 생성해야 한다
     */
    private boolean ensureDemoOrdersAttributable(List<Member> demoMembers, Map<Long, Long> sellerByProductId) {
        List<Long> memberIds = demoMembers.stream().map(Member::getId).toList();
        List<Order> existing = orderRepository.findByMemberIdIn(memberIds);
        if (existing.isEmpty()) {
            return true;
        }
        boolean allReplaceable = existing.stream().allMatch(o -> isUnattributedSeedOrder(o, sellerByProductId));
        if (!allReplaceable) {
            return false;   // 이미 귀속됐거나(정상) 실주문이 섞임 → 그대로 둔다
        }
        orderRepository.deleteAll(existing);   // 항목·이력·shipment는 cascade + orphanRemoval로 함께 삭제
        orderRepository.flush();               // 재생성 INSERT보다 DELETE가 먼저 나가도록 명시적 flush
        log.info("[demo-seed] 셀러 미귀속 데모 주문 {}건 삭제 — 귀속본으로 재생성", existing.size());
        return true;
    }

    /** 이 주문이 "셀러 귀속 이전 시드가 만든 것"으로 판정되는가(위 ②~⑤ 조건). */
    private boolean isUnattributedSeedOrder(Order order, Map<Long, Long> sellerByProductId) {
        if (order.getStatus() != OrderStatus.PAID || paymentRepository.existsByOrderId(order.getId())) {
            return false;
        }
        boolean anyAttributable = false;
        for (OrderItem item : order.getOrderItems()) {
            if (item.getSellerId() != null) {
                return false;
            }
            if (sellerByProductId.containsKey(item.getProductId())) {
                anyAttributable = true;
            }
        }
        return anyAttributable;
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
