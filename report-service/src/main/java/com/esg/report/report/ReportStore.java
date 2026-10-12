package com.esg.report.report;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest report per company, held in memory only — lost on restart by design (the service is
 * stateless and the lightest in the system). Thread-safe: the listener writes while HTTP threads read.
 */
@Component
public class ReportStore {

    private final Map<Long, Report> reports = new ConcurrentHashMap<>();

    /**
     * Stores the report unless an equal or newer one exists (events can arrive twice or out of order).
     * compute() runs atomically per key, so two concurrent events for one company can't interleave.
     *
     * @return true if stored
     */
    public boolean saveIfNewer(Report report) {
        boolean[] stored = {false};
        reports.compute(report.companyId(), (id, existing) -> {
            if (existing != null && !report.scoreCalculatedAt().isAfter(existing.scoreCalculatedAt())) {
                return existing;
            }
            stored[0] = true;
            return report;
        });
        return stored[0];
    }

    public Optional<Report> find(Long companyId) {
        return Optional.ofNullable(reports.get(companyId));
    }

    /** Ordered by company id so responses are identical across runs; rating null = all. */
    public List<Report> findAll(Rating rating) {
        return reports.values().stream()
                .filter(report -> rating == null || report.rating() == rating)
                .sorted(Comparator.comparing(Report::companyId))
                .toList();
    }

    public int size() {
        return reports.size();
    }
}
