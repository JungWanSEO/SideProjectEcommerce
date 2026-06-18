package com.commerce.api.global.outbox;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 이벤트 → 핸들러 디스패치 (발행 경로 공유).
 *
 * <p>이벤트 타입별로 {@link OutboxEventHandler}들을 모아두고, 타입이 맞는 핸들러를 순서대로 호출한다(팬아웃).
 * in-process 발행({@link InProcessEventPublisher})과 RabbitMQ 소비({@link OutboxEventConsumer}) 둘 다
 * 이 디스패처를 거쳐 <b>동일한 핸들러 로직</b>을 탄다(중복 제거). 구독자가 없으면 no-op.
 */
@Component
public class EventDispatcher {

    private final Map<String, List<OutboxEventHandler>> handlersByType;

    public EventDispatcher(List<OutboxEventHandler> handlers) {
        // 스프링이 모든 OutboxEventHandler 빈을 주입 → eventType으로 그룹핑
        this.handlersByType = handlers.stream().collect(Collectors.groupingBy(OutboxEventHandler::eventType));
    }

    /** 이벤트 타입에 맞는 핸들러들을 호출한다. 핸들러가 던지면 호출자(발행/소비)로 전파된다(재시도). */
    public void dispatch(OutboxEvent event) {
        for (OutboxEventHandler handler : handlersByType.getOrDefault(event.getEventType(), List.of())) {
            handler.handle(event);
        }
    }
}
