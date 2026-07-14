package com.commerce.api.audit.service;

import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.repository.AuditLogRepository;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 적재/조회/내보내기 서비스.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** CSV 내보내기 한 번에 읽는 행 수(메모리 상한). */
    private static final int CHUNK_SIZE = 1_000;

    /** CSV 내보내기 최대 행 수(응답 폭주 방지). 넘치면 파일 끝에 안내 행. */
    private static final int MAX_EXPORT_ROWS = 50_000;

    private final AuditLogRepository auditLogRepository;
    private final MemberRepository memberRepository;

    /**
     * 감사 로그 1건 적재. {@link com.commerce.api.audit.aspect.AuditAspect}가 업무 트랜잭션 밖(성공/실패 후)에 호출한다.
     *
     * <p><b>REQUIRES_NEW</b>: 감사 기록을 독립 트랜잭션에 남겨, 감사 대상 업무가 롤백되더라도(또는 실패했더라도)
     * "무엇을 시도했다"는 이력은 보존한다. 실패 감사(FAILURE)를 남기려면 별도 트랜잭션이 필수다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorMemberId, String action, String targetType,
                       String targetId, String detail, AuditResult result) {
        auditLogRepository.save(AuditLog.builder()
                .actorMemberId(actorMemberId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .result(result)
                .build());
    }

    /**
     * 감사 로그 검색(최신순). 행위자 이메일은 회원 배치 조회로 enrich한다(N+1 회피).
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(AuditLogSearchCondition condition, Pageable pageable) {
        Page<AuditLog> page = auditLogRepository.search(condition, pageable);
        Page<AuditLogResponse> mapped = enrich(page);
        return PageResponse.from(mapped);
    }

    /**
     * 검색 결과를 CSV로 내보낸다(감사는 "뽑아서 보관"이 용도 — 화면 조회만으론 반쪽).
     *
     * <p><b>메모리</b>: 전체를 리스트로 들지 않고 {@value #CHUNK_SIZE}행씩 페이지로 읽어 바로 흘려 쓴다
     * (컨트롤러의 StreamingResponseBody가 이 메서드를 스트리밍 중에 호출 → 트랜잭션도 그때 열린다).
     *
     * <p><b>스냅샷 경계</b>: 정렬이 최신순이라 내보내는 중에 새 감사 로그가 쌓이면 offset이 밀려
     * <b>같은 행이 페이지 경계에서 중복</b>될 수 있다. 그래서 to(상한, 미만)가 없으면 <b>내보내기 시작 시각</b>으로
     * 못 박아 "그 순간까지의 스냅샷"을 뽑는다.
     *
     * <p><b>상한</b>: {@value #MAX_EXPORT_ROWS}행. 넘치면 파일 끝에 안내 행을 써서 잘렸음을 알린다(무언의 절단 금지).
     */
    @Transactional(readOnly = true)
    public void exportCsv(AuditLogSearchCondition condition, Writer writer) throws IOException {
        AuditLogSearchCondition snapshot = snapshotBoundary(condition);
        AuditCsvWriter.writeHeader(writer);

        int written = 0;
        for (int page = 0; written < MAX_EXPORT_ROWS; page++) {
            Page<AuditLog> chunk = auditLogRepository.search(snapshot, PageRequest.of(page, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                return;   // 다 썼다(잘리지 않음)
            }
            for (AuditLogResponse log : enrich(chunk)) {
                AuditCsvWriter.writeRow(writer, log);
                if (++written >= MAX_EXPORT_ROWS) {
                    break;
                }
            }
            writer.flush();
            if (!chunk.hasNext()) {
                return;   // 다 썼다
            }
        }
        AuditCsvWriter.writeTruncationNotice(writer, MAX_EXPORT_ROWS);   // 상한에 걸려 잘림
    }

    /** to(상한)가 없으면 지금으로 고정 — 내보내는 동안 들어오는 새 로그가 페이지를 밀지 않게. */
    private AuditLogSearchCondition snapshotBoundary(AuditLogSearchCondition condition) {
        if (condition.to() != null) {
            return condition;
        }
        return new AuditLogSearchCondition(condition.actorMemberId(), condition.action(),
                condition.targetType(), condition.result(), condition.from(), LocalDateTime.now());
    }

    /** 행위자 이메일을 배치 조회로 채운다(N+1 회피). */
    private Page<AuditLogResponse> enrich(Page<AuditLog> page) {
        Set<Long> actorIds = page.getContent().stream()
                .map(AuditLog::getActorMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> emailByActor = memberRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getEmail));

        return page.map(log -> AuditLogResponse.of(log, emailByActor.get(log.getActorMemberId())));
    }
}
