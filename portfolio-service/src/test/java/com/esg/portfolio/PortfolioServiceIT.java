package com.esg.portfolio;

import com.esg.portfolio.api.PortfolioResponse;
import com.esg.portfolio.client.ScoreClient;
import com.esg.portfolio.messaging.MessagingConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real HTTP, Postgres and RabbitMQ. esg-score-service is not running: events are published here as
 * raw JSON with the "scoreUpdated" type id — exactly the bytes esg-score-service sends — which tests
 * the cross-service contract rather than this service's own Java class.
 */
@Testcontainers
@ActiveProfiles("it")
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @MockitoBean
    private ScoreClient scoreClient;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void reset() {
        flyway.clean();
        flyway.migrate();
        amqpAdmin.purgeQueue(MessagingConfig.DEAD_LETTER_QUEUE, false);
        when(scoreClient.fetch(any())).thenReturn(Optional.empty());
    }

    /** Publishes like esg-score-service does: JSON body, __TypeId__ header, routing key score.updated. */
    private void publish(String json) {
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setHeader("__TypeId__", "scoreUpdated")
                .build();
        rabbitTemplate.send(MessagingConfig.EXCHANGE, MessagingConfig.SCORE_UPDATED_ROUTING_KEY, message);
    }

    private void publishScore(long companyId, String name, double total) {
        publish("""
                {"companyId":%d,"companyName":"%s","totalScore":%s,"calculatedAt":"2026-10-09T12:00:00Z"}
                """.formatted(companyId, name, total));
    }

    private Double aggregateOf(long portfolioId) {
        return rest.getForObject("/api/portfolios/" + portfolioId, PortfolioResponse.class).aggregateScore();
    }

    @Test
    void seedPortfoliosStartWithoutScores() {
        PortfolioResponse[] all = rest.getForObject("/api/portfolios", PortfolioResponse[].class);

        assertThat(all).extracting(PortfolioResponse::name)
                .containsExactly("Sustainable Leaders", "Balanced Mix", "Heavy Industry");
        assertThat(all).allSatisfy(p -> assertThat(p.aggregateScore()).isNull());
    }

    @Test
    void scoreUpdatedEventsRecalculateSeedPortfolios() {
        Map<Long, Double> seedScores = Map.of(
                1L, 95.06, 6L, 92.6, 11L, 96.49,               // Sustainable Leaders
                3L, 73.75, 8L, 55.97, 12L, 75.36, 17L, 71.08,  // Balanced Mix
                9L, 31.17, 10L, 21.03, 20L, 13.33);            // Heavy Industry
        seedScores.forEach((id, total) -> publishScore(id, "Company " + id, total));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(aggregateOf(1)).isEqualTo(94.75);
            assertThat(aggregateOf(2)).isEqualTo(69.04);
            assertThat(aggregateOf(3)).isEqualTo(20.99);
        });
    }

    @Test
    void malformedMessageEndsUpInDeadLetterQueue() {
        publish("{not json");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(MessagingConfig.DEAD_LETTER_QUEUE)).isNotNull());
    }

    @Test
    void crudRoundTripAndLateScore() {
        ResponseEntity<PortfolioResponse> created = rest.postForEntity("/api/portfolios", Map.of(
                "name", "Mixed Energy",
                "holdings", new Object[] {
                        Map.of("companyId", 6, "weight", 0.5),
                        Map.of("companyId", 10, "weight", 0.5)}), PortfolioResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = created.getBody().id();
        assertThat(created.getBody().aggregateScore()).isNull();

        // score arrives later via event -> portfolio is updated without any request to it
        publishScore(6, "Voltara Renewables", 92.6);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(aggregateOf(id)).isEqualTo(92.6));

        ResponseEntity<Void> deleted = rest.exchange("/api/portfolios/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForEntity("/api/portfolios/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void probesAndMetricsAreExposed() {
        publishScore(6, "Voltara Renewables", 92.6);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(aggregateOf(1)).isNotNull());

        assertThat(rest.getForObject("/actuator/health/readiness", String.class)).contains("\"status\":\"UP\"");
        assertThat(rest.getForObject("/actuator/prometheus", String.class))
                .contains("portfolio_score_events_total{")
                .contains("portfolio_aggregate_recalculations_total{")
                .contains("application=\"portfolio-service\"");
    }
}
