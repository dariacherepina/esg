package com.esg.score.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Fan-out via a topic exchange: this service only publishes to the exchange. Each consumer
 * (portfolio-service, report-service) declares and binds its OWN queue to "score.updated",
 * so every consumer receives every event.
 */
@Configuration
public class MessagingConfig {

    public static final String EXCHANGE = "esg.events";
    public static final String SCORE_UPDATED_ROUTING_KEY = "score.updated";

    /** Logical type name in the __TypeId__ header, so consumers need not know this service's Java class. */
    public static final String SCORE_UPDATED_TYPE_ID = "scoreUpdated";

    /** Declared on the broker automatically at startup (durable: survives a RabbitMQ restart). */
    @Bean
    public TopicExchange esgEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /** Send messages as JSON (Spring Boot's ObjectMapper writes Instant as ISO-8601 text). */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of(SCORE_UPDATED_TYPE_ID, ScoreUpdatedEvent.class));
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
