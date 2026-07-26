package com.commerce.api.cart.controller;

import com.commerce.api.cart.dto.CartItemAddRequest;
import com.commerce.api.cart.dto.CartItemUpdateRequest;
import com.commerce.api.cart.dto.CartResponse;
import com.commerce.api.cart.service.CartCookieManager;
import com.commerce.api.cart.service.CartOwner;
import com.commerce.api.cart.service.CartService;
import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.global.security.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장바구니 API — <b>로그인 회원 + 비로그인 게스트</b> 공통(#7). 소유는 서비스가 판별한다:
 * 로그인이면 SecurityContext의 memberId, 비로그인이면 {@code cart_token} 쿠키(없으면 담기 시 서버가 발급).
 *
 * - POST   /api/carts/items                 담기 (게스트면 토큰 쿠키 발급/재사용)
 * - GET    /api/carts                        조회
 * - PUT    /api/carts/items/{optionId}      수량 변경 (절대값)
 * - DELETE /api/carts/items/{optionId}      항목 제거
 */
@Tag(name = "장바구니(Cart)", description = "담기 / 조회 / 항목 제거 API (로그인·게스트 공통)")
@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartCookieManager cartCookieManager;

    @Operation(summary = "장바구니 담기",
            description = "옵션(사이즈)을 담는다. 같은 옵션이면 수량을 더한다. 비로그인 게스트도 담을 수 있고, "
                    + "토큰이 없으면 서버가 게스트 장바구니 토큰(httpOnly 쿠키)을 발급한다.")
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @Valid @RequestBody CartItemAddRequest request,
            @CookieValue(name = CartCookieManager.CART_TOKEN_COOKIE, required = false) String cartToken) {
        Long memberId = SecurityUtil.getCurrentMemberIdOrNull();
        if (memberId != null) {
            CartResponse response = cartService.addItem(CartOwner.member(memberId), request);
            return ResponseEntity.ok(ApiResponse.success("장바구니에 담았습니다.", response));
        }
        // 게스트: 토큰이 없으면 새로 발급해 쿠키로 내려준다(이미 있으면 재사용 — 쿠키 재설정 없음).
        boolean issue = !StringUtils.hasText(cartToken);
        String token = issue ? cartCookieManager.newToken() : cartToken;
        CartResponse response = cartService.addItem(CartOwner.guest(token), request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (issue) {
            builder.header(HttpHeaders.SET_COOKIE, cartCookieManager.issue(token).toString());
        }
        return builder.body(ApiResponse.success("장바구니에 담았습니다.", response));
    }

    @Operation(summary = "장바구니 조회", description = "로그인 사용자 또는 게스트(쿠키)의 장바구니를 현재 상품 정보로 채워 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(
            @CookieValue(name = CartCookieManager.CART_TOKEN_COOKIE, required = false) String cartToken) {
        CartOwner owner = resolveOwner(cartToken);
        if (owner == null) {
            return ResponseEntity.ok(ApiResponse.success(new CartResponse(null, List.of(), 0)));   // 게스트·토큰 없음 = 빈 카트
        }
        return ResponseEntity.ok(ApiResponse.success(cartService.getCart(owner)));
    }

    @Operation(summary = "장바구니 항목 수량 변경",
            description = "특정 옵션(사이즈) 항목의 수량을 절대값으로 변경한다(더하지 않고 덮어쓴다). 항목이 없으면 404.")
    @PutMapping("/items/{optionId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            @PathVariable Long optionId,
            @Valid @RequestBody CartItemUpdateRequest request,
            @CookieValue(name = CartCookieManager.CART_TOKEN_COOKIE, required = false) String cartToken) {
        return ResponseEntity.ok(ApiResponse.success(
                "수량을 변경했습니다.", cartService.changeQuantity(requireOwner(cartToken), optionId, request)));
    }

    @Operation(summary = "장바구니 항목 제거", description = "장바구니에서 특정 옵션(사이즈) 항목을 제거한다.")
    @DeleteMapping("/items/{optionId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(
            @PathVariable Long optionId,
            @CookieValue(name = CartCookieManager.CART_TOKEN_COOKIE, required = false) String cartToken) {
        return ResponseEntity.ok(ApiResponse.success(
                "항목을 제거했습니다.", cartService.removeItem(requireOwner(cartToken), optionId)));
    }

    /** 로그인이면 회원, 게스트+토큰 있으면 게스트, 아니면 null(장바구니 컨텍스트 없음). */
    private CartOwner resolveOwner(String cartToken) {
        Long memberId = SecurityUtil.getCurrentMemberIdOrNull();
        if (memberId != null) {
            return CartOwner.member(memberId);
        }
        return StringUtils.hasText(cartToken) ? CartOwner.guest(cartToken) : null;
    }

    /** 수량변경·제거처럼 기존 장바구니가 있어야 하는 경우 — 컨텍스트 없으면 404(회원 no-cart와 동일). */
    private CartOwner requireOwner(String cartToken) {
        CartOwner owner = resolveOwner(cartToken);
        if (owner == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "장바구니가 없습니다.");
        }
        return owner;
    }
}
