package com.esg.score.client;

import com.esg.score.scoring.EsgInput;

/**
 * A company as returned by company-service (GET /api/companies/{id}).
 * Only the fields this service needs; unknown JSON fields are ignored.
 */
public record CompanyData(
        Long id,
        String name,
        String sector,
        double co2Emissions,
        double renewableEnergyPct,
        double waterConsumption,
        double boardDiversityPct,
        int corruptionIncidents,
        double transparencyScore
) {

    public EsgInput toEsgInput() {
        return new EsgInput(co2Emissions, renewableEnergyPct, waterConsumption,
                boardDiversityPct, corruptionIncidents, transparencyScore);
    }
}
