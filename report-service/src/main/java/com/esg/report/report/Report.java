package com.esg.report.report;

import java.time.Instant;
import java.util.List;

/**
 * A sustainability report for one company. Immutable; a newer score produces a new report.
 *
 * @param scoreCalculatedAt when esg-score-service calculated the score (used to ignore older events)
 * @param generatedAt       when this service built the report
 */
public record Report(
        Long companyId,
        String companyName,
        double totalScore,
        Rating rating,
        List<String> recommendations,
        Instant scoreCalculatedAt,
        Instant generatedAt
) {
}
