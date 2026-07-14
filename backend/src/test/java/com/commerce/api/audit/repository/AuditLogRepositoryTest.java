package com.commerce.api.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * AuditLogRepository 슬라이스 테스트 — 감사 로그 검색의 QueryDSL 동적 where.
 *
 * <p><b>왜 이제야</b>: JaCoCo 리포트(07-14)에서 {@code AuditLogRepositoryImpl}이 <b>커버리지 0%</b>로 드러났다 —
 * 감사 화면·CSV 내보내기가 전적으로 의존하는 필터가 한 번도 실행된 적이 없었다(필터가 틀려도 아무도 모름).
 *
 * <p>createdAt은 @CreatedDate(감사)라 저장 시각으로 자동 채워진다 → 기간(from/to) 검증은 저장 후
 * <b>네이티브 UPDATE로 created_at을 못 박아</b> 결정론적으로 만든다(ActivityLogRepositoryTest와 같은 수법).
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})
class AuditLogRepositoryTest {

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TestEntityManager em;

    private AuditLog log(Long actorId, String action, String targetType, AuditResult result) {
        return AuditLog.builder()
                .actorMemberId(actorId)
                .action(action)
                .targetType(targetType)
                .targetId("42")
                .detail("PUT /api/products/42")
                .result(result)
                .build();
    }

    /** 지정한 시각으로 못 박아 저장(@CreatedDate 자동값을 네이티브 UPDATE로 덮어씀). */
    private void savedAt(AuditLog log, LocalDateTime createdAt) {
        AuditLog saved = auditLogRepository.saveAndFlush(log);
        em.getEntityManager()
                .createNativeQuery("update audit_log set created_at = :t where id = :id")
                .setParameter("t", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("search - 조건이 비면 전체를 최신순으로")
    void search_noCondition() {
        auditLogRepository.save(log(1L, "PRODUCT_UPDATE", "PRODUCT", AuditResult.SUCCESS));
        auditLogRepository.save(log(2L, "COUPON_CREATE", "COUPON", AuditResult.FAILURE));

        Page<AuditLog> page = auditLogRepository.search(
                new AuditLogSearchCondition(null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("search - 행위자(actorMemberId)로 거른다")
    void search_byActor() {
        auditLogRepository.save(log(1L, "PRODUCT_UPDATE", "PRODUCT", AuditResult.SUCCESS));
        auditLogRepository.save(log(2L, "PRODUCT_UPDATE", "PRODUCT", AuditResult.SUCCESS));

        Page<AuditLog> page = auditLogRepository.search(
                new AuditLogSearchCondition(2L, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getContent()).extracting(AuditLog::getActorMemberId).containsExactly(2L);
    }

    @Test
    @DisplayName("search - 액션 코드·대상 종류로 거른다(정확히 일치)")
    void search_byActionAndTargetType() {
        auditLogRepository.save(log(1L, "PAYOUT_PAY", "PAYOUT", AuditResult.SUCCESS));
        auditLogRepository.save(log(1L, "PRODUCT_UPDATE", "PRODUCT", AuditResult.SUCCESS));

        Page<AuditLog> byAction = auditLogRepository.search(
                new AuditLogSearchCondition(null, "PAYOUT_PAY", null, null, null, null), FIRST_PAGE);
        Page<AuditLog> byTarget = auditLogRepository.search(
                new AuditLogSearchCondition(null, null, "PRODUCT", null, null, null), FIRST_PAGE);

        assertThat(byAction.getContent()).extracting(AuditLog::getAction).containsExactly("PAYOUT_PAY");
        assertThat(byTarget.getContent()).extracting(AuditLog::getTargetType).containsExactly("PRODUCT");
    }

    @Test
    @DisplayName("search - 결과(SUCCESS/FAILURE)로 거른다 — 실패한 시도만 뽑는 게 감사의 핵심 용도")
    void search_byResult() {
        auditLogRepository.save(log(1L, "CATEGORY_UPDATE", "CATEGORY", AuditResult.SUCCESS));
        auditLogRepository.save(log(1L, "CATEGORY_UPDATE", "CATEGORY", AuditResult.FAILURE));

        Page<AuditLog> page = auditLogRepository.search(
                new AuditLogSearchCondition(null, null, null, AuditResult.FAILURE, null, null), FIRST_PAGE);

        assertThat(page.getContent()).extracting(AuditLog::getResult).containsExactly(AuditResult.FAILURE);
    }

    @Test
    @DisplayName("search - 기간 윈도우: from 이상(포함) ~ to 미만(제외)")
    void search_byPeriod() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 10, 12, 0);
        savedAt(log(1L, "OLD", "PRODUCT", AuditResult.SUCCESS), base.minusDays(2));
        savedAt(log(1L, "IN_WINDOW", "PRODUCT", AuditResult.SUCCESS), base);
        savedAt(log(1L, "AT_TO", "PRODUCT", AuditResult.SUCCESS), base.plusDays(1));   // to와 같은 시각 = 제외

        Page<AuditLog> page = auditLogRepository.search(
                new AuditLogSearchCondition(null, null, null, null, base.minusHours(1), base.plusDays(1)),
                FIRST_PAGE);

        assertThat(page.getContent()).extracting(AuditLog::getAction).containsExactly("IN_WINDOW");
    }

    @Test
    @DisplayName("search - 최신순 정렬(같은 시각이면 id 내림차순)")
    void search_ordersByCreatedAtDesc() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 10, 12, 0);
        savedAt(log(1L, "FIRST", "PRODUCT", AuditResult.SUCCESS), base.minusHours(2));
        savedAt(log(1L, "SECOND", "PRODUCT", AuditResult.SUCCESS), base.minusHours(1));
        savedAt(log(1L, "THIRD", "PRODUCT", AuditResult.SUCCESS), base);

        Page<AuditLog> page = auditLogRepository.search(
                new AuditLogSearchCondition(null, null, null, null, null, null), FIRST_PAGE);

        assertThat(page.getContent()).extracting(AuditLog::getAction)
                .containsExactly("THIRD", "SECOND", "FIRST");
    }
}
