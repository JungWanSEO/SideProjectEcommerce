package com.commerce.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import com.commerce.api.order.entity.Order;
import com.commerce.api.order.entity.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * OrderRepository 슬라이스 테스트 (@DataJpaTest).
 * flush/clear로 영속성 컨텍스트를 비운 뒤 재조회하여, 애그리거트(주문+항목)가
 * cascade로 DB에 실제 저장되는지 검증한다.
 */
@DataJpaTest
// QuerydslConfig: @DataJpaTest가 모든 리포지토리를 로드하므로 ProductRepository(QueryDSL)의
// JPAQueryFactory 빈이 슬라이스에도 필요하다.
@Import({JpaConfig.class, QuerydslConfig.class})
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("주문 저장 시 항목이 cascade로 함께 저장되고, 총액·생성일시가 채워진다")
    void save_cascadesItemsAndTotal() {
        // given
        Order order = Order.create(100L);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(3).build());
        order.addItem(OrderItem.builder()
                .productId(2L).optionId(21L).productName("청바지").size("L")
                .orderPrice(20000L).quantity(1).build());

        // when
        Long savedId = orderRepository.save(order).getId();
        em.flush();
        em.clear();   // 영속성 컨텍스트 비움 → 아래 조회는 DB에서 다시 읽어옴

        // then
        Order found = orderRepository.findById(savedId).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getTotalPrice()).isEqualTo(50000L);   // 10000*3 + 20000*1
        assertThat(found.getOrderItems()).hasSize(2);
    }

    @Test
    @DisplayName("findById - 없는 id면 빈 Optional")
    void findById_notFound() {
        assertThat(orderRepository.findById(999L)).isEmpty();
    }

    private Order orderFor(Long memberId) {
        Order order = Order.create(memberId);
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(11L).productName("반팔티셔츠").size("M")
                .orderPrice(10000L).quantity(1).build());
        return order;
    }

    @Test
    @DisplayName("findByMemberId - 해당 회원의 주문만 조회되고 다른 회원 주문은 제외된다")
    void findByMemberId_onlyOwnOrders() {
        orderRepository.save(orderFor(100L));
        orderRepository.save(orderFor(100L));
        orderRepository.save(orderFor(200L));   // 다른 회원
        em.flush();
        em.clear();

        Page<Order> page = orderRepository.findByMemberId(
                100L, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(Order::getMemberId)
                .containsOnly(100L);
    }

    @Test
    @DisplayName("findByMemberId - 페이지 크기만큼 담고 totalPages/hasNext로 다음을 알린다")
    void findByMemberId_paging() {
        for (int i = 0; i < 3; i++) {
            orderRepository.save(orderFor(100L));
        }
        em.flush();
        em.clear();

        Page<Order> first = orderRepository.findByMemberId(100L, PageRequest.of(0, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
    }

    // === 어드민 주문 검색 (QueryDSL search) ===

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    /** 수령인·셀러·금액을 지정한 주문 저장. */
    private Order searchable(Long memberId, String recipient, Long sellerId, long price) {
        Order order = Order.create(memberId);
        order.ship(com.commerce.api.order.entity.ShippingInfo.of(
                recipient, "010-0000-0000", "06236", "서울", "1층", null));
        order.addItem(OrderItem.builder()
                .productId(1L).optionId(10L).sellerId(sellerId).productName("상품").size("M")
                .orderPrice(price).quantity(1).build());
        return orderRepository.save(order);
    }

    private com.commerce.api.order.dto.OrderSearchCondition cond(
            String keyword, Long memberId, java.time.LocalDate from, java.time.LocalDate to,
            Long minAmount, Long maxAmount, Long sellerId) {
        return new com.commerce.api.order.dto.OrderSearchCondition(
                keyword, memberId, null, from, to, minAmount, maxAmount, sellerId);
    }

    @Test
    @DisplayName("search - 수령인명 부분일치, 그리고 키워드가 숫자면 주문번호(id)도 함께 매칭")
    void search_byRecipientOrOrderNumber() {
        Order target = searchable(1L, "홍길동", null, 30000L);
        searchable(2L, "김철수", null, 50000L);

        Page<Order> byName = orderRepository.search(cond("길동", null, null, null, null, null, null), FIRST_PAGE);
        Page<Order> byId = orderRepository.search(
                cond(String.valueOf(target.getId()), null, null, null, null, null, null), FIRST_PAGE);

        assertThat(byName.getContent()).extracting(o -> o.getShippingInfo().getRecipient())
                .containsExactly("홍길동");
        assertThat(byId.getContent()).extracting(Order::getId).contains(target.getId());
    }

    @Test
    @DisplayName("search - 회원·금액대로 거른다")
    void search_byMemberAndAmount() {
        searchable(1L, "홍길동", null, 30000L);
        searchable(1L, "홍길동", null, 90000L);   // 금액대 밖
        searchable(2L, "김철수", null, 30000L);   // 다른 회원

        assertThat(orderRepository.search(cond(null, 1L, null, null, null, null, null), FIRST_PAGE)
                .getTotalElements()).isEqualTo(2);
        assertThat(orderRepository.search(cond(null, null, null, null, 20000L, 50000L, null), FIRST_PAGE)
                .getContent())
                .allMatch(o -> o.getTotalPrice() >= 20000 && o.getTotalPrice() <= 50000);
    }

    @Test
    @DisplayName("search - 기간 to는 그날 하루를 포함한다(to 당일 23:59 주문도 잡힘)")
    void search_dateWindowInclusive() {
        Order o = searchable(1L, "홍길동", null, 30000L);
        em.getEntityManager().createNativeQuery("update orders set created_at = :t where id = :id")
                .setParameter("t", java.time.LocalDate.of(2026, 7, 10).atTime(23, 59))
                .setParameter("id", o.getId())
                .executeUpdate();
        em.clear();

        Page<Order> inWindow = orderRepository.search(
                cond(null, null, java.time.LocalDate.of(2026, 7, 10), java.time.LocalDate.of(2026, 7, 10),
                        null, null, null), FIRST_PAGE);
        Page<Order> afterWindow = orderRepository.search(
                cond(null, null, java.time.LocalDate.of(2026, 7, 11), null, null, null, null), FIRST_PAGE);

        assertThat(inWindow.getContent()).extracting(Order::getId).contains(o.getId());   // to 당일 포함
        assertThat(afterWindow.getContent()).extracting(Order::getId).doesNotContain(o.getId());
    }

    @Test
    @DisplayName("search - sellerId: 그 셀러 상품이 든 주문만(EXISTS·중복 없음)")
    void search_bySeller() {
        searchable(1L, "홍길동", 7L, 30000L);
        searchable(2L, "김철수", 9L, 30000L);

        Page<Order> page = orderRepository.search(cond(null, null, null, null, null, null, 7L), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getOrderItems().get(0).getSellerId()).isEqualTo(7L);
    }
}