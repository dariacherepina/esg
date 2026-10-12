package com.esg.report.messaging;

import java.time.Instant;

/**
 * This service's own copy of the score.updated event published by esg-score-service.
 * No Java code is shared between services: the JSON shape and the type id "scoreUpdated" are the contract.
 */
public record ScoreUpdatedEvent(
        Long companyId,
        String companyName,
        double totalScore,
        Instant calculatedAt
) {
}
