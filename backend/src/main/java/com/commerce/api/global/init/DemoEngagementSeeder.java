package com.commerce.api.global.init;

import com.commerce.api.member.entity.Member;
import com.commerce.api.product.entity.Product;
import com.commerce.api.product.repository.ProductRepository;
import com.commerce.api.review.entity.Review;
import com.commerce.api.review.repository.ReviewRepository;
import com.commerce.api.wishlist.repository.WishlistRepository;
import com.commerce.api.wishlist.service.WishlistService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 데모 <b>참여 신호</b> 시드(dev 전용) — 리뷰·평점·찜. 카탈로그만 있으면 목록이 "가격순"밖에 못 보여주는데,
 * 이 신호가 있어야 <b>평점순·인기순 정렬, 별점 분포 막대, 사진리뷰 필터, 리뷰 목록</b>이 데모에서 실제로 동작한다.
 *
 * <p><b>결정적</b>(난수 없음)이라 같은 카탈로그면 항상 같은 결과가 나오고, 이미 리뷰가 있는 상품·이미 찜한 조합은
 * 건너뛰어 재기동해도 누적되지 않는다.
 *
 * <p>⚠️ 리뷰는 서비스가 아니라 리포지토리로 직접 넣는다 — 실제 작성 경로는 "구매자만"(`hasActivePurchase`)을
 * 요구하는데, 데모 회원 몇 명의 주문만으로는 카탈로그 전체에 평점을 깔 수 없기 때문이다. 데모 표시용 데이터라
 * 자격 검증을 우회하는 것이고, 운영 경로(ReviewService)는 그대로 자격을 강제한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
class DemoEngagementSeeder {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final WishlistRepository wishlistRepository;
    private final WishlistService wishlistService;

    /** 평점 사이클 — 4~5점 위주에 3점·2점을 섞어 별점 분포 막대가 한 칸으로 몰리지 않게 한다. */
    private static final int[] RATINGS = {5, 4, 5, 3, 4, 5, 2, 4, 5, 3};

    private static final String[] COMMENTS = {
            "사이즈 딱 맞고 원단이 생각보다 두툼해요. 재구매 의사 있습니다.",
            "색감이 사진이랑 거의 같아요. 배송도 빨랐습니다.",
            "무난하게 입기 좋아요. 다만 세탁 후 조금 줄어드는 느낌.",
            "가격 대비 만족스럽습니다. 데일리로 자주 손이 가네요.",
            "마감이 아쉬워요. 실밥 정리가 덜 된 부분이 있었습니다.",
            "핏이 예쁘게 떨어져요. 키 170에 M 사이즈 적당합니다.",
            "생각보다 얇아서 간절기용으로 좋을 것 같아요.",
            "포장 깔끔하고 상품 상태도 좋았습니다."};

    /** 사진리뷰용 이미지(사진리뷰 필터 데모) — FE가 쓰는 로컬 일러스트 경로를 그대로 재사용한다. */
    private static final String[] REVIEW_IMAGES = {
            "/products/tee.svg", "/products/hoodie.svg", "/products/sneaker.svg", "/products/bag.svg"};

    /**
     * 리뷰·찜을 심는다. 상품은 이름 정렬 등 <b>안정된 순서</b>로 받아야 결과가 결정적이다.
     *
     * @param products 대상 상품(정렬된 목록)
     * @param members  리뷰어·찜 주체가 될 데모 회원(2명 이상 권장)
     */
    void seed(List<Product> products, List<Member> members) {
        if (products.isEmpty() || members.isEmpty()) {
            return;
        }
        int reviews = seedReviews(products, members);
        int wishes = seedWishlists(products, members);
        if (reviews > 0 || wishes > 0) {
            log.info("[demo-seed] 참여 신호 시드 — 리뷰 {}건 · 찜 {}건", reviews, wishes);
        }
    }

    /** 두 상품 중 하나꼴로 리뷰 1~3건. 상품마다 평점이 달라야 평점순 정렬·분포가 의미를 갖는다. */
    private int seedReviews(List<Product> products, List<Member> members) {
        int created = 0;
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            if (i % 2 == 1 || hasReview(product)) {
                continue;   // 리뷰 없는 상품도 남겨 둔다(빈 상태 UI도 데모 대상)
            }
            int count = 1 + (i % 3);   // 1~3건
            for (int n = 0; n < count; n++) {
                int rating = RATINGS[(i + n) % RATINGS.length];
                Member reviewer = members.get((i + n) % members.size());
                reviewRepository.save(Review.builder()
                        .memberId(reviewer.getId())
                        .productId(product.getId())
                        .rating(rating)
                        .content(COMMENTS[(i + n) % COMMENTS.length])
                        .imageUrl((i + n) % 3 == 0 ? REVIEW_IMAGES[(i + n) % REVIEW_IMAGES.length] : null)
                        .build());
                productRepository.incrementRating(product.getId(), rating);   // 비정규화 카운터(원자 UPDATE)
                created++;
            }
        }
        return created;
    }

    /** 회원마다 서로 다른 상품 5개씩 찜 — 상품별 찜 수가 갈려야 "인기순" 정렬이 눈에 보인다. */
    private int seedWishlists(List<Product> products, List<Member> members) {
        int created = 0;
        for (int m = 0; m < members.size(); m++) {
            Member member = members.get(m);
            for (int n = 0; n < 5; n++) {
                Product product = products.get((m * 3 + n * 2) % products.size());
                if (wishlistRepository.existsByMemberIdAndProductId(member.getId(), product.getId())) {
                    continue;
                }
                wishlistService.add(member.getId(), product.getId());   // 카운터 일관 — 서비스 경유
                created++;
            }
        }
        return created;
    }

    private boolean hasReview(Product product) {
        return reviewRepository.findByProductId(product.getId(), PageRequest.of(0, 1)).hasContent();
    }
}
