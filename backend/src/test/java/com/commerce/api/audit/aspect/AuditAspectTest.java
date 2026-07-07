package com.commerce.api.audit.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * {@link AuditAspect} 단위 테스트 — AspectJProxyFactory로 @Auditable 타깃을 프록시해 아스펙트만 검증한다.
 * (스프링 컨텍스트 없이 AOP 어드바이스 동작·SpEL 대상ID·성공/실패 분기를 빠르게 확인.)
 */
class AuditAspectTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private Sample proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Sample());
        factory.addAspect(new AuditAspect(auditLogService));
        return factory.getProxy();
    }

    @Test
    @DisplayName("정상 실행이면 SUCCESS로 감사 로그를 남기고 대상ID를 SpEL(#id)로 뽑는다 (반환값은 그대로 통과)")
    void records_success() {
        String result = proxied().update(42L);

        assertThat(result).isEqualTo("ok:42");   // 아스펙트가 반환값을 바꾸지 않는다
        // actor·detail은 보안/요청 컨텍스트가 없어 null. 대상ID는 인자 #id → "42".
        verify(auditLogService).record(
                isNull(), eq("SAMPLE_UPDATE"), eq("SAMPLE"), eq("42"), isNull(), eq(AuditResult.SUCCESS));
    }

    @Test
    @DisplayName("대상 메서드가 예외를 던지면 FAILURE로 남기고 예외는 그대로 전파한다")
    void records_failure_and_propagates() {
        assertThatThrownBy(() -> proxied().fail(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        verify(auditLogService).record(
                isNull(), eq("SAMPLE_FAIL"), eq("SAMPLE"), eq("7"), isNull(), eq(AuditResult.FAILURE));
    }

    /** @Auditable을 붙인 테스트용 타깃. */
    static class Sample {

        @Auditable(action = "SAMPLE_UPDATE", targetType = "SAMPLE", targetId = "#id")
        public String update(Long id) {
            return "ok:" + id;
        }

        @Auditable(action = "SAMPLE_FAIL", targetType = "SAMPLE", targetId = "#id")
        public void fail(Long id) {
            throw new IllegalStateException("boom");
        }
    }
}
