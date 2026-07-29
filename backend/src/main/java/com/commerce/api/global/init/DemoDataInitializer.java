package com.commerce.api.global.init;

import com.commerce.api.recommendation.service.CoOccurrenceBatchService;
import com.commerce.api.recommendation.service.RecommendationBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 데모 부트스트랩 — 기동 직후 데모 데이터를 시드하고 추천 배치를 즉시 1회 돌린다.
 * {@code app.demo-seed.enabled=true}일 때만 등록된다(로컬 dev 기본 ON · 그 외 기본 OFF — {@link DemoDataSeeder} 참조).
 *
 * <p>순서: {@link DemoDataSeeder#seed()}(트랜잭션 — 커밋) → "나를 위한 추천" 배치 → "함께 산 상품" 배치.
 * 시드를 별도 빈(seeder)을 통해 호출해 프록시 경유 트랜잭션을 보장하고, 배치는 커밋된 데이터를 각자 트랜잭션으로 읽는다
 * (스케줄(@Scheduled)을 기다리지 않고 데모가 곧장 보이도록 수동 트리거 — POST /run과 같은 경로).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo-seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private final DemoDataSeeder seeder;
    private final DemoMoneyFlowSeeder moneyFlowSeeder;
    private final RecommendationBatchService recommendationBatchService;
    private final CoOccurrenceBatchService coOccurrenceBatchService;

    @Override
    public void run(String... args) {
        seeder.seed();
        // 돈 흐름은 시드 트랜잭션이 커밋된 뒤에 — 결제가 낙관락 재시도를 위해 자기 트랜잭션 경계를 직접 관리하므로
        // 바깥 트랜잭션에 묶으면 재시도가 깨진다(그래서 별도 호출이고, 이 시더는 @Transactional이 없다).
        moneyFlowSeeder.seed();
        int personalized = recommendationBatchService.run();
        int coOccurrence = coOccurrenceBatchService.run();
        log.info("[demo-seed] 완료 — 추천 {}건, 함께 산 상품 {}건 계산", personalized, coOccurrence);
    }
}
