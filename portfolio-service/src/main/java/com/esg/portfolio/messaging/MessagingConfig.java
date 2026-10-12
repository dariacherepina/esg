package com.esg.portfolio.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * This service's OWN queue, bound to score.updated on the shared exchange. report-service binds
 * a separate queue the same way, so both receive every event (fan-out).
 *
 * <pre>
 * esg.events ──score.updated──► portfolio-service.score-updated ──(after 3 failed attempts)──►
 *                                                     portfolio-service.score-updated.dlq
 * </pre>
 */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "esg.events";
    public static final String SCORE_UPDATED_ROUTING_KEY = "score.updated";
    public static final String QUEUE = "portfolio-service.score-updated";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";

    /** Declared here too (idempotent), so the binding works even if esg-score-service never ran. */
    @Bean
    public TopicExchange esgEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /** Durable: survives broker restarts; events published while this service is down wait here. */
    @Bean
    public Queue scoreUpdatedQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")                 // default exchange routes by queue name...
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE) // ...so rejected messages land in the DLQ
                .build();
    }

    /** Messages that could not be processed; inspect them in the management UI. */
    @Bean
    public Queue scoreUpdatedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding scoreUpdatedBinding(Queue scoreUpdatedQueue, TopicExchange esgEventsExchange) {
        return BindingBuilder.bind(scoreUpdatedQueue).to(esgEventsExchange).with(SCORE_UPDATED_ROUTING_KEY);
    }

    /** Maps the logical type id "scoreUpdated" (set by the publisher) to this service's own class. */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of("scoreUpdated", ScoreUpdatedEvent.class));
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
