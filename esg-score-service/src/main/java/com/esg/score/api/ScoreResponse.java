package com.esg.score.api;

import com.esg.score.domain.CompanyScore;

import java.time.Instant;

public record ScoreResponse(
        Long companyId,
        String companyName,
        double environmental,
        double social,
        double governance,
        double total,
        Instant calculatedAt
) {

    public static ScoreResponse from(CompanyScore score) {
        return new ScoreResponse(score.getCompanyId(), score.getCompanyName(), score.getEnvironmental(),
                score.getSocial(), score.getGovernance(), score.getTotal(), score.getCalculatedAt());
    }
}
