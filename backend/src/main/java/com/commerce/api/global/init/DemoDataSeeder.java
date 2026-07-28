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
import com.commerce.api.product.entity.ProductStatus;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 시드 — <b>빈 DB를 라이브 데모가 가능한 상태로</b> 만든다: 카테고리·브랜드·셀러 + <b>카탈로그(상품·옵션·재고)</b>
 * + 셀러/구매 회원 계정 + 다중항목 PAID 주문(추천·함께 산 상품 신호).
 *
 * <p><b>켜는 방법</b>({@code app.demo-seed.enabled}): 로컬 dev 프로파일은 기본 ON(application-dev.yml),
 * 그 외(운영 포함)는 기본 OFF다. 배포한 데모 서버를 채울 땐 {@code APP_DEMO_SEED_ENABLED=true}로 <b>명시적으로</b>
 * 켠다 — 예전엔 {@code @Profile("dev")}로 묶여 있어 <b>운영 DB엔 상품이 한 개도 생기지 않았다</b>(카탈로그가
 * 오너 로컬 DB에만 존재했다). 시드가 카탈로그를 직접 만들게 되면서 "새 DB로 배포하면 빈 상점" 문제가 사라졌다.
 *
 * <p><b>멱등</b>: 자연키(상품명·브랜드명·셀러명·이메일)로 "없으면 생성"하고, 주문/신호는 이미 있으면 건너뛴다
 * → 재기동해도 중복 없음. 스키마는 Flyway가, 데모 데이터는 이 시드 빈이 — 책임 분리.
 *
 * <p>self-invocation 회피: 트랜잭션 경계인 {@link #seed()}는 {@link DemoDataInitializer}가
 * <b>다른 빈을 통해</b> 호출한다(같은 클래스 내부 호출이 아니라 프록시 경유 → @Transactional 적용).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
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

    private static final List<String> CATEGORIES = List.of("아우터", "상의", "하의", "원피스", "신발", "액세서리");
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

    /**
     * 데모 카탈로그 12종 — <b>시드가 직접 만든다</b>(예전엔 오너 로컬 DB에만 있던 P01~P07에 분류만 얹었다).
     *
     * <p>상품명은 FE의 이미지 폴백(`frontend/src/lib/productImage.ts`)이 <b>이름 키워드로</b> 일러스트를 고르도록
     * 지었다(후디·니트·스커트·선글라스…) — 그래서 {@code imageUrl}을 비워둬도 `public/products/*.svg` 12종이
     * 자연스럽게 붙고 이미지 호스팅이 필요 없다. 12종을 두면 카테고리 6개에 고르게 퍼져 PLP 필터·정렬·페이지네이션이
     * 데모에서 실제로 동작해 보인다.
     */
    private static final List<DemoProduct> CATALOG = List.of(
            // name                 category    brand           price     sale  sizes                 재고
            new DemoProduct("코튼 볼캡", "액세서리", "Daily Form", 29_000L, null, List.of("FREE"), 40),
            new DemoProduct("오버핏 후디", "상의", "Maison Clay", 89_000L, 1.30, List.of("S", "M", "L"), 30),
            new DemoProduct("울 블렌드 자켓", "아우터", "Nord Atelier", 219_000L, null, List.of("S", "M", "L"), 15),
            new DemoProduct("라이트 머플러", "액세서리", "Daily Form", 39_000L, null, List.of("FREE"), 40),
            new DemoProduct("레더 스니커즈", "신발", "Nord Atelier", 159_000L, 1.50, List.of("250", "260", "270"), 3),
            new DemoProduct("에센셜 티셔츠", "상의", "Maison Clay", 39_000L, null, List.of("S", "M", "L"), 60),
            new DemoProduct("와이드 슬랙스", "하의", "Daily Form", 79_000L, 1.20, List.of("28", "30", "32"), 25),
            new DemoProduct("캐시미어 니트", "상의", "Maison Clay", 129_000L, null, List.of("S", "M", "L"), 20),
            new DemoProduct("플리츠 스커트", "하의", "Daily Form", 69_000L, null, List.of("S", "M", "L"), 25),
            new DemoProduct("린넨 원피스", "원피스", "Maison Clay", 119_000L, 1.25, List.of("S", "M", "L"), 18),
            new DemoProduct("미니 토트백", "액세서리", "Nord Atelier", 149_000L, null, List.of("FREE"), 12),
            new DemoProduct("라운드 선글라스", "액세서리", "Nord Atelier", 89_000L, null, List.of("FREE"), 30));

    /**
     * 초기 카탈로그(P01~P07) → 현재 상품명. <b>삭제가 아니라 이름 변경</b>이라 상품 id가 유지돼
     * 기존 주문·리뷰·찜·추천·활동로그 참조가 전부 살아 있고 중복 상품도 생기지 않는다(로컬 dev DB 이행용).
     */
    private static final Map<String, String> LEGACY_RENAME = Map.of(
            "P01-Cap", "코튼 볼캡",
            "P02-Hoodie", "오버핏 후디",
            "P03-Jacket", "울 블렌드 자켓",
            "P04-Socks", "라이트 머플러",
            "P05-Sneakers", "레더 스니커즈",
            "P06-Tee", "에센셜 티셔츠",
            "P07-Pants", "와이드 슬랙스");

    /**
     * 데모 상품 정의. {@code saleMarkup}은 정가=판매가×markup(널이면 세일 아님) — %OFF·취소선·SALE 필터·
     * 할인율 정렬을 데모에서 보이게 한다. 판매가는 결제·정산의 기준이고 정가는 표시 전용(#5 불변식).
     */
    private record DemoProduct(String name, String category, String brand, long price, Double saleMarkup,
            List<String> sizes, int stockPerSize) {
    }

    /** 데모 구매 회원 이메일. password=demopass1234. */
    private static final List<String> DEMO_EMAILS =
            List.of("demo1@commerce.com", "demo2@commerce.com", "demo3@commerce.com");

    /**
     * 데모 다중항목 PAID 주문: (회원 인덱스 0~2, 상품명들). 함께 산 쌍을 형성한다.
     * 셀러가 섞인 조합(메종클레이 상의 + 데일리폼 하의)이 있어야 <b>멀티셀러 shipment 팬아웃</b>이 데모에 보인다.
     */
    private static final List<DemoOrder> DEMO_ORDERS = List.of(
            new DemoOrder(0, List.of("오버핏 후디", "와이드 슬랙스")),
            new DemoOrder(0, List.of("오버핏 후디", "와이드 슬랙스", "코튼 볼캡")),
            new DemoOrder(1, List.of("에센셜 티셔츠", "와이드 슬랙스", "라이트 머플러")),
            new DemoOrder(1, List.of("에센셜 티셔츠", "코튼 볼캡")),
            new DemoOrder(2, List.of("오버핏 후디", "에센셜 티셔츠")),
            new DemoOrder(2, List.of("와이드 슬랙스", "라이트 머플러")));

    private record DemoOrder(int memberIndex, List<String> productNames) {
    }

    @Transactional
    public void seed() {
        cleanupTestData();
        Map<String, Category> categories = ensureCategories();
        Map<String, Seller> sellers = ensureSellers();
        Map<String, Brand> brands = ensureBrands(sellers);
        Map<String, Product> products = ensureCatalog(categories, brands);
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

    /**
     * 카탈로그 보장 — <b>없으면 만들고</b>, 있으면 분류·세일가·누락 옵션만 맞춘다. 반환=상품명→상품 맵.
     *
     * <p>순서: ① 초기 이름(P01~P07)이 남아 있으면 <b>rename</b>(id 유지 → 기존 주문·리뷰·찜 참조 보존)
     * ② 카탈로그 12종을 자연키(이름)로 upsert ③ 카테고리/브랜드 귀속 + 정가(세일) ④ 없는 사이즈 옵션 추가.
     *
     * <p>가격·이미 있는 옵션의 재고는 <b>덮어쓰지 않는다</b> — 어드민에서 만진 값을 매 기동마다 되돌리면
     * 데모 중 조작이 사라지기 때문(분류·세일가는 카탈로그 정의가 단일 출처라 맞춘다).
     */
    private Map<String, Product> ensureCatalog(Map<String, Category> categories, Map<String, Brand> brands) {
        Map<String, Product> byName = new HashMap<>();
        for (Product p : productRepository.findAll()) {
            byName.put(p.getName(), p);
        }
        renameLegacyProducts(byName);

        for (DemoProduct spec : CATALOG) {
            Product product = byName.get(spec.name());
            if (product == null) {
                product = productRepository.save(Product.builder()
                        .name(spec.name())
                        .price(spec.price())
                        .description(spec.category() + " · " + spec.brand() + " 데모 상품")
                        .imageUrl(null)               // FE가 상품명 키워드로 일러스트를 고른다(productImage.ts)
                        .status(ProductStatus.ON_SALE)
                        .build());
                byName.put(spec.name(), product);
            }
            Category category = categories.get(spec.category());
            Brand brand = brands.get(spec.brand());
            product.assignTaxonomy(category == null ? null : category.getId(),
                    brand == null ? null : brand.getId());
            product.applyOriginalPrice(spec.saleMarkup() == null ? null
                    : Math.round(product.getPrice() * spec.saleMarkup()));   // 정가=판매가×markup → %OFF 노출
            ensureOptions(product, spec);
        }
        return byName;
    }

    /** 초기 이름(P01~P07)을 현재 상품명으로 변경 — 삭제·재생성이 아니라 rename이라 참조가 전부 유지된다. */
    private void renameLegacyProducts(Map<String, Product> byName) {
        LEGACY_RENAME.forEach((legacy, current) -> {
            Product product = byName.get(legacy);
            if (product == null || byName.containsKey(current)) {
                return;   // 이미 이행됐거나(없음) 같은 이름이 따로 있음(중복 rename 방지)
            }
            product.updateBasics(current, product.getPrice(), product.getOriginalPrice(), product.getDescription(),
                    product.getImageUrl(), product.getCategoryId(), product.getBrandId());
            byName.remove(legacy);
            byName.put(current, product);
            log.info("[demo-seed] 초기 상품명 이행 — {} → {} (id={} 유지)", legacy, current, product.getId());
        });
    }

    /** 카탈로그에 정의된 사이즈 중 <b>없는 것만</b> 추가한다(기존 옵션의 재고는 보존). */
    private void ensureOptions(Product product, DemoProduct spec) {
        for (String size : spec.sizes()) {
            boolean exists = product.getOptions().stream().anyMatch(o -> o.getSize().equals(size));
            if (!exists) {
                product.addOption(size, spec.stockPerSize());
            }
        }
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
        Product hoodie = products.get("오버핏 후디");
        if (hoodie != null) {
            wishlistService.add(buyer.getId(), hoodie.getId()); // 찜(카운터 일관 — 서비스 경유)
        }
        for (String name : List.of("에센셜 티셔츠", "와이드 슬랙스", "코튼 볼캡")) {
            Product p = products.get(name);
            if (p != null) {
                activityLogRepository.save(ActivityLog.view(buyer.getId(), p.getId()));
            }
        }
        log.info("[demo-seed] buyer 행동 신호 시드(찜 1 + 조회 3)");
    }
}
