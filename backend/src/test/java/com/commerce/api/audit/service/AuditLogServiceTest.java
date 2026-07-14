package com.commerce.api.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commerce.api.audit.dto.AuditLogSearchCondition;
import com.commerce.api.audit.entity.AuditLog;
import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.repository.AuditLogRepository;
import com.commerce.api.member.entity.Member;
import com.commerce.api.member.entity.Role;
import com.commerce.api.member.repository.MemberRepository;
import java.io.IOException;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AuditLogService CSV 내보내기 단위 테스트 (Mockito).
 * 청크 조회·행위자 enrich·스냅샷 경계(to 고정)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private MemberRepository memberRepository;

    @InjectMocks private AuditLogService auditLogService;

    private AuditLog log(Long id, Long actorId, String action) {
        AuditLog log = AuditLog.builder()
                .actorMemberId(actorId)
                .action(action)
                .targetType("PRODUCT")
                .targetId("42")
                .detail("PUT /api/products/42")
                .result(AuditResult.SUCCESS)
                .build();
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "createdAt", LocalDateTime.of(2026, 7, 14, 9, 0, 0));
        return log;
    }

    private Member member(Long id, String email) {
        Member member = Member.builder()
                .email(email).password("ENCODED").nickname("admin").role(Role.ADMIN).build();
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    @Test
    @DisplayName("CSV 내보내기 - 헤더(BOM) + 행, 행위자 이메일은 배치 조회로 채운다")
    void exportCsv_writesHeaderAndRows() throws IOException {
        Page<AuditLog> page = new PageImpl<>(
                List.of(log(1L, 9L, "PRODUCT_UPDATE")), PageRequest.of(0, 1_000), 1);
        given(auditLogRepository.search(any(AuditLogSearchCondition.class), any(Pageable.class)))
                .willReturn(page);
        given(memberRepository.findAllById(anyIterable())).willReturn(List.of(member(9L, "admin@commerce.com")));

        StringWriter out = new StringWriter();
        auditLogService.exportCsv(new AuditLogSearchCondition(null, null, null, null, null, null), out);

        String csv = out.toString();
        assertThat(csv).startsWith("﻿ID,시각,행위자ID,행위자");                       // BOM + 헤더
        assertThat(csv).contains("1,2026-07-14 09:00:00,9,admin@commerce.com,PRODUCT_UPDATE");
        assertThat(csv).doesNotContain("최대");                                        // 잘리지 않았으니 안내 없음
    }

    @Test
    @DisplayName("CSV 내보내기 - to가 없으면 '지금'으로 고정해 스냅샷을 뽑는다(내보내는 중 새 로그가 페이지를 밀지 않게)")
    void exportCsv_pinsSnapshotBoundary() throws IOException {
        given(auditLogRepository.search(any(AuditLogSearchCondition.class), any(Pageable.class)))
                .willReturn(Page.empty(PageRequest.of(0, 1_000)));

        LocalDateTime before = LocalDateTime.now();
        auditLogService.exportCsv(
                new AuditLogSearchCondition(null, null, null, null, null, null), new StringWriter());

        ArgumentCaptor<AuditLogSearchCondition> captor =
                ArgumentCaptor.forClass(AuditLogSearchCondition.class);
        verify(auditLogRepository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue().to())
                .isNotNull()
                .isAfterOrEqualTo(before);   // 내보내기 시작 시각으로 상한이 박힌다
    }

    @Test
    @DisplayName("CSV 내보내기 - 사용자가 준 to는 그대로 존중한다")
    void exportCsv_keepsGivenBoundary() throws IOException {
        LocalDateTime to = LocalDateTime.of(2026, 7, 1, 0, 0);
        given(auditLogRepository.search(any(AuditLogSearchCondition.class), any(Pageable.class)))
                .willReturn(Page.empty(PageRequest.of(0, 1_000)));

        auditLogService.exportCsv(
                new AuditLogSearchCondition(null, null, null, null, null, to), new StringWriter());

        ArgumentCaptor<AuditLogSearchCondition> captor =
                ArgumentCaptor.forClass(AuditLogSearchCondition.class);
        verify(auditLogRepository).search(captor.capture(), any(Pageable.class));
        assertThat(captor.getValue().to()).isEqualTo(to);
    }
}
