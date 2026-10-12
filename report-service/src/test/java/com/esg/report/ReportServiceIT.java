package com.esg.report;

import com.esg.report.messaging.MessagingConfig;
import com.esg.report.report.Rating;
import com.esg.report.report.Report;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real HTTP and RabbitMQ — and no database at all. Events are published as raw JSON with the
 * "scoreUpdated" type id, exactly as esg-score-service sends them.
 * A fresh application context per test resets the in-memory store.
 */
@Testcontainers
@ActiveProfiles("it")
@AutoConfigureObservability(tracing = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportServiceIT {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    private static final Map<Long, Double> SEED_TOTALS = Map.ofEntries(
            Map.entry(1L, 95.06), Map.entry(2L, 89.88), Map.entry(3L, 73.75), Map.entry(4L, 55.90),
            Map.entry(5L, 24.90), Map.entry(6L, 92.60), Map.entry(7L, 75.73), Map.entry(8L, 55.97),
            Map.entry(9L, 31.17), Map.entry(10L, 21.03), Map.entry(11L, 96.49), Map.entry(12L, 75.36),
            Map.entry(13L, 73.03), Map.entry(14L, 57.27), Map.entry(15L, 35.02), Map.entry(16L, 87.95),
            Map.entry(17L, 71.08), Map.entry(18L, 53.73), Map.entry(19L, 48.83), Map.entry(20L, 13.33));

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private void publish(String json) {
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setHeader("__TypeId__", "scoreUpdated")
                .build();
        rabbitTemplate.send(MessagingConfig.EXCHANGE, MessagingConfig.SCORE_UPDATED_ROUTING_KEY, message);
    }

    private void publishScore(long companyId, double total) {
        publish("""
                {"companyId":%d,"companyName":"Company %d","totalScore":%s,"calculatedAt":"2026-10-09T12:00:00Z"}
                """.formatted(companyId, companyId, total));
    }

    @Test
    void seedScoresProduceFiveReportsPerRating() {
        SEED_TOTALS.forEach(this::publishScore);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rest.getForObject("/api/reports", Report[].class)).hasSize(20));

        Report[] all = rest.getForObject("/api/reports", Report[].class);
        assertThat(all).extracting(Report::companyId).isSorted();
        Map<Rating, Long> perRating = Arrays.stream(all)
                .collect(Collectors.groupingBy(Report::rating, Collectors.counting()));
        assertThat(perRating).containsOnly(
                Map.entry(Rating.EXCELLENT, 5L), Map.entry(Rating.GOOD, 5L),
                Map.entry(Rating.FAIR, 5L), Map.entry(Rating.POOR, 5L));

        assertThat(rest.getForObject("/api/reports?rating=POOR", Report[].class))
                .extracting(Report::companyId).containsExactly(5L, 9L, 10L, 15L, 20L);
    }

    @Test
    void reportForSingleCompany() {
        publishScore(6, 92.6);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rest.getForEntity("/api/reports/6", String.class).getStatusCode()).isEqualTo(HttpStatus.OK));
        Report report = rest.getForObject("/api/reports/6", Report.class);
        assertThat(report.rating()).isEqualTo(Rating.EXCELLENT);
        assertThat(report.recommendations()).isNotEmpty();
        assertThat(rest.getForEntity("/api/reports/7", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedMessageEndsUpInDeadLetterQueue() {
        amqpAdmin.purgeQueue(MessagingConfig.DEAD_LETTER_QUEUE, false);
        publish("{not json");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(MessagingConfig.DEAD_LETTER_QUEUE)).isNotNull());
    }

    @Test
    void probesAndMetricsAreExposed() {
        publishScore(6, 92.6);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rest.getForEntity("/api/reports/6", String.class).getStatusCode()).isEqualTo(HttpStatus.OK));

        assertThat(rest.getForObject("/actuator/health/readiness", String.class)).contains("\"status\":\"UP\"");
        assertThat(rest.getForObject("/actuator/prometheus", String.class))
                .contains("reports_generated_total{")
                .contains("reports_stored{")
                .contains("application=\"report-service\"")
                .doesNotContain("hikaricp_");   // no database in this service
    }
}
