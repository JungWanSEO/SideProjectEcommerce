package com.commerce.api.product.repository;

import com.commerce.api.product.entity.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 옵션 재고의 <b>원자적 조건부 UPDATE</b> 전용 리포지토리 — 재고 예약/소진/해제(#2).
 *
 * <p>예약·차감을 "읽고-판단하고-쓰기"(앱 락)로 하면 경쟁 구간이 생긴다. 대신 조건을 WHERE에 넣은
 * 단일 UPDATE로 DB가 행 락으로 직렬화하게 한다 — 선착순 쿠폰 {@code incrementIssuedCount}와 동형.
 * 영향 행 수(0/1)로 성공·실패(품절)를 판정한다. 이 경로는 {@code @Version} 낙관락에 의존하지 않는다.
 *
 * <p>모두 {@code @Modifying}이라 <b>같은 트랜잭션에서 옵션 엔티티를 다시 읽지 않는다</b>(영속성 컨텍스트
 * 는 이 UPDATE를 모른다). {@code clearAutomatically}는 안 쓴다 — 진행 중인 주문 애그리거트를 detach시키기 때문.
 *
 * <p><b>{@code version = version + 1}을 함께 올린다</b>: 이 벌크 UPDATE는 @Version을 자동 증가시키지 않으므로,
 * 그냥 두면 관리자 옵션 수정(엔티티 dirty-checking flush)이 낡은 version으로 stock을 덮어써 여기서 바꾼 값을
 * 잃는다(lost update). 수동으로 version을 올려 낙관락이 원자 경로의 변경을 감지하게 한다.
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    /** 예약: 가용재고(stock−reserved)가 충분할 때만 reserved 증가. 반환 1=예약 성공, 0=품절. */
    @Modifying
    @Query("update ProductOption o set o.reserved = o.reserved + :qty, o.version = o.version + 1 "
            + "where o.id = :optionId and o.stock - o.reserved >= :qty")
    int reserve(@Param("optionId") Long optionId, @Param("qty") int qty);

    /**
     * 소진(결제 확정): 예약을 실재고 차감으로 전환(stock↓·reserved↓). 반환 1=성공, 0=불변식 위반(호출자가 롤백).
     * {@code stock >= qty} 가드로 (관리자가 예약분 아래로 재고를 내린 경우 등) 음수 재고=오버셀을 막는다.
     */
    @Modifying
    @Query("update ProductOption o set o.stock = o.stock - :qty, o.reserved = o.reserved - :qty, "
            + "o.version = o.version + 1 "
            + "where o.id = :optionId and o.reserved >= :qty and o.stock >= :qty")
    int consume(@Param("optionId") Long optionId, @Param("qty") int qty);

    /** 해제(만료·취소): 예약분을 되돌린다(reserved↓, stock 불변). 반환 1=해제됨(멱등: 이미 없으면 0). */
    @Modifying
    @Query("update ProductOption o set o.reserved = o.reserved - :qty, o.version = o.version + 1 "
            + "where o.id = :optionId and o.reserved >= :qty")
    int release(@Param("optionId") Long optionId, @Param("qty") int qty);

    /** 실재고 복원(결제 완료 주문 취소): 이미 소진된 재고를 되돌린다(reserved 무관). */
    @Modifying
    @Query("update ProductOption o set o.stock = o.stock + :qty, o.version = o.version + 1 "
            + "where o.id = :optionId")
    int restore(@Param("optionId") Long optionId, @Param("qty") int qty);
}
