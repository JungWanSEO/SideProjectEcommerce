package com.commerce.api.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 — <b>분산락 또는 레이트리밋이 redisson</b> 일 때만 생성한다
 * ({@code app.lock.provider=redisson} 이거나 {@code app.ratelimit.provider=redisson}).
 *
 * <p>core 라이브러리만 쓰므로 자동설정이 없다 → 이 빈이 있을 때만 Redisson이 Redis에 연결한다(둘 다 redisson이
 * 아니면 Redisson 미사용). 주소는 캐시와 같은 {@code spring.data.redis.host/port} 를 재사용.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(
            "'${app.lock.provider:none}'.equals('redisson') or '${app.ratelimit.provider:memory}'.equals('redisson')")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        // lockWatchdogTimeout 은 기본 30s — 분산락 보유 동안 자동 연장(watchdog). 운영 기본값 사용.
        return Redisson.create(config);
    }
}
