package com.esg.score.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns raw ESG values into pillar scores and a weighted total (all 0-100).
 * Pure Java on purpose: no Spring, no I/O, deterministic — testable in isolation.
 *
 * <p>Each raw value is normalised linearly (min-max) onto 0-100 using the fixed ranges defined by
 * company-service, inverted where lower is better:
 * <pre>
 * Environmental = avg( 100 - co2/10,  renewable%,  100 - water/50 )
 * Social        = avg( min(diversity% * 2, 100),  100 - incidents*10 )   // parity at 50% scores 100
 * Governance    = transparency
 * Total         = E*0.40 + S*0.35 + G*0.25
 * </pre>
 */
public class EsgScoreCalculator {

    static final double WEIGHT_ENVIRONMENTAL = 0.40;
    static final double WEIGHT_SOCIAL = 0.35;
    static final double WEIGHT_GOVERNANCE = 0.25;

    private static final double MAX_CO2 = 1000;
    private static final double MAX_WATER = 5000;
    private static final double MAX_INCIDENTS = 10;
    private static final double DIVERSITY_PARITY_PCT = 50;

    public EsgScore calculate(EsgInput input) {
        double co2 = lowerIsBetter(input.co2Emissions(), MAX_CO2);
        double renewables = clamp(input.renewableEnergyPct());
        double water = lowerIsBetter(input.waterConsumption(), MAX_WATER);
        double environmental = (co2 + renewables + water) / 3;

        double diversity = clamp(input.boardDiversityPct() / DIVERSITY_PARITY_PCT * 100);
        double integrity = lowerIsBetter(input.corruptionIncidents(), MAX_INCIDENTS);
        double social = (diversity + integrity) / 2;

        double governance = clamp(input.transparencyScore());

        double total = environmental * WEIGHT_ENVIRONMENTAL
                + social * WEIGHT_SOCIAL
                + governance * WEIGHT_GOVERNANCE;

        return new EsgScore(round(environmental), round(social), round(governance), round(total));
    }

    /** 0 -> 100, max -> 0. */
    private static double lowerIsBetter(double value, double max) {
        return clamp(100 - value / max * 100);
    }

    private static double clamp(double score) {
        return Math.max(0, Math.min(100, score));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
