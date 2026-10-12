package com.esg.report.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    private static final Instant CALCULATED = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:01Z");

    private final ReportGenerator generator = new ReportGenerator();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "0,      POOR",
            "39.99,  POOR",
            "40,     FAIR",
            "59.99,  FAIR",
            "60,     GOOD",
            "79.99,  GOOD",
            "80,     EXCELLENT",
            "100,    EXCELLENT",
    })
    void ratingBoundaries(double score, Rating expected) {
        assertThat(Rating.fromScore(score)).isEqualTo(expected);
    }

    @Test
    void reportContainsCompanyScoreRatingAndTimestamps() {
        Report report = generator.generate(6L, "Voltara Renewables", 92.6, CALCULATED, NOW);

        assertThat(report.companyId()).isEqualTo(6L);
        assertThat(report.companyName()).isEqualTo("Voltara Renewables");
        assertThat(report.totalScore()).isEqualTo(92.6);
        assertThat(report.rating()).isEqualTo(Rating.EXCELLENT);
        assertThat(report.scoreCalculatedAt()).isEqualTo(CALCULATED);
        assertThat(report.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void everyRatingHasItsOwnNonEmptyRecommendations() {
        Map<Rating, Report> byRating = Arrays.stream(Rating.values()).collect(Collectors.toMap(
                Function.identity(),
                r -> generator.generate(1L, "X", r.lowerBound(), CALCULATED, NOW)));

        assertThat(byRating.values()).allSatisfy(report -> assertThat(report.recommendations()).isNotEmpty());
        assertThat(byRating.get(Rating.POOR).recommendations())
                .doesNotContainAnyElementsOf(byRating.get(Rating.EXCELLENT).recommendations());
    }

    @Test
    void sameInputGivesSameReport() {
        assertThat(generator.generate(6L, "Voltara Renewables", 92.6, CALCULATED, NOW))
                .isEqualTo(generator.generate(6L, "Voltara Renewables", 92.6, CALCULATED, NOW));
    }

    /** The 20 seed company totals from esg-score-service split evenly over the four ratings. */
    @Test
    void seedCompaniesSpreadFiveFiveFiveFive() {
        double[] seedTotals = {95.06, 89.88, 73.75, 55.90, 24.90, 92.60, 75.73, 55.97, 31.17, 21.03,
                96.49, 75.36, 73.03, 57.27, 35.02, 87.95, 71.08, 53.73, 48.83, 13.33};

        Map<Rating, Long> counts = DoubleStream.of(seedTotals).mapToObj(Rating::fromScore)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(counts).containsOnly(
                Map.entry(Rating.EXCELLENT, 5L), Map.entry(Rating.GOOD, 5L),
                Map.entry(Rating.FAIR, 5L), Map.entry(Rating.POOR, 5L));
    }
}
