package com.commerce.api.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * in-process 이벤트 발행 어댑터 — 외부 브로커 없이 같은 프로세스의 핸들러로 디스패치한다(기본값).
 *
 * <p>{@code outbox.publisher=in-process}(미설정이면 기본)일 때 활성. {@code rabbit}이면 대신
 * {@link RabbitEventPublisher}가 활성화되어 RabbitMQ로 발행한다 — 결제·폴러 코드는 {@link EventPublisher}
 * 포트에만 의존하므로 어느 쪽이든 변경이 없다(포트-어댑터·DIP).
 *
 * <p>발행 = 같은 프로세스에서 핸들러 호출이라 {@link OutboxProcessor#publish}의 트랜잭션 안에서 동기 실행된다
 * (핸들러 부수효과와 PUBLISHED가 원자적). 핸들러가 던지면 예외가 폴러까지 전파되어 재시도된다.
 */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "in-process", matchIfMissing = true)
@RequiredArgsConstructor
public class InProcessEventPublisher implements EventPublisher {

    private final EventDispatcher dispatcher;

    @Override
    public void publish(OutboxEvent event) {
        dispatcher.dispatch(event);
    }
}
