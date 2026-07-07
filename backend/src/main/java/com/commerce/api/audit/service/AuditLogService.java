package com.commerce.api.audit.service;

import com.commerce.api.audit.dto.AuditLogResponse;
import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.repository.AuditLogRepository;
import com.commerce.api.global.common.PageResponse;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.repository.MemberRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 적재/조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

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

        Set<Long> actorIds = page.getContent().stream()
                .map(AuditLog::getActorMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> emailByActor = memberRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(Member::getId, Member::getEmail));

        Page<AuditLogResponse> mapped = page.map(log ->
                AuditLogResponse.of(log, emailByActor.get(log.getActorMemberId())));
        return PageResponse.from(mapped);
    }
}
