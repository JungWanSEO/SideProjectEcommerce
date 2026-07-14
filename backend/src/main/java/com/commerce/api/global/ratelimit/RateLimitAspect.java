package com.commerce.api.global.ratelimit;

import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
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
 * {@code @RateLimit} 메서드를 감싸 호출 횟수를 제한하는 애스펙트 ({@code AuditAspect}와 같은 패턴).
 *
 * <p>이전엔 컨트롤러·서비스 세 곳이 {@code rateLimiter.check("login:" + email, 5)}처럼 <b>키를 손으로 조립</b>했다.
 * 그러면 (1) 키 규칙이 흩어지고 (2) 제한을 걸려면 업무 코드를 고쳐야 하며 (3) IP를 쓰려고 컨트롤러가
 * {@code HttpServletRequest}까지 받아야 했다. 애너테이션으로 올려 선언만 남긴다.
 *
 * <p>제한 초과 시 {@link RateLimiter} 구현이 {@link RateLimitExceededException}(429 + Retry-After)을 던진다.
 * {@code app.ratelimit.enabled=false}면 NoOp 어댑터가 주입돼 아무 일도 하지 않는다(테스트 기본).
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** SpEL 평가값이 null일 때 쓰는 식별자 — 키가 통째로 사라져 제한이 무력화되는 것을 막는다. */
    private static final String UNKNOWN = "unknown";

    private final RateLimiter rateLimiter;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        rateLimiter.check(rateLimit.key() + ":" + identifier(joinPoint, rateLimit), rateLimit.limit());
        return joinPoint.proceed();
    }

    /** by()가 있으면 SpEL로 인자에서 뽑고(회원·이메일), 없으면 클라이언트 IP. */
    private String identifier(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        if (!StringUtils.hasText(rateLimit.by())) {
            return clientIp();
        }
        Object value = evaluate(joinPoint, rateLimit.by());
        return value == null ? UNKNOWN : String.valueOf(value);
    }

    private Object evaluate(ProceedingJoinPoint joinPoint, String expression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] names = paramNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        Expression parsed = spelParser.parseExpression(expression);
        return parsed.getValue(context);
    }

    /**
     * 클라이언트 IP. 리버스 프록시(Caddy) 뒤에선 {@code server.forward-headers-strategy=framework}가
     * X-Forwarded-For를 반영해 실제 클라 IP가 된다(프록시 IP 아님).
     * 요청 컨텍스트가 없으면(스케줄러·테스트) UNKNOWN으로 센다.
     */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRemoteAddr();
        }
        return UNKNOWN;
    }
}
