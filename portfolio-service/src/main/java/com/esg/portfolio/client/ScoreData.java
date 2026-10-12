package com.esg.portfolio.client;

import java.time.Instant;

/** A score as returned by esg-score-service (GET /api/scores/{companyId}); other fields are ignored. */
public record ScoreData(
        Long companyId,
        String companyName,
        double total,
        Instant calculatedAt
) {
}
