package com.commerce.api.global.init;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.activity.repository.ActivityLogRepository;
import com.commerce.api.brand.entity.Brand;
import com.commerce.api.brand.repository.BrandRepository;
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
import com.commerce.api.product.entity.ProductStatus;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.recommendation.repository.RecommendationRepository;
import com.commerce.api.seller.entity.Seller;
import com.commerce.api.seller.repository.SellerRepository;
import com.commerce.api.wishlist.service.WishlistService;
import java.util.List;
import java.util.Map;
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
 * 데모 시드의 <b>셀러 귀속</b> 검증.
 *
 * <p>시드 빈은 {@code @Profile("dev")}라 테스트 컨텍스트에 등록되지 않는다 → 실제 빈들을 주입해 직접 생성한다.
 * 시드의 {@code @Transactional}은 프록시 경유가 아니라 <b>테스트 트랜잭션</b>이 대신 제공한다(더티 체킹 동일).
 *
 * <p>핵심 불변식: 시드 주문의 셀러 귀속은 실제 체크아웃과 <b>같은 경로</b>(상품 → brandId → Brand.sellerId)를 따르고,
 * 그 결과 {@code markPaid()}가 셀러별 shipment를 팬아웃한다 — 셀러 콘솔·셀러 알림 데모가 여기에 걸려 있다.
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

    /** 데모 주문이 참조하는 카탈로그(P01~P07 중 주문에 쓰이는 것) — 시드는 "기존 상품"에 분류/셀러를 얹는다. */
    private static final Map<String, Long> CATALOG = Map.of(
            "P01-Cap", 19_000L,
            "P02-Hoodie", 89_000L,
            "P04-Socks", 7_000L,
            "P06-Tee", 29_000L,
            "P07-Pants", 59_000L);

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(categoryRepository, brandRepository, productRepository, memberRepository,
                orderRepository, sellerRepository, paymentRepository, activityLogRepository,
                recommendationRepository, wishlistService, passwordEncoder);
        CATALOG.forEach((name, price) -> {
            Product product = Product.builder()
                    .name(name).price(price).description("데모").imageUrl("http://img/" + name)
                    .status(ProductStatus.ON_SALE)
                    .build();
            product.addOption("M", 100);
            productRepository.save(product);
        });
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
            // demo1의 주문은 후디(메종클레이) + 팬츠(노드폼) 조합 = 셀러 2곳 → shipment 2건
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
    @DisplayName("재실행해도 셀러·브랜드·주문이 중복 생성되지 않는다(멱등)")
    void isIdempotentAcrossRestarts() {
        seeder.seed();
        long sellersAfterFirst = sellerRepository.count();
        List<Long> orderIdsAfterFirst = allDemoOrderIds();

        seeder.seed();

        assertThat(sellerRepository.count()).isEqualTo(sellersAfterFirst);
        assertThat(brandRepository.findAll()).extracting(Brand::getName).doesNotHaveDuplicates();
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
    @DisplayName("실제 결제가 물린 주문이 섞여 있으면 아무것도 지우지 않는다(안전장치)")
    void neverTouchesOrdersWithRealPayment() {
        Member demo1 = saveMember("demo1@commerce.com", Role.USER);
        Order realOrder = saveUnattributedOrder(demo1);
        paymentRepository.save(Payment.ready(realOrder.getId(), 1000L, "MOCK_CARD", "TOSS", "key-demo-safety"));

        seeder.seed();

        // 결제행이 있으면 "시드가 만든 주문"이 아니다 → 전체 보존(부분 삭제로 데모가 반쪽 나는 것도 방지)
        assertThat(orderRepository.findById(realOrder.getId())).isPresent();
        assertThat(allDemoOrderIds()).containsExactly(realOrder.getId());
    }

    // === 헬퍼 ===================================================================

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

    /** 셀러 귀속 이전 시드가 만들던 모양 그대로(항목 sellerId=null) PAID 주문 1건. */
    private Order saveUnattributedOrder(Member member) {
        Order order = Order.create(member.getId());
        for (String name : List.of("P02-Hoodie", "P07-Pants")) {
            Product product = productRepository.findAll().stream()
                    .filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
            order.addItem(OrderItem.builder()
                    .productId(product.getId())
                    .optionId(product.getOptions().get(0).getId())
                    .brandId(product.getBrandId())
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
