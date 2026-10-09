package com.esg.score;

import com.esg.score.api.ScoreResponse;
import com.esg.score.client.CompanyClient;
import com.esg.score.client.CompanyData;
import com.esg.score.domain.CompanyScoreRepository;
import com.esg.score.messaging.MessagingConfig;
import com.esg.score.messaging.ScoreUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Real HTTP server, real Postgres, real RabbitMQ (all in Docker via Testcontainers).
 * Only company-service is replaced by a mock — it is tested separately in CompanyClientTest.
 */
@Testcontainers
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScoreServiceIT {

    private static final String TEST_QUEUE = "it.score-updated";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @MockitoBean
    private CompanyClient companyClient;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private CompanyScoreRepository repository;

    private static final CompanyData VOLTARA =
            new CompanyData(6L, "Voltara Renewables", "ENERGY", 40, 98, 300, 42, 0, 88);
    private static final CompanyData LUMINA =
            new CompanyData(1L, "Lumina Cloud Systems", "TECHNOLOGY", 15, 95, 120, 45, 0, 92);

    /** Plays the role of a consumer (like portfolio-service): its own queue bound to score.updated. */
    @BeforeEach
    void setUp() {
        repository.deleteAll();
        TopicExchange exchange = new TopicExchange(MessagingConfig.EXCHANGE);
        Queue queue = new Queue(TEST_QUEUE, false);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(MessagingConfig.SCORE_UPDATED_ROUTING_KEY));
        amqpAdmin.purgeQueue(TEST_QUEUE, false);
    }

    @Test
    void calculatePersistsScoreAndPublishesEvent() {
        when(companyClient.fetch(6L)).thenReturn(VOLTARA);

        ResponseEntity<ScoreResponse> response = rest.postForEntity("/api/scores/6", null, ScoreResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().total()).isEqualTo(92.6);

        // stored in Postgres
        assertThat(repository.findById(6L)).get().satisfies(s -> assertThat(s.getTotal()).isEqualTo(92.6));

        // delivered through RabbitMQ and deserialised via the logical type id
        Object message = rabbitTemplate.receiveAndConvert(TEST_QUEUE, 5000);
        assertThat(message).isInstanceOf(ScoreUpdatedEvent.class);
        ScoreUpdatedEvent event = (ScoreUpdatedEvent) message;
        assertThat(event.companyId()).isEqualTo(6L);
        assertThat(event.companyName()).isEqualTo("Voltara Renewables");
        assertThat(event.totalScore()).isEqualTo(92.6);
        assertThat(event.calculatedAt()).isEqualTo(response.getBody().calculatedAt());
    }

    @Test
    void recalculationOverwritesInsteadOfAddingRows() {
        when(companyClient.fetch(6L)).thenReturn(VOLTARA);

        rest.postForEntity("/api/scores/6", null, ScoreResponse.class);
        rest.postForEntity("/api/scores/6", null, ScoreResponse.class);

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void calculateAllPublishesOneEventPerCompany() {
        when(companyClient.fetchAll()).thenReturn(List.of(LUMINA, VOLTARA));

        rest.postForEntity("/api/scores", null, ScoreResponse[].class);

        ScoreUpdatedEvent first = (ScoreUpdatedEvent) rabbitTemplate.receiveAndConvert(TEST_QUEUE, 5000);
        ScoreUpdatedEvent second = (ScoreUpdatedEvent) rabbitTemplate.receiveAndConvert(TEST_QUEUE, 5000);
        assertThat(List.of(first.companyId(), second.companyId())).containsExactly(1L, 6L);

        ScoreResponse[] stored = rest.getForObject("/api/scores", ScoreResponse[].class);
        assertThat(stored).extracting(ScoreResponse::companyId).containsExactly(1L, 6L);
    }

    @Test
    void probesAndMetricsAreExposed() {
        when(companyClient.fetch(6L)).thenReturn(VOLTARA);
        rest.postForEntity("/api/scores/6", null, ScoreResponse.class);

        assertThat(rest.getForObject("/actuator/health/readiness", String.class)).contains("\"status\":\"UP\"");
        assertThat(rest.getForObject("/actuator/prometheus", String.class))
                .contains("esg_scores_calculated_total{")
                .contains("jvm_memory_used_bytes{")
                .contains("application=\"esg-score-service\"");
    }
}
