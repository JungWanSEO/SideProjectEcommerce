package com.commerce.api.audit.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 메서드의 실행을 <b>감사 로그로 남긴다</b>는 선언. {@link AuditAspect}가 이 애너테이션을 잡아
 * 행위자·액션·대상·결과를 자동 기록한다(도메인 코드는 감사를 몰라도 됨 = 횡단 관심사 분리).
 *
 * <p>보통 어드민 변경(mutation) 컨트롤러 메서드에 붙인다.
 * 예: {@code @Auditable(action = "PRODUCT_UPDATE", targetType = "PRODUCT", targetId = "#id")}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 액션 코드(필수). 예: "PRODUCT_UPDATE". */
    String action();

    /** 대상 리소스 종류. 예: "PRODUCT". 없으면 생략. */
    String targetType() default "";

    /**
     * 대상 식별자를 뽑는 SpEL 식. 메서드 인자(예: {@code #id})나 반환값(예: {@code #result.body.data.id})을 참조한다.
     * 비우면 대상 ID 없이 기록. 평가에 실패해도(식이 안 맞아도) 감사 자체는 남긴다(targetId=null).
     */
    String targetId() default "";
}
