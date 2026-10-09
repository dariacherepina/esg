package com.esg.score.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ScoreEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public ScoreEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(ScoreUpdatedEvent event) {
        rabbitTemplate.convertAndSend(MessagingConfig.EXCHANGE, MessagingConfig.SCORE_UPDATED_ROUTING_KEY, event);
    }
}
