package com.esg.company.api;

import com.esg.company.domain.Company;
import com.esg.company.domain.Sector;

/** JSON representation of a company returned by the API. */
public record CompanyResponse(
        Long id,
        String name,
        Sector sector,
        double co2Emissions,
        double renewableEnergyPct,
        double waterConsumption,
        double boardDiversityPct,
        int corruptionIncidents,
        double transparencyScore
) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getName(),
                company.getSector(),
                company.getCo2Emissions(),
                company.getRenewableEnergyPct(),
                company.getWaterConsumption(),
                company.getBoardDiversityPct(),
                company.getCorruptionIncidents(),
                company.getTransparencyScore());
    }
}
