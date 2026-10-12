package com.esg.report.report;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {

    private static final Instant CALCULATED = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:05Z");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ReportService service = new ReportService(new ReportGenerator(), new ReportStore(),
            Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

    @Test
    void scoreUpdateCreatesReport() {
        assertThat(service.onScoreUpdated(6L, "Voltara Renewables", 92.6, CALCULATED)).isTrue();

        Report report = service.findByCompanyId(6L);
        assertThat(report.rating()).isEqualTo(Rating.EXCELLENT);
        assertThat(report.generatedAt()).isEqualTo(NOW);
        assertThat(meterRegistry.counter("reports.generated").count()).isEqualTo(1);
        assertThat(meterRegistry.get("reports.stored").gauge().value()).isEqualTo(1);
    }

    @Test
    void duplicateEventIsNotCountedTwice() {
        service.onScoreUpdated(6L, "Voltara Renewables", 92.6, CALCULATED);

        assertThat(service.onScoreUpdated(6L, "Voltara Renewables", 92.6, CALCULATED)).isFalse();
        assertThat(meterRegistry.counter("reports.generated").count()).isEqualTo(1);
    }

    @Test
    void unknownCompanyThrows() {
        assertThatThrownBy(() -> service.findByCompanyId(99L)).isInstanceOf(ReportNotFoundException.class);
    }
}
