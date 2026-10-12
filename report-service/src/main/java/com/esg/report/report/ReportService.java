package com.esg.report.report;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ReportService {

    private final ReportGenerator generator;
    private final ReportStore store;
    private final Clock clock;
    private final Counter reportsGenerated;

    public ReportService(ReportGenerator generator, ReportStore store, Clock clock, MeterRegistry meterRegistry) {
        this.generator = generator;
        this.store = store;
        this.clock = clock;
        this.reportsGenerated = Counter.builder("reports.generated")
                .description("Reports generated from score.updated events")
                .register(meterRegistry);
        Gauge.builder("reports.stored", store, ReportStore::size)
                .description("Reports currently held in memory")
                .register(meterRegistry);
    }

    /** @return true if a new report was stored (false for duplicate or older events) */
    public boolean onScoreUpdated(Long companyId, String companyName, double totalScore, Instant calculatedAt) {
        Report report = generator.generate(companyId, companyName, totalScore, calculatedAt, clock.instant());
        boolean stored = store.saveIfNewer(report);
        if (stored) {
            reportsGenerated.increment();
        }
        return stored;
    }

    public List<Report> findAll(Rating rating) {
        return store.findAll(rating);
    }

    public Report findByCompanyId(Long companyId) {
        return store.find(companyId).orElseThrow(() -> new ReportNotFoundException(companyId));
    }
}
