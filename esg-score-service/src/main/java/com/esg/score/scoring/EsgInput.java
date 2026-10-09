package com.esg.score.scoring;

/**
 * Raw ESG values of one company, in the units defined by company-service.
 *
 * @param co2Emissions        tonnes CO2 per EUR 1M revenue (0-1000, lower is better)
 * @param renewableEnergyPct  % of energy from renewables (0-100, higher is better)
 * @param waterConsumption    m3 per EUR 1M revenue (0-5000, lower is better)
 * @param boardDiversityPct   % underrepresented board members (0-100, higher is better up to parity at 50)
 * @param corruptionIncidents confirmed cases in the last 3 years (0-10, lower is better)
 * @param transparencyScore   disclosure quality rating (0-100, higher is better)
 */
public record EsgInput(
        double co2Emissions,
        double renewableEnergyPct,
        double waterConsumption,
        double boardDiversityPct,
        int corruptionIncidents,
        double transparencyScore
) {
}
