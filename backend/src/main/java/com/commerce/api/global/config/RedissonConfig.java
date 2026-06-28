package com.commerce.api.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 — {@code app.lock.provider=redisson} 일 때만 생성한다(분산락 비교 실습용).
 *
 * <p>core 라이브러리만 쓰므로 자동설정이 없다 → 이 빈이 있을 때만 Redisson이 Redis에 연결한다(기본 none/redis면
 * Redisson 미사용). 주소는 캐시와 같은 {@code spring.data.redis.host/port} 를 재사용.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "app.lock.provider", havingValue = "redisson")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        // lockWatchdogTimeout 은 기본 30s — 보유 동안 10s마다 자동 연장(watchdog). 운영 기본값 사용.
        return Redisson.create(config);
    }
}
