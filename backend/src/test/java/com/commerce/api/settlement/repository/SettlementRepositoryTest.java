package com.commerce.api.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.api.global.config.JpaConfig;
import com.commerce.api.global.config.QuerydslConfig;
import com.commerce.api.settlement.dto.SellerSettlementSummary;
import com.commerce.api.settlement.dto.SettlementSearchCondition;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * SettlementRepository 슬라이스 테스트 (@DataJpaTest) — 셀러/상태/기간 필터 + 셀러별 집계(QueryDSL).
 */
@DataJpaTest
@Import({JpaConfig.class, QuerydslConfig.class})   // QuerydslConfig: search/summary가 쓰는 JPAQueryFactory 빈 제공
class SettlementRepositoryTest {

    @Autowired
    private SettlementRepository repository;

    private static final LocalDate D1 = LocalDate.now().plusDays(2);
    private static final LocalDate D2 = LocalDate.now().plusDays(5);

    private SettlementEntry entry(long paymentId, Long sellerId, long gross, long fee, long platformFee,
            SettlementStatus status, LocalDate date) {
        SettlementEntry e = SettlementEntry.scheduled(
                paymentId, paymentId, "tx-" + paymentId + "-" + sellerId, "TOSS",
                sellerId, gross, fee, 0.025, platformFee, 0.10, date);
        if (status == SettlementStatus.PAID_OUT) {
            e.markPaidOut();
        }
        return repository.save(e);
    }

    @BeforeEach
    void seed() {
        entry(1L, 1L, 10000L, 250L, 1000L, SettlementStatus.SCHEDULED, D1);   // 셀러1, net 8750
        entry(2L, 1L, 20000L, 500L, 2000L, SettlementStatus.PAID_OUT, D2);    // 셀러1, net 17500
        entry(3L, 2L, 5000L, 125L, 250L, SettlementStatus.SCHEDULED, D1);     // 셀러2, net 4625
    }

    @Test
    @DisplayName("search - 셀러 필터")
    void search_bySeller() {
        Page<SettlementEntry> page = repository.search(
                new SettlementSearchCondition(1L, null, null, null), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(e -> e.getSellerId() == 1L);
    }

    @Test
    @DisplayName("search - 상태 필터")
    void search_byStatus() {
        Page<SettlementEntry> page = repository.search(
                new SettlementSearchCondition(null, SettlementStatus.SCHEDULED, null, null), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);   // e1, e3
        assertThat(page.getContent()).allMatch(e -> e.getStatus() == SettlementStatus.SCHEDULED);
    }

    @Test
    @DisplayName("search - 기간(정산일) 필터: from=D2면 D2 이후만")
    void search_byDateRange() {
        Page<SettlementEntry> page = repository.search(
                new SettlementSearchCondition(null, null, D2, null), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);   // e2(D2)만
        assertThat(page.getContent().get(0).getPaymentId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("search - 필터 없으면 전체")
    void search_noFilter() {
        Page<SettlementEntry> page = repository.search(
                new SettlementSearchCondition(null, null, null, null), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("summarizeBySeller - 셀러별 매출/수수료/실수령 집계(sellerName은 null=서비스 enrich)")
    void summarizeBySeller() {
        List<SellerSettlementSummary> summary =
                repository.summarizeBySeller(new SettlementSearchCondition(null, null, null, null));

        assertThat(summary).hasSize(2);
        SellerSettlementSummary s1 = summary.stream().filter(s -> s.sellerId() == 1L).findFirst().orElseThrow();
        assertThat(s1.count()).isEqualTo(2);
        assertThat(s1.grossAmount()).isEqualTo(30000L);   // 10000 + 20000
        assertThat(s1.fee()).isEqualTo(750L);             // 250 + 500
        assertThat(s1.platformFee()).isEqualTo(3000L);    // 1000 + 2000
        assertThat(s1.netAmount()).isEqualTo(26250L);     // 8750 + 17500
        assertThat(s1.sellerName()).isNull();             // enrich는 서비스 책임

        SellerSettlementSummary s2 = summary.stream().filter(s -> s.sellerId() == 2L).findFirst().orElseThrow();
        assertThat(s2.count()).isEqualTo(1);
        assertThat(s2.netAmount()).isEqualTo(4625L);
    }

    @Test
    @DisplayName("summarizeBySeller - 셀러 필터를 주면 그 셀러 그룹만")
    void summarizeBySeller_filtered() {
        List<SellerSettlementSummary> summary =
                repository.summarizeBySeller(new SettlementSearchCondition(1L, null, null, null));

        assertThat(summary).hasSize(1);
        assertThat(summary.get(0).sellerId()).isEqualTo(1L);
        assertThat(summary.get(0).grossAmount()).isEqualTo(30000L);
    }
}
