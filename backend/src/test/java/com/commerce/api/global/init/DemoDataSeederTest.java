package com.commerce.api.global.init;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.commerce.api.order.entity.Shipment;
import com.commerce.api.order.repository.OrderRepository;
import com.commerce.api.payment.entity.Payment;
import com.commerce.api.payment.repository.PaymentRepository;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.wishlist.service.WishlistService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 시드 검증 — <b>빈 DB → 라이브 데모 가능한 상태</b>(카탈로그·셀러 귀속·멀티셀러 주문)와 재기동 멱등.
 *
 * <p>시드 빈은 {@code app.demo-seed.enabled=true}일 때만 등록되므로(테스트 기본 OFF) 실제 빈들을 주입해 직접 생성한다.
 * 시드의 {@code @Transactional}은 프록시가 아니라 <b>테스트 트랜잭션</b>이 대신 제공한다(더티 체킹 동일).
 *
 * <p>핵심 불변식 둘: ① 카탈로그가 <b>코드에</b> 있어 어떤 DB에서도 재현된다(예전엔 오너 로컬 DB에만 존재)
 * ② 시드 주문의 셀러 귀속이 실제 체크아웃과 같은 경로(상품 → brandId → Brand.sellerId)라
 * {@code markPaid()}가 셀러별 shipment를 팬아웃한다 — 셀러 콘솔·셀러 알림 데모가 여기에 걸려 있다.
 */
@SpringBootTest
@Transactional
class DemoDataSeederTest {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private RecommendationRepository recommendationRepository;
    @Autowired private WishlistService wishlistService;
    @Autowired private PasswordEncoder passwordEncoder;

    private DemoDataSeeder seeder;

