package com.commerce.api.global.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 분산락 포트(port) — 임계구역을 키 단위로 직렬화한다. 구현(어댑터)은 NoOp(기본) / Redis(SETNX+Lua) 등.
 *
 * <p>outbox {@code EventPublisher}와 같은 포트-어댑터: 호출부는 이 인터페이스만 의존하고, 운영에서
 * Redisson·RedisLockRegistry 등으로 바꾸려면 어댑터만 교체한다(애플리케이션 코드 무변경).
 *
 * <p><b>역할 주의:</b> 우리 선착순 쿠폰의 정합성 최종 보증은 DB 원자적 조건부 UPDATE다. 이 락은 그 위에서
 * "다중 인스턴스가 같은 키에 몰리는 동시 요청을 앱 계층에서 하나씩" 통과시키는 <b>advisory(권고)</b> 락이다.
 */
public interface DistributedLock {

    /** 기본 대기/임대 시간 — 쿠폰 claim 같은 짧은 임계구역 기준(작업은 ms, lease는 보유자 사망 대비 여유). */
    Duration DEFAULT_WAIT = Duration.ofSeconds(3);
    Duration DEFAULT_LEASE = Duration.ofSeconds(5);

    /**
     * {@code key} 락을 잡고 {@code action}을 실행한 뒤 해제한다. 결과를 그대로 반환.
     * {@code waitTime} 안에 못 잡으면 503(잠시 후 다시). 보유 중 죽어도 {@code leaseTime} 후 자동 해제(데드락 방지).
     */
    <T> T executeWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /** 기본 대기/임대 시간으로 실행하는 편의 메서드. */
    default <T> T executeWithLock(String key, Supplier<T> action) {
        return executeWithLock(key, DEFAULT_WAIT, DEFAULT_LEASE, action);
    }
}
