package com.commerce.api.global.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 토폴로지·설정 — {@code outbox.publisher=rabbit}일 때만 활성.
 *
 * <p>토픽 익스체인지 하나({@link #EVENTS_EXCHANGE})에 이벤트를 라우팅 키 = eventType으로 발행하고,
 * 알림 큐({@link #NOTIFICATIONS_QUEUE})가 {@code PAYMENT_COMPLETED}를 구독한다(소비자가 분리 수신).
 *
 * <p>메시지는 JSON({@link Jackson2JsonMessageConverter})으로 직렬화 — {@link OutboxMessage} 레코드가 본문.
 * (스프링 부트가 이 MessageConverter 빈을 RabbitTemplate·리스너 팩토리에 자동 연결한다.)
 *
 * <p>{@code spring.rabbitmq.dynamic=false}로 부트의 자동 RabbitAdmin을 꺼 두었으므로(기본 in-process 모드에서
 * 헛된 연결 방지), rabbit 모드에선 여기서 {@link RabbitAdmin}을 직접 만들어 위 익스체인지/큐/바인딩을 선언한다.
 */
@Configuration
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "rabbit")
public class RabbitConfig {

    /** 이벤트 토픽 익스체인지 — 모든 도메인 이벤트가 여기로 발행된다(라우팅 키 = eventType). */
    public static final String EVENTS_EXCHANGE = "commerce.events";
    /** 알림 소비 큐 — PAYMENT_COMPLETED를 받아 알림 기록(NotificationLog)으로 소비. */
    public static final String NOTIFICATIONS_QUEUE = "commerce.notifications";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);   // durable=true(재시작 보존), autoDelete=false
    }

    @Bean
    Queue notificationsQueue() {
        return new Queue(NOTIFICATIONS_QUEUE, true);   // durable=true
    }

    /** 알림 큐를 결제완료 이벤트에 바인딩(라우팅 키 = eventType). 새 소비자가 생기면 큐·바인딩을 추가하면 된다. */
    @Bean
    Binding notificationsBinding(Queue notificationsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(eventsExchange).with("PAYMENT_COMPLETED");
    }

    /** 본문을 JSON으로 직렬화/역직렬화 — OutboxMessage 레코드가 그대로 오간다(자바 직렬화 대신). */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** 익스체인지/큐/바인딩을 브로커에 선언 — 자동 RabbitAdmin을 껐으므로(dynamic=false) 직접 둔다. */
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
