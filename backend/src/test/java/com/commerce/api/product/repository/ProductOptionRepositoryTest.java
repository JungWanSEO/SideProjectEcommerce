package com.commerce.api.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.entity.ProductOption;
import com.commerce.api.product.entity.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * ProductOptionRepository 슬라이스 테스트(@DataJpaTest) — 재고 예약의 <b>원자적 조건부 UPDATE</b>를 실제 H2에서
 * 경계까지 검증한다. 오버셀 방지의 핵심 SQL 술어(WHERE stock-reserved>=q 등)가 회귀하면 여기서 잡힌다.
 *
 * <p>@Modifying UPDATE는 영속성 컨텍스트를 우회하므로, 변경 후엔 em.clear()로 컨텍스트를 비우고 다시 읽어 확인한다.
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})
class ProductOptionRepositoryTest {

    @Autowired private ProductOptionRepository productOptionRepository;
    @Autowired private com.commerce.api.product.repository.ProductRepository productRepository;
    @Autowired private TestEntityManager em;

    /** 재고 stock짜리 옵션 1개를 저장하고 옵션 id를 반환. */
    private Long saveOption(int stock) {
        Product p = Product.builder()
                .name("t").price(1000L).description("d").status(ProductStatus.ON_SALE).build();
        p.addOption(ProductOption.create("M", stock));
        productRepository.save(p);
        em.flush();
        em.clear();
        return p.getOptions().get(0).getId();
    }

    private ProductOption reload(Long id) {
        em.clear();
        return productOptionRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("reserve - 가용재고가 충분할 때만 성공(경계 available==qty), 부족하면 0행·불변")
    void reserve_boundary() {
        Long id = saveOption(10);

        assertThat(productOptionRepository.reserve(id, 10)).isEqualTo(1);   // available 10 == 10 → 성공
        assertThat(reload(id).getReserved()).isEqualTo(10);

        assertThat(productOptionRepository.reserve(id, 1)).isZero();        // available 0 < 1 → 품절
        assertThat(reload(id).getReserved()).isEqualTo(10);                 // reserved 불변
    }

    @Test
    @DisplayName("consume - 예약을 실재고 차감으로 전환(stock↓·reserved↓), 예약 부족이면 0행")
    void consume() {
        Long id = saveOption(10);
        productOptionRepository.reserve(id, 4);

        assertThat(productOptionRepository.consume(id, 4)).isEqualTo(1);
        ProductOption o = reload(id);
        assertThat(o.getStock()).isEqualTo(6);
        assertThat(o.getReserved()).isZero();
        assertThat(o.available()).isEqualTo(6);

        assertThat(productOptionRepository.consume(id, 1)).isZero();        // reserved 0 → 가드로 0행(무변)
        assertThat(reload(id).getStock()).isEqualTo(6);
    }

    @Test
    @DisplayName("release - 예약분을 되돌리고(reserved↓) 이미 0이면 멱등(0행)")
    void release_idempotent() {
        Long id = saveOption(10);
        productOptionRepository.reserve(id, 3);

        assertThat(productOptionRepository.release(id, 3)).isEqualTo(1);
        assertThat(reload(id).getReserved()).isZero();

        assertThat(productOptionRepository.release(id, 1)).isZero();        // 이미 0 → no-op
        assertThat(reload(id).getReserved()).isZero();
    }

    @Test
    @DisplayName("restore - 결제 완료 취소 시 실재고를 되돌린다(reserved 무관)")
    void restore() {
        Long id = saveOption(5);

        assertThat(productOptionRepository.restore(id, 2)).isEqualTo(1);
        assertThat(reload(id).getStock()).isEqualTo(7);
    }
}
