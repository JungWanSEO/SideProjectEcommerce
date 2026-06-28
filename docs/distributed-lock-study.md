# 분산락 비교 분석 (선착순 쿠폰 동시성)

> 선착순 쿠폰 `claim`에 Redis 분산락을 얹기 전, 방식들을 비교하고 선택 근거를 남긴다.
> 결론: **DIY SETNX+Lua**를 `DistributedLock` 포트 뒤에 두고 opt-in 토글로. 운영 대안은 RedisLockRegistry/Redisson로 명시.

## 0. 먼저 — 이 락이 "정합성 보증"인가?

아니다. 현재 `MemberCouponService.claim`은 **DB 원자적 조건부 UPDATE**로 초과 발급을 이미 막는다:

```sql
UPDATE coupon SET issued_count = issued_count + 1
WHERE id = :id AND (total_quantity IS NULL OR issued_count < total_quantity)
```

영향 행 1=발급, 0=마감. DB 행 락으로 직렬화되므로 **단일 DB 기준 정합성은 이미 완결**이다.

게다가 **TTL 기반 Redis 락은 완벽한 상호배제가 아니다.** 락 보유 중 GC 일시정지/네트워크 지연/시계 오차로
TTL이 만료되면, 보유자는 "내가 락을 쥐었다"고 믿는데 다른 클라이언트가 이미 새 락을 얻을 수 있다. 엄밀한
정합성에는 **fencing token**(단조 증가 토큰을 보호 자원이 검증)이 필요하다(Kleppmann). 우리의 fencing 역할은
**DB 원자 UPDATE가 이미** 한다.

→ 따라서 Redis 분산락의 역할은 **정합성 보증이 아니라 "다중 인스턴스에서 같은 쿠폰 요청을 앱 계층에서
직렬화해 DB 경합을 줄이는 advisory(권고) 락"**이다. 이 프레이밍이 면접에서도 정확한 답이다.

## 1. 방식 비교

| 방식 | 무엇 | 의존성 | 핵심 동작 | 장점 | 단점 |
|---|---|---|---|---|---|
| ① DB 원자 UPDATE(현재) | 단일 DB 행 락 | 0 | 조건부 UPDATE | 이미 정합·단순 | 단일 DB 한정·앱단 직렬화 아님 |
| **② DIY SETNX+Lua** ★ | 직접 구현 | 0(기존 spring-data-redis) | `SET k token NX PX ttl` / 해제=Lua(내 토큰일 때만 DEL) | 메커니즘 학습·면접 단골·완전 제어 | 엣지(재진입·만료·해제안전)를 직접 책임 |
| ③ Spring `RedisLockRegistry` | 스프링 공식 추상화 | `spring-integration-redis` | `java.util.concurrent.locks.Lock` 반환·재진입·기본 60s 만료·spin(100ms)/pub-sub | "스프링 방식"·표준 Lock API·해제안전 내장 | 저지연용 아님·만료락 unlock 시 예외 |
| ④ Redisson `RLock` | 업계 표준 라이브러리 | `redisson-spring-boot-starter` | tryLock(wait,lease)·**watchdog**(기본 30s, 10s마다 자동 연장)·pub-sub | 기능 풍부·watchdog로 작업중 만료 방지·이력서 키워드 | 무거운 의존성·magic |
| ⑤ Redlock(멀티 마스터) | 다중 Redis 합의 | Redisson | N개 마스터 과반 획득 | HA Redis 대응 | 안전성 논쟁(Kleppmann↔antirez)·단일 Redis엔 과함 |

> ShedLock은 `@Scheduled` 중복 실행 방지(스케줄러)용 — 임계구역 락과 용도가 다르므로 제외.

## 2. 안전성 메모 (Redlock 논쟁)

- Kleppmann: Redlock은 시계 동기화·프로세스 일시정지 가정에 의존해 "correctness-critical"엔 불안. fencing token 권장.
- antirez(Redis 창시자): 실무적으로 충분, mid-tier 안전엔 유효.
- **우리 결론**: 정합성은 DB가 책임지므로 단일 Redis + 단순 SETNX 락이면 충분(Redlock 불필요).

## 3. 선택: ② DIY SETNX+Lua (포트로 추상화, opt-in)

근거:
- **학습 가치 1순위**: "Redis 분산락을 직접 구현"은 면접 단골. NX·TTL·왜 Lua로 해제하는지(내 토큰 확인+DEL의
  원자성)를 코드로 보여준다.
- **의존성 0 추가**: 이미 있는 `spring-data-redis`(`StringRedisTemplate`)만 사용.
- **추상화(`DistributedLock` 포트)**: outbox `EventPublisher`와 같은 포트-어댑터. 운영에서 Redisson/
  RedisLockRegistry로 바꾸려면 어댑터만 교체.
- **opt-in 토글**(`app.lock.provider=none|redis`, 기본 none): 캐시 provider 토글과 같은 결. 로컬/테스트는
  락 없이(DB 원자 방식 그대로), `redis`일 때만 분산락. 테스트 결정성·CI(무 Redis) 보존.

## 4. 구현 메모

- 획득: `redis.opsForValue().setIfAbsent(lockKey, token, leaseTtl)` = `SET k token NX PX ttl`(원자).
  - `token` = UUID(소유권 식별). `leaseTtl`로 보유자 사망 시 자동 해제(데드락 방지).
  - 대기: `waitTime`까지 짧은 간격 재시도(spin). 초과 시 503(잠시 후 다시).
- 해제: **Lua 원자 스크립트** — `if get(k)==token then del(k) end`. (get→del 사이 만료로 남의 락을 지우는 것 방지.)
- 적용: `claim` 임계구역을 락으로 감싸되 **DB 원자 UPDATE는 백스톱으로 유지**(락은 advisory).
- 한계: watchdog 없음(작업이 lease보다 길면 만료) → claim은 ms 단위라 안전. 운영 장수명 락은 Redisson watchdog 권장.

## 참고
- Spring Integration — Distributed Locks: https://docs.spring.io/spring-integration/reference/distributed-locks.html
- Redisson Locks: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- Redlock(antirez): https://redis.antirez.com/fundamental/redlock.html
- Kleppmann, How to do distributed locking: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
