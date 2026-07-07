package com.commerce.api.audit.aspect;

import com.commerce.api.audit.entity.AuditResult;
import com.commerce.api.audit.service.AuditLogService;
import com.commerce.api.global.security.SecurityUtil;
import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code @Auditable} 메서드를 감싸 <b>어드민 변경을 자동 감사</b>하는 애스펙트 — 횡단 관심사를 AOP로 분리.
 *
 * <p>@Around로 대상 메서드를 감싼다:
 * <ul>
 *   <li>정상 반환 → {@link AuditResult#SUCCESS}, 예외 → {@link AuditResult#FAILURE}로 감사 로그 적재.</li>
 *   <li>행위자는 SecurityContext에서, 대상 ID는 SpEL로 인자/반환값에서 뽑는다.</li>
 *   <li><b>감사 기록 실패는 삼켜</b> 원래 업무 흐름을 절대 깨지 않는다(best-effort).</li>
 * </ul>
 *
 * <p>기록은 {@link AuditLogService#record}가 REQUIRES_NEW로 남기므로 업무 트랜잭션 롤백과 무관하다.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogService auditLogService;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        Object result = null;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                writeAudit(joinPoint, auditable, result, error);
            } catch (Exception e) {
                // 감사는 부가기능 — 기록 실패가 실제 요청을 깨뜨리면 안 된다.
                log.warn("[audit] 감사 로그 기록 실패(업무 흐름엔 영향 없음): {}", e.getMessage());
            }
        }
    }

    private void writeAudit(ProceedingJoinPoint joinPoint, Auditable auditable,
                            Object result, Throwable error) {
        Long actorId = currentActorId();
        String targetId = evalTargetId(joinPoint, auditable.targetId(), result);
        String detail = httpDetail();
        AuditResult outcome = (error == null) ? AuditResult.SUCCESS : AuditResult.FAILURE;
        auditLogService.record(actorId, auditable.action(),
                blankToNull(auditable.targetType()), targetId, detail, outcome);
    }

    /** 현재 로그인 회원 ID. 인증 정보가 없으면(스케줄러/미인증) null. */
    private Long currentActorId() {
        try {
            return SecurityUtil.getCurrentMemberId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SpEL로 대상 ID를 뽑는다. 메서드 인자(파라미터명 변수, 예 {@code #id})와 반환값({@code #result})을 바인딩한다.
     * 평가 실패는 조용히 null(감사 자체는 남긴다).
     */
    private String evalTargetId(ProceedingJoinPoint joinPoint, String expression, Object result) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String[] paramNames = paramNameDiscoverer.getParameterNames(method);
            Object[] args = joinPoint.getArgs();

            StandardEvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("result", result);

            Expression exp = spelParser.parseExpression(expression);
            Object value = exp.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.debug("[audit] targetId SpEL 평가 실패: '{}' ({})", expression, e.getMessage());
            return null;
        }
    }

    /** 요청 컨텍스트에서 "HTTP메서드 URI"(예: "PUT /api/products/42"). 요청 밖이면 null. */
    private String httpDetail() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        var request = attributes.getRequest();
        return request.getMethod() + " " + request.getRequestURI();
    }

    private String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }
}
