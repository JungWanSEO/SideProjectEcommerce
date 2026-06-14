package com.commerce.api.settlement.service;

import com.commerce.api.global.common.PageResponse;
import com.commerce.api.global.exception.BusinessException;
import com.commerce.api.payment.gateway.PaymentGatewayRouter;
import com.commerce.api.payment.gateway.PgSettlementRecord;
import com.commerce.api.payment.gateway.PgSettlementStatus;
import com.commerce.api.settlement.dto.MismatchResponse;
import com.commerce.api.settlement.dto.ReconciliationResult;
import com.commerce.api.settlement.dto.ReconciliationResult.ProviderReconciliation;
import com.commerce.api.settlement.entity.Mismatch;
import com.commerce.api.settlement.entity.MismatchStatus;
import com.commerce.api.settlement.entity.MismatchType;
import com.commerce.api.settlement.entity.SettlementEntry;
import com.commerce.api.settlement.repository.MismatchRepository;
import com.commerce.api.settlement.repository.SettlementRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사(reconciliation) 서비스.
 *
 * <p>두 진실의 출처 — 우리 {@link SettlementEntry}와 PG 정산 리포트({@link PaymentGatewayRouter#fetchAllSettlements()}) —
 * 를 {@code pgTransactionId}로 매칭해 어긋남을 분류·기록(예외 큐)하고, 사람이 처리(resolve/ignore)한다.
 * .NET으로 치면 두 컬렉션을 키로 outer-join 해 교집합/차집합을 가르는 일.
 *
 * <p>PG 라우터를 통해 <b>모든 PG의 리포트를 합쳐</b> 대조한다 — PG는 결제·정산이 공유하는 외부 인프라이고,
 * 다중 PG여도 거래 ID 프리픽스가 PG를 구분하므로 키가 겹치지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final SettlementRepository settlementRepository;
    private final MismatchRepository mismatchRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;

    /**
     * 대사 실행 — 우리 정산 ↔ PG 리포트 전체를 거래키로 대조한다.
     *
     * <p><b>예외 큐 운영:</b> 이전 OPEN 불일치만 비우고 다시 스냅샷하되, 이미 처리된(RESOLVED/IGNORED)
     * 거래키는 다시 OPEN으로 만들지 않는다(사람의 처리 결정을 존중). 그래서 재대사를 돌려도 처리한 건은
     * 되살아나지 않고, 아직 안 본 OPEN만 갱신된다.
     *
     * <p>단순화: 실무는 일자별 윈도우로 대조하지만 여기선 전체를 본다(P1 정산 배치와 같은 단순화).
     * 또 한번 resolve/ignore한 거래키는 이후 데이터가 여전히 어긋나도 다시 뜨지 않는다(재오픈 정책 생략).
     */
    @Transactional
    public ReconciliationResult reconcile() {
        // 우리 정산은 (결제×셀러)로 쪼개져 한 pgTransactionId에 항목이 여러 개일 수 있다(Phase 2).
        // 대사는 결제(거래) 단위이므로 pgTransactionId로 묶어 매출(gross)을 합산한 뒤 PG 리포트와 대조한다.
        Map<String, OursTx> ours = new HashMap<>();
        for (SettlementEntry e : settlementRepository.findAll()) {
            OursTx agg = ours.get(e.getPgTransactionId());
            if (agg == null) {
                ours.put(e.getPgTransactionId(), new OursTx(e.getProvider(), e.getGrossAmount()));
            } else {
                agg.gross += e.getGrossAmount();   // 같은 결제의 셀러 항목들을 합산(provider는 동일)
            }
        }
        Map<String, PgSettlementRecord> pg = paymentGatewayRouter.fetchAllSettlements().stream()
                .collect(Collectors.toMap(PgSettlementRecord::pgTransactionId, Function.identity(), (a, b) -> a));

        // 이미 처리된 거래키(RESOLVED/IGNORED) — 재대사에서 다시 OPEN으로 만들지 않는다.
        Set<String> handledKeys = mismatchRepository
                .findByStatusIn(List.of(MismatchStatus.RESOLVED, MismatchStatus.IGNORED)).stream()
                .map(Mismatch::getPgTransactionId)
                .collect(Collectors.toSet());

        mismatchRepository.deleteByStatus(MismatchStatus.OPEN);   // 직전 OPEN 스냅샷만 비움(처리된 건 보존)

        int matched = 0, missingInPg = 0, missingInOurs = 0, amountMismatch = 0, statusMismatch = 0, alreadyHandled = 0;
        // PG별 누적 — TreeMap이라 응답의 PG 순서가 알파벳순으로 결정적(테스트·표시 안정).
        Map<String, ProviderAccumulator> byProvider = new TreeMap<>();

        Set<String> keys = new HashSet<>(ours.keySet());
        keys.addAll(pg.keySet());

        for (String key : keys) {
            OursTx o = ours.get(key);
            PgSettlementRecord p = pg.get(key);
            // 거래의 PG — 우리 정산이 있으면 그 provider(MPG-3), 없으면 PG 리포트가 알려준 provider.
            String provider = (o != null) ? o.provider : p.provider();
            ProviderAccumulator acc = byProvider.computeIfAbsent(provider, ProviderAccumulator::new);

            // 1) 어긋남 분류 (일치면 카운트만 하고 다음으로)
            MismatchType type;
            Long ourAmount, pgAmount;
            String detail;
            if (o != null && p == null) {
                type = MismatchType.MISSING_IN_PG;
                ourAmount = o.gross; pgAmount = null;
                detail = "우리 정산엔 있으나 PG 리포트에 없음(웹훅 유실/PG 누락 의심)";
            } else if (o == null && p != null) {
                type = MismatchType.MISSING_IN_OURS;
                ourAmount = null; pgAmount = p.amount();
                detail = "PG 리포트엔 있으나 우리 정산 없음(정산 미실행/누락)";
            } else if (p.status() == PgSettlementStatus.REFUNDED) {
                type = MismatchType.STATUS_MISMATCH;
                ourAmount = o.gross; pgAmount = p.amount();
                detail = "PG는 환불됨이나 우리 정산은 미반영(상계 필요)";
            } else if (o.gross != p.amount()) {
                type = MismatchType.AMOUNT_MISMATCH;
                ourAmount = o.gross; pgAmount = p.amount();
                detail = "금액 상이(수수료·부분취소 반영 차이)";
            } else {
                matched++; acc.matched++;
                continue;
            }

            // 2) 이미 사람이 처리한 거래키면 다시 OPEN으로 만들지 않고 건너뜀
            if (handledKeys.contains(key)) {
                alreadyHandled++; acc.alreadyHandled++;
                continue;
            }

            // 3) 새 OPEN 불일치 기록 (어느 PG의 어긋남인지 함께 — MPG-2)
            mismatchRepository.save(Mismatch.of(key, provider, type, ourAmount, pgAmount, detail));
            switch (type) {
                case MISSING_IN_PG -> { missingInPg++; acc.missingInPg++; }
                case MISSING_IN_OURS -> { missingInOurs++; acc.missingInOurs++; }
                case STATUS_MISMATCH -> { statusMismatch++; acc.statusMismatch++; }
                case AMOUNT_MISMATCH -> { amountMismatch++; acc.amountMismatch++; }
            }
        }

        int total = missingInPg + missingInOurs + amountMismatch + statusMismatch;
        List<ProviderReconciliation> breakdown = byProvider.values().stream()
                .map(ProviderAccumulator::toDto).toList();
        return new ReconciliationResult(matched, missingInPg, missingInOurs, amountMismatch, statusMismatch,
                total, alreadyHandled, breakdown);
    }

    /** 우리 정산을 거래(pgTransactionId) 단위로 합산한 값 — 셀러 분할분을 결제 단위로 되묶어 PG와 대조. */
    private static final class OursTx {
        private final String provider;   // 거래의 PG(같은 결제의 셀러 항목들은 동일)
        private long gross;              // 셀러 항목 매출의 합 = 결제 매출

        OursTx(String provider, long gross) {
            this.provider = provider;
            this.gross = gross;
        }
    }

    /** PG 한 곳의 대사 누적기 — reconcile() 안에서만 쓰는 가변 집계 도우미. */
    private static final class ProviderAccumulator {
        private final String provider;
        private int matched, missingInPg, missingInOurs, amountMismatch, statusMismatch, alreadyHandled;

        ProviderAccumulator(String provider) {
            this.provider = provider;
        }

        ProviderReconciliation toDto() {
            int total = missingInPg + missingInOurs + amountMismatch + statusMismatch;
            return new ProviderReconciliation(provider, matched, missingInPg, missingInOurs,
                    amountMismatch, statusMismatch, total, alreadyHandled);
        }
    }

    /** 불일치 처리(상계·보정 완료) → RESOLVED. */
    @Transactional
    public MismatchResponse resolve(Long id, String note) {
        Mismatch m = findMismatch(id);
        m.resolve(note);   // OPEN→RESOLVED (이미 종료면 409). 변경은 더티 체킹으로 반영.
        return MismatchResponse.from(m);
    }

    /** 불일치 무시(오탐·허용) → IGNORED. */
    @Transactional
    public MismatchResponse ignore(Long id, String note) {
        Mismatch m = findMismatch(id);
        m.ignore(note);
        return MismatchResponse.from(m);
    }

    private Mismatch findMismatch(Long id) {
        return mismatchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "불일치 항목을 찾을 수 없습니다."));
    }

    /**
     * 불일치 항목 목록(페이지). status·provider는 각각 선택 필터(둘 다 없으면 전체) — MPG-2: PG별로도 본다.
     * provider는 대문자로 정규화(저장 표기와 일치).
     */
    @Transactional(readOnly = true)
    public PageResponse<MismatchResponse> getMismatches(MismatchStatus status, String provider, Pageable pageable) {
        String pg = (provider == null || provider.isBlank()) ? null : provider.toUpperCase();
        Page<Mismatch> page;
        if (status == null && pg == null) {
            page = mismatchRepository.findAll(pageable);
        } else if (pg == null) {
            page = mismatchRepository.findByStatus(status, pageable);
        } else if (status == null) {
            page = mismatchRepository.findByProvider(pg, pageable);
        } else {
            page = mismatchRepository.findByStatusAndProvider(status, pg, pageable);
        }
        return PageResponse.from(page.map(MismatchResponse::from));
    }
}
