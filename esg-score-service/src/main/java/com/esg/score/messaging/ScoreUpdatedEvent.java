package com.esg.score.messaging;

import java.time.Instant;

/**
 * Published to exchange "esg.events" with routing key "score.updated" after every calculation.
 * JSON: {"companyId":6,"companyName":"Voltara Renewables","totalScore":92.6,"calculatedAt":"2026-10-09T12:00:00Z"}
 */
public record ScoreUpdatedEvent(
        Long companyId,
        String companyName,
        double totalScore,
        Instant calculatedAt
) {
}
