package com.esg.report.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Builds a report from a score. Pure Java: no Spring, no I/O, fixed texts — the same input always
 * gives the same report, so experiment runs are reproducible.
 */
public class ReportGenerator {

    private static final Map<Rating, List<String>> RECOMMENDATIONS = Map.of(
            Rating.EXCELLENT, List.of(
                    "Maintain current ESG practices and disclosure standards",
                    "Obtain third-party assurance of ESG data",
                    "Share best practices with sector peers and suppliers"),
            Rating.GOOD, List.of(
                    "Set science-based emission reduction targets",
                    "Increase the share of renewable energy",
                    "Strengthen board diversity policies"),
            Rating.FAIR, List.of(
                    "Develop a decarbonisation roadmap with interim targets",
                    "Improve water efficiency in operations",
                    "Expand anti-corruption training and controls",
                    "Publish more detailed sustainability disclosures"),
            Rating.POOR, List.of(
                    "Commission an independent ESG risk assessment",
                    "Prioritise immediate reductions in emissions and water use",
                    "Establish board-level oversight of ESG risks",
                    "Implement a whistleblowing and anti-corruption programme"));

    public Report generate(Long companyId, String companyName, double totalScore,
                           Instant scoreCalculatedAt, Instant generatedAt) {
        Rating rating = Rating.fromScore(totalScore);
        return new Report(companyId, companyName, totalScore, rating, RECOMMENDATIONS.get(rating),
                scoreCalculatedAt, generatedAt);
    }
}
