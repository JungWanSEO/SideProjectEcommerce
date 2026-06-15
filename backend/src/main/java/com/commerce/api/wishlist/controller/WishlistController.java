package com.commerce.api.wishlist.controller;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.security.SecurityUtil;
import com.commerce.api.wishlist.dto.WishlistRequest;
import com.commerce.api.wishlist.dto.WishlistResponse;
import com.commerce.api.wishlist.service.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위시리스트(찜) API. 모두 <b>로그인 필요</b>(SecurityConfig의 공개·ADMIN 규칙에 안 걸려
 * {@code anyRequest().authenticated()}로 처리) — 회원 본인의 찜만 다룬다.
 *
 * <ul>
 *   <li>POST   /api/wishlist                 찜 추가 (바디 productId, 중복 409)
 *   <li>DELETE /api/wishlist/{productId}      찜 해제 (안 찜한 상품 404)
 *   <li>GET    /api/wishlist/me               내 찜 목록 (페이지, 최신순)
 *   <li>GET    /api/wishlist/me/product-ids   내가 찜한 상품 ID 전체 (FE 하트 채움용)
 * </ul>
 */
@Tag(name = "위시리스트(Wishlist)", description = "상품 찜 추가 / 해제 / 조회 API (로그인 필요)")
@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @Operation(summary = "찜 추가",
            description = "상품을 내 위시리스트에 담는다. 로그인 필요. 없는 상품이면 404, 이미 찜한 상품이면 409.")
    @PostMapping
    public ResponseEntity<ApiResponse<WishlistResponse>> add(
            @Valid @RequestBody WishlistRequest request) {
        WishlistResponse response = wishlistService.add(
                SecurityUtil.getCurrentMemberId(), request.productId());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("찜에 추가했습니다.", response));
    }

    @Operation(summary = "찜 해제",
            description = "상품을 내 위시리스트에서 뺀다. 로그인 필요. 찜하지 않은 상품이면 404.")
    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable Long productId) {
        wishlistService.remove(SecurityUtil.getCurrentMemberId(), productId);
        return ResponseEntity.ok(ApiResponse.<Void>success("찜을 해제했습니다.", null));
    }

    @Operation(summary = "내 찜 목록 조회",
            description = "내가 찜한 상품을 페이지로 조회한다(상품 정보 포함). 기본 정렬은 최신 찜 순(createdAt desc), 기본 크기 20.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<PageResponse<WishlistResponse>>> myWishlist(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Direction.DESC)
            Pageable pageable) {
        PageResponse<WishlistResponse> response =
                wishlistService.getMyWishlist(SecurityUtil.getCurrentMemberId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "내가 찜한 상품 ID 목록",
            description = "내가 찜한 상품의 ID 전체를 반환한다. FE가 상품 목록/상세에서 하트 채움 여부를 한 번에 판단하는 데 쓴다.")
    @GetMapping("/me/product-ids")
    public ResponseEntity<ApiResponse<List<Long>>> myProductIds() {
        List<Long> ids = wishlistService.getMyProductIds(SecurityUtil.getCurrentMemberId());
        return ResponseEntity.ok(ApiResponse.success(ids));
    }
}
