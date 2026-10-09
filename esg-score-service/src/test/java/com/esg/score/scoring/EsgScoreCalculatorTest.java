package com.esg.score.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EsgScoreCalculatorTest {

    private final EsgScoreCalculator calculator = new EsgScoreCalculator();

    @Test
    void bestPossibleValuesScore100() {
        EsgScore score = calculator.calculate(new EsgInput(0, 100, 0, 50, 0, 100));

        assertThat(score.environmental()).isEqualTo(100.0);
        assertThat(score.social()).isEqualTo(100.0);
        assertThat(score.governance()).isEqualTo(100.0);
        assertThat(score.total()).isEqualTo(100.0);
    }

    @Test
    void worstPossibleValuesScore0() {
        EsgScore score = calculator.calculate(new EsgInput(1000, 0, 5000, 0, 10, 0));

        assertThat(score.environmental()).isZero();
        assertThat(score.social()).isZero();
        assertThat(score.governance()).isZero();
        assertThat(score.total()).isZero();
    }

    @Test
    void environmentalIsAverageOfCo2RenewablesAndWater() {
        // co2 500 -> 50, renewables 80 -> 80, water 1000 -> 80  => (50 + 80 + 80) / 3 = 70
        EsgScore score = calculator.calculate(new EsgInput(500, 80, 1000, 0, 0, 0));

        assertThat(score.environmental()).isEqualTo(70.0);
    }

    @Test
    void socialIsAverageOfDiversityAndIntegrity() {
        // diversity 30% -> 60 (parity at 50%), 2 incidents -> 80  => (60 + 80) / 2 = 70
        EsgScore score = calculator.calculate(new EsgInput(0, 0, 0, 30, 2, 0));

        assertThat(score.social()).isEqualTo(70.0);
    }

    @Test
    void boardDiversityAboveParityDoesNotExceed100() {
        EsgScore atParity = calculator.calculate(new EsgInput(0, 0, 0, 50, 0, 0));
        EsgScore aboveParity = calculator.calculate(new EsgInput(0, 0, 0, 80, 0, 0));

        assertThat(aboveParity.social()).isEqualTo(atParity.social()).isEqualTo(100.0);
    }

    @Test
    void totalAppliesPillarWeights() {
        // E = 100, S = 0, G = 0 -> 40; E = 0, S = 100, G = 0 -> 35; E = 0, S = 0, G = 100 -> 25
        assertThat(calculator.calculate(new EsgInput(0, 100, 0, 0, 10, 0)).total()).isEqualTo(40.0);
        assertThat(calculator.calculate(new EsgInput(1000, 0, 5000, 50, 0, 0)).total()).isEqualTo(35.0);
        assertThat(calculator.calculate(new EsgInput(1000, 0, 5000, 0, 10, 100)).total()).isEqualTo(25.0);
    }

    @Test
    void outOfRangeInputIsClampedTo0To100() {
        EsgScore score = calculator.calculate(new EsgInput(2000, 150, 9000, -5, 20, 120));

        assertThat(score.environmental()).isBetween(0.0, 100.0);
        assertThat(score.social()).isBetween(0.0, 100.0);
        assertThat(score.governance()).isEqualTo(100.0);
        assertThat(score.total()).isBetween(0.0, 100.0);
    }

    @Test
    void scoresAreRoundedToTwoDecimals() {
        EsgScore score = calculator.calculate(new EsgInput(15, 95, 120, 45, 0, 92));

        assertThat(score.environmental()).isEqualTo(97.03); // 97.0333...
    }

    /**
     * Expected totals for the 20 seed companies of company-service, computed independently.
     * Bands (report-service): Excellent >= 80, Good >= 60, Fair >= 40, Poor < 40 -> 5 / 5 / 5 / 5.
     */
    @ParameterizedTest(name = "seed company {0} -> {7}")
    @CsvSource({
            // id, co2, renewable, water, diversity, incidents, transparency, expected total
            "1,  15,  95, 120,  45, 0, 92, 95.06",   // Excellent
            "2,  25,  85, 200,  40, 0, 85, 89.88",   // Excellent
            "3,  120, 55, 900,  30, 1, 70, 73.75",   // Good
            "4,  260, 30, 1800, 20, 2, 50, 55.90",   // Fair
            "5,  520, 8,  4200, 8,  6, 22, 24.90",   // Poor
            "6,  40,  98, 300,  42, 0, 88, 92.60",   // Excellent
            "7,  180, 65, 1200, 35, 1, 72, 75.73",   // Good
            "8,  450, 35, 2200, 25, 2, 55, 55.97",   // Fair
            "9,  780, 6,  3900, 15, 4, 35, 31.17",   // Poor
            "10, 920, 3,  4600, 10, 5, 25, 21.03",   // Poor
            "11, 5,   90, 60,   48, 0, 95, 96.49",   // Excellent
            "12, 12,  45, 150,  30, 1, 68, 75.36",   // Good
            "13, 18,  40, 200,  28, 1, 65, 73.03",   // Good
            "14, 30,  15, 300,  18, 3, 45, 57.27",   // Fair
            "15, 45,  5,  400,  6,  8, 15, 35.02",   // Poor
            "16, 90,  85, 600,  40, 0, 85, 87.95",   // Excellent
            "17, 200, 55, 1500, 30, 1, 70, 71.08",   // Good
            "18, 480, 25, 2800, 26, 2, 58, 53.73",   // Fair
            "19, 650, 20, 3200, 22, 2, 60, 48.83",   // Fair
            "20, 980, 4,  4800, 5,  7, 20, 13.33",   // Poor
    })
    void seedCompaniesProduceExpectedTotals(int id, double co2, double renewable, double water,
                                            double diversity, int incidents, double transparency,
                                            double expectedTotal) {
        EsgScore score = calculator.calculate(new EsgInput(co2, renewable, water, diversity, incidents, transparency));

        assertThat(score.total()).isEqualTo(expectedTotal);
    }
}