    /**
     * 시드가 보장해야 할 카탈로그 이름 — 단언을 <b>이 이름들로 한정</b>한다. 전체 스위트에선 다른 테스트가
     * 커밋한 상품이 같은 H2에 남아 있어 전역 카운트로 검증하면 실행 순서에 따라 깨진다(테스트 격리).
     */
    private static final List<String> CATALOG_NAMES = List.of(
            "코튼 볼캡", "오버핏 후디", "울 블렌드 자켓", "라이트 머플러", "레더 스니커즈", "에센셜 티셔츠",
            "와이드 슬랙스", "캐시미어 니트", "플리츠 스커트", "린넨 원피스", "미니 토트백", "라운드 선글라스");

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(categoryRepository, brandRepository, productRepository, memberRepository,
                orderRepository, sellerRepository, paymentRepository, activityLogRepository,
                recommendationRepository, wishlistService, passwordEncoder);
    }

    @Test
    @DisplayName("카탈로그 12종을 직접 만든다 — 상품·옵션·분류·세일가(새 DB로 배포해도 상점이 비지 않음)")
    void createsCatalogFromCode() {
        seeder.seed();

        List<Product> products = catalogProducts();
        assertThat(products).hasSize(12);
        assertThat(products).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(ProductStatus.ON_SALE);
            assertThat(p.getCategoryId()).isNotNull();          // PLP 카테고리 필터가 동작할 것
            assertThat(p.getBrandId()).isNotNull();             // 브랜드 → 셀러 귀속의 출발점
            assertThat(p.getOptions()).isNotEmpty();            // 옵션(SKU)이 있어야 장바구니·주문이 가능
            assertThat(p.getOptions()).allSatisfy(o -> assertThat(o.getStock()).isPositive());
        });
        // 세일 상품이 섞여 있어야 %OFF·SALE 필터·할인율 정렬 데모가 살아난다(정가 > 판매가).
        assertThat(products).anySatisfy(p -> assertThat(p.isOnSale()).isTrue());
        assertThat(categoryRepository.findAll()).extracting(Category::getName)
                .contains("아우터", "상의", "하의", "원피스", "신발", "액세서리");
    }

    @Test
    @DisplayName("초기 상품명(P01~P07)은 삭제가 아니라 rename — 상품 id가 유지돼 기존 주문·리뷰 참조가 산다")
    void renamesLegacyProductsKeepingId() {
        Product legacy = saveProduct("P02-Hoodie", 89_000L);
        Long legacyId = legacy.getId();

        seeder.seed();

        Product renamed = productRepository.findById(legacyId).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("오버핏 후디");
        assertThat(catalogProducts()).hasSize(12);                                 // 중복 생성 없음
        assertThat(productRepository.findAll()).noneSatisfy(p ->
                assertThat(p.getName()).isEqualTo("P02-Hoodie"));
        assertThat(renamed.getOptions()).extracting(ProductOption::getSize)
                .contains("M", "S", "L");                                          // 기존 옵션 보존 + 누락분 보강
    }

    @Test
    @DisplayName("브랜드가 셀러에 귀속되고 셀러 콘솔 로그인 계정이 생긴다")
    void assignsSellersToBrandsAndCreatesOperatorAccounts() {
        seeder.seed();

        Seller maison = sellerRepository.findByName("메종클레이").orElseThrow();
        Seller nordform = sellerRepository.findByName("노드폼컴퍼니").orElseThrow();
        assertThat(brandByName("Maison Clay").getSellerId()).isEqualTo(maison.getId());
        // 한 셀러가 브랜드 2개 운영(seller 1:N brand)
        assertThat(brandByName("Daily Form").getSellerId()).isEqualTo(nordform.getId());
        assertThat(brandByName("Nord Atelier").getSellerId()).isEqualTo(nordform.getId());

        Member operator = memberRepository.findByEmail("seller1@commerce.com").orElseThrow();
        assertThat(operator.getRole()).isEqualTo(Role.SELLER);
        assertThat(operator.getSellerId()).isEqualTo(maison.getId());   // 연결 없는 SELLER면 콘솔이 403으로 깨진다
    }

    @Test
    @DisplayName("데모 주문 항목에 셀러가 귀속되고, 멀티셀러 주문은 셀러별 shipment로 팬아웃된다")
    void attributesOrderItemsAndFansOutShipmentsPerSeller() {
        seeder.seed();

        Long maisonId = sellerRepository.findByName("메종클레이").orElseThrow().getId();
        Long nordformId = sellerRepository.findByName("노드폼컴퍼니").orElseThrow().getId();
        List<Order> orders = ordersOf("demo1@commerce.com");

        assertThat(orders).isNotEmpty();
        assertThat(orders).allSatisfy(order -> {
            assertThat(order.getOrderItems()).allSatisfy(item ->
                    assertThat(item.getSellerId()).isNotNull());   // 미귀속(null) 0 — 셀러 화면이 비지 않는다
            // demo1의 주문은 후디(메종클레이) + 슬랙스(노드폼) 조합 = 셀러 2곳 → shipment 2건
            assertThat(sellerIdsOf(order)).containsExactlyInAnyOrder(maisonId, nordformId);
            assertThat(order.getShipments()).hasSize(2);
            assertThat(order.getShipments().stream().map(Shipment::getSellerId))
                    .containsExactlyInAnyOrder(maisonId, nordformId);
        });
    }

    @Test
    @DisplayName("단일 셀러 주문은 shipment 1건 — 팬아웃은 셀러 수만큼만")
    void singleSellerOrderGetsOneShipment() {
        seeder.seed();

        List<Order> orders = ordersOf("demo3@commerce.com");   // 상의+상의 / 하의+액세서리 = 각각 한 셀러
        assertThat(orders).isNotEmpty();
        assertThat(orders).allSatisfy(order -> {
            assertThat(sellerIdsOf(order)).hasSize(1);
            assertThat(order.getShipments()).hasSize(1);
        });
    }

    @Test
    @DisplayName("재실행해도 상품·셀러·브랜드·주문이 중복 생성되지 않는다(멱등)")
    void isIdempotentAcrossRestarts() {
        seeder.seed();
        long sellersAfterFirst = sellerRepository.count();
        int productsAfterFirst = catalogProducts().size();
        List<Long> orderIdsAfterFirst = allDemoOrderIds();

        seeder.seed();

        assertThat(sellerRepository.count()).isEqualTo(sellersAfterFirst);
        assertThat(catalogProducts()).hasSize(productsAfterFirst);
        assertThat(brandRepository.findAll()).extracting(Brand::getName).doesNotHaveDuplicates();
        assertThat(catalogProducts()).extracting(Product::getName).doesNotHaveDuplicates();
        assertThat(allDemoOrderIds()).isEqualTo(orderIdsAfterFirst);   // 재생성도 없음(같은 행 유지)
    }

    @Test
    @DisplayName("셀러 귀속 이전에 시드된 미귀속 주문은 삭제 후 귀속본으로 재생성된다")
    void replacesUnattributedSeedOrders() {
        Member demo1 = saveMember("demo1@commerce.com", Role.USER);
        Long staleOrderId = saveUnattributedOrder(demo1).getId();

        seeder.seed();

        assertThat(orderRepository.findById(staleOrderId)).isEmpty();   // 옛 주문은 사라지고
        List<Order> recreated = ordersOf("demo1@commerce.com");
        assertThat(recreated).isNotEmpty();
        assertThat(recreated).allSatisfy(order ->
                assertThat(order.getOrderItems()).allSatisfy(item ->
                        assertThat(item.getSellerId()).isNotNull()));   // 귀속본으로 다시 생성된다
    }

    @Test
    @DisplayName("실제 결제가 물린 주문은 건드리지 않되, 그 존재가 시드 주문 재귀속을 막지도 않는다(주문 단위 판정)")
    void keepsRealOrdersButStillAttributesSeedOrders() {
        Member demo1 = saveMember("demo1@commerce.com", Role.USER);
        Order realOrder = saveUnattributedOrder(demo1);   // 사람이 만든 주문(결제행 있음)
        paymentRepository.save(Payment.ready(realOrder.getId(), 1000L, "MOCK_CARD", "TOSS", "key-demo-safety"));

        seeder.seed();

        // 결제행이 있으면 "시드가 만든 주문"이 아니다 → 그대로 보존(정산·대사가 물려 있을 수 있다)
        Order preserved = orderRepository.findById(realOrder.getId()).orElseThrow();
        assertThat(preserved.getOrderItems()).allSatisfy(item -> assertThat(item.getSellerId()).isNull());
        // 그러면서도 시드 주문은 새로 생성돼 귀속된다(예전엔 이 한 건 때문에 전체가 미귀속으로 남았다)
        List<Order> seeded = ordersOf("demo1@commerce.com").stream()
                .filter(o -> !o.getId().equals(realOrder.getId()))
                .toList();
        assertThat(seeded).isNotEmpty();
        assertThat(seeded).allSatisfy(order ->
                assertThat(order.getOrderItems()).allSatisfy(item ->
                        assertThat(item.getSellerId()).isNotNull()));
    }

    // === 헬퍼 ===================================================================

    private Product saveProduct(String name, long price) {
        Product product = Product.builder()
                .name(name).price(price).description("d").imageUrl(null)
                .status(ProductStatus.ON_SALE)
                .build();
        product.addOption("M", 10);
        return productRepository.save(product);
    }

    /** 시드 카탈로그에 속한 상품만(다른 테스트가 커밋해 둔 상품은 제외). */
    private List<Product> catalogProducts() {
        return productRepository.findAll().stream()
                .filter(p -> CATALOG_NAMES.contains(p.getName()))
                .toList();
    }

    private Brand brandByName(String name) {
        return brandRepository.findAll().stream().filter(b -> b.getName().equals(name)).findFirst().orElseThrow();
    }

    private List<Order> ordersOf(String email) {
        Member member = memberRepository.findByEmail(email).orElseThrow();
        return orderRepository.findByMemberIdIn(List.of(member.getId()));
    }

    private Set<Long> sellerIdsOf(Order order) {
        return order.getOrderItems().stream().map(OrderItem::getSellerId).collect(Collectors.toSet());
    }

    private List<Long> allDemoOrderIds() {
        List<Long> memberIds = List.of("demo1@commerce.com", "demo2@commerce.com", "demo3@commerce.com").stream()
                .map(email -> memberRepository.findByEmail(email).map(Member::getId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return orderRepository.findByMemberIdIn(memberIds).stream().map(Order::getId).sorted().toList();
    }

    private Member saveMember(String email, Role role) {
        return memberRepository.save(Member.builder()
                .email(email).password(passwordEncoder.encode("demopass1234"))
                .nickname("데모").role(role).build());
    }

    /** 셀러 귀속 이전 시드가 만들던 모양 그대로(항목 sellerId=null) PAID 주문 1건. 상품은 초기 이름으로 미리 심는다. */
    private Order saveUnattributedOrder(Member member) {
        Order order = Order.create(member.getId());
        for (String legacyName : List.of("P02-Hoodie", "P07-Pants")) {
            Product product = saveProduct(legacyName, 50_000L);
            order.addItem(OrderItem.builder()
                    .productId(product.getId())
                    .optionId(product.getOptions().get(0).getId())
                    .brandId(null)
                    .sellerId(null)
                    .productName(product.getName())
                    .size(product.getOptions().get(0).getSize())
                    .orderPrice(product.getPrice())
                    .quantity(1)
                    .build());
        }
        order.markPaid();
        return orderRepository.save(order);
    }
}
