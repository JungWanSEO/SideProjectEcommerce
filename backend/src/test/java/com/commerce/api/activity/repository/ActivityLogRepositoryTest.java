package com.commerce.api.activity.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.activity.entity.ActivityLog;
import com.commerce.api.activity.entity.ActivityType;
import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

/**
 * ActivityLogRepository 슬라이스 테스트 (@DataJpaTest) — "최근 본 상품" 그룹핑 쿼리 검증.
 *
 * <p>핵심은 조회 로그가 <b>append-only</b>라는 점: 같은 상품을 여러 번 보면 행이 여러 개 쌓인다.
 * 그래서 {@code group by product_id} + {@code order by max(created_at) desc}가 실제로
 * "상품별 1건 · 마지막 조회 순"을 주는지 확인한다.
 *
 * <p>createdAt은 @CreatedDate(감사)라 저장 시각으로 자동 채워져 <b>테스트에서 시각을 통제할 수 없다</b>
 * (연속 저장은 같은 밀리초가 될 수 있어 순서가 흔들린다). 그래서 저장 후 <b>네이티브 UPDATE로 created_at을
 * 못박고</b>(감사 리스너·updatable=false 우회) 영속성 컨텍스트를 비운 뒤 조회한다.
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})   // 감사(createdAt) + JPAQueryFactory 빈
class ActivityLogRepositoryTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;

    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private TestEntityManager em;

    private LocalDateTime base;

    @BeforeEach
    void setUp() {
        base = LocalDateTime.now().minusDays(1);
    }

    @Test
    @DisplayName("최근 본 상품 - 상품별 1건(마지막 조회 시각 기준)으로 접어 최신순 productId를 준다")
    void findRecentlyViewedProductIds_groupsByProductAndOrdersByLastView() {
        viewedAt(MEMBER_ID, 30L, base.plusMinutes(10));   // 30번: 오래 전
        viewedAt(MEMBER_ID, 10L, base.plusMinutes(20));   // 10번: 처음 봄 (이 시각은 버려짐)
        viewedAt(MEMBER_ID, 20L, base.plusMinutes(30));   // 20번
        viewedAt(MEMBER_ID, 10L, base.plusMinutes(40));   // 10번을 다시 봄 → 10번이 가장 최근
        viewedAt(OTHER_MEMBER_ID, 99L, base.plusMinutes(50));   // 남의 로그 → 안 보임
        em.clear();

        List<Long> ids = activityLogRepository.findRecentlyViewedProductIds(
                MEMBER_ID, ActivityType.VIEW, PageRequest.of(0, 10));

        assertThat(ids).containsExactly(10L, 20L, 30L);   // 중복 없음(3행 아님), 다른 회원 것 없음
    }

    @Test
    @DisplayName("최근 본 상품 - Pageable size로 개수를 자른다(최신 N개)")
    void findRecentlyViewedProductIds_limitsBySize() {
        viewedAt(MEMBER_ID, 30L, base.plusMinutes(10));
        viewedAt(MEMBER_ID, 20L, base.plusMinutes(20));
        viewedAt(MEMBER_ID, 10L, base.plusMinutes(30));
        em.clear();

        List<Long> ids = activityLogRepository.findRecentlyViewedProductIds(
                MEMBER_ID, ActivityType.VIEW, PageRequest.of(0, 2));

        assertThat(ids).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("최근 본 상품 - 조회 이력이 없으면 빈 목록")
    void findRecentlyViewedProductIds_empty() {
        assertThat(activityLogRepository.findRecentlyViewedProductIds(
                MEMBER_ID, ActivityType.VIEW, PageRequest.of(0, 10))).isEmpty();
    }

    /** 조회 로그 1건을 지정한 시각으로 못박아 저장한다(@CreatedDate 자동값을 네이티브 UPDATE로 덮어씀). */
    private void viewedAt(Long memberId, Long productId, LocalDateTime viewedAt) {
        ActivityLog log = activityLogRepository.saveAndFlush(ActivityLog.view(memberId, productId));
        em.getEntityManager()
                .createNativeQuery("update activity_log set created_at = :t where id = :id")
                .setParameter("t", viewedAt)
                .setParameter("id", log.getId())
                .executeUpdate();
    }
}
