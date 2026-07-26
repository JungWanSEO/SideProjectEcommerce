package com.commerce.api.cart.entity;

import com.commerce.api.global.common.BaseEntity;
import com.commerce.api.global.exception.BusinessException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 장바구니 (애그리거트 루트). <b>회원 장바구니</b>(회원당 하나) 또는 <b>게스트 장바구니</b>(비로그인·쿠키 토큰, #7).
 *
 * - 소유는 {@code memberId}(로그인) 또는 {@code cartToken}(게스트) — <b>정확히 하나만</b> 설정된다.
 * - 회원/게스트 모두 항목(CartItem)은 애그리거트 내부 → @OneToMany. order와 달리 가격/이름을 스냅샷하지 않는다.
 * - 로그인 시 게스트 카트 항목이 회원 카트로 병합된다(CartService.mergeGuestIntoMember).
 */
@Getter
@Entity
@Table(name = "cart")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 회원 장바구니면 회원 ID(회원당 1개·unique). 게스트 장바구니면 null. */
    @Column(unique = true)
    private Long memberId;

    /** 게스트 장바구니 토큰(쿠키 값·unique·추측불가 UUID, #7). 회원 장바구니면 null. */
    @Column(name = "cart_token", unique = true, length = 80)
    private String cartToken;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> cartItems = new ArrayList<>();

    private Cart(Long memberId, String cartToken) {
        this.memberId = memberId;
        this.cartToken = cartToken;
    }

    /** 회원 장바구니 생성. */
    public static Cart create(Long memberId) {
        return new Cart(memberId, null);
    }

    /** 게스트 장바구니 생성(#7) — 비로그인 사용자가 담을 때. 토큰은 서버가 발급한 추측불가 UUID. */
    public static Cart createForGuest(String cartToken) {
        return new Cart(null, cartToken);
    }

    /**
     * 옵션(사이즈) 담기: 같은 옵션이 이미 있으면 수량 증가, 아니면 새 항목 추가.
     * 식별 단위가 optionId라 같은 상품의 다른 사이즈는 별개 항목으로 쌓인다.
     */
    public void addItem(Long productId, Long optionId, int quantity) {
        for (CartItem item : cartItems) {
            if (item.getOptionId().equals(optionId)) {
                item.addQuantity(quantity);
                return;
            }
        }
        CartItem newItem = CartItem.builder()
                .productId(productId).optionId(optionId).quantity(quantity).build();
        cartItems.add(newItem);
        newItem.assignCart(this);
    }

    /**
     * 항목 수량 변경 (옵션 단위, 절대값으로 설정). 해당 옵션 항목이 없으면 404.
     * addItem(가산)·removeItem(삭제)과 달리 기존 항목의 수량을 덮어쓴다 — 수량 스테퍼용.
     */
    public void updateItemQuantity(Long optionId, int quantity) {
        CartItem item = cartItems.stream()
                .filter(i -> i.getOptionId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "장바구니에 해당 옵션 항목이 없습니다. (optionId: " + optionId + ")"));
        item.changeQuantity(quantity);
    }

    /** 항목 제거 (옵션 단위, orphanRemoval로 DB에서도 삭제됨) */
    public void removeItem(Long optionId) {
        cartItems.removeIf(item -> item.getOptionId().equals(optionId));
    }

    /** 장바구니 비우기 (체크아웃 후). orphanRemoval로 DB에서도 삭제됨. */
    public void clearItems() {
        cartItems.clear();
    }
}