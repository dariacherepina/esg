package com.esg.portfolio.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioTest {

    private static final Instant T1 = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-09T13:00:00Z");

    private static Portfolio portfolio(Holding... holdings) {
        Portfolio portfolio = new Portfolio("Test");
        portfolio.replaceHoldings(List.of(holdings));
        return portfolio;
    }

    @Test
    void aggregateIsNullWhileNoHoldingHasAScore() {
        Portfolio portfolio = portfolio(new Holding(1L, 0.5), new Holding(2L, 0.5));

        assertThat(portfolio.getAggregateScore()).isNull();
    }

    @Test
    void aggregateIsWeightedAverageOfHoldingScores() {
        Portfolio portfolio = portfolio(new Holding(1L, 0.6), new Holding(6L, 0.4));

        portfolio.applyScore(1L, "Lumina Cloud Systems", 95.06, T1);
        portfolio.applyScore(6L, "Voltara Renewables", 92.6, T1);

        // (0.6 * 95.06 + 0.4 * 92.6) / 1.0 = 57.036 + 37.04 = 94.076
        assertThat(portfolio.getAggregateScore()).isEqualTo(94.08);
    }

    @Test
    void holdingsWithoutScoreAreLeftOutOfTheAverage() {
        Portfolio portfolio = portfolio(new Holding(1L, 0.3), new Holding(2L, 0.7));

        portfolio.applyScore(1L, "A", 80.0, T1);

        // only holding 1 is known: 0.3 * 80 / 0.3 = 80
        assertThat(portfolio.getAggregateScore()).isEqualTo(80.0);
    }

    @Test
    void weightsNeedNotSumToOne() {
        Portfolio portfolio = portfolio(new Holding(1L, 0.2), new Holding(2L, 0.2));

        portfolio.applyScore(1L, "A", 90.0, T1);
        portfolio.applyScore(2L, "B", 50.0, T1);

        assertThat(portfolio.getAggregateScore()).isEqualTo(70.0);
    }

    @Test
    void applyScoreUpdatesNameScoreAndTimestamp() {
        Portfolio portfolio = portfolio(new Holding(6L, 1.0));

        boolean changed = portfolio.applyScore(6L, "Voltara Renewables", 92.6, T1);

        assertThat(changed).isTrue();
        Holding holding = portfolio.getHoldings().getFirst();
        assertThat(holding.getCompanyName()).isEqualTo("Voltara Renewables");
        assertThat(holding.getEsgScore()).isEqualTo(92.6);
        assertThat(holding.getScoreCalculatedAt()).isEqualTo(T1);
    }

    @Test
    void olderScoreIsIgnored() {
        Portfolio portfolio = portfolio(new Holding(6L, 1.0));
        portfolio.applyScore(6L, "Voltara Renewables", 92.6, T2);

        boolean changed = portfolio.applyScore(6L, "Voltara Renewables", 10.0, T1);

        assertThat(changed).isFalse();
        assertThat(portfolio.getAggregateScore()).isEqualTo(92.6);
    }

    @Test
    void scoreForCompanyNotInPortfolioChangesNothing() {
        Portfolio portfolio = portfolio(new Holding(6L, 1.0));

        assertThat(portfolio.applyScore(99L, "Other", 50.0, T1)).isFalse();
        assertThat(portfolio.getAggregateScore()).isNull();
    }

    @Test
    void replacingHoldingsRecalculatesAggregate() {
        Portfolio portfolio = portfolio(new Holding(1L, 1.0));
        portfolio.applyScore(1L, "A", 80.0, T1);

        portfolio.replaceHoldings(List.of(new Holding(2L, 1.0)));

        assertThat(portfolio.getAggregateScore()).isNull();
        assertThat(portfolio.getHoldings()).extracting(Holding::getCompanyId).containsExactly(2L);
    }

    @Test
    void duplicateCompanyIsRejected() {
        assertThatThrownBy(() -> portfolio(new Holding(1L, 0.5), new Holding(1L, 0.3)))
                .isInstanceOf(InvalidPortfolioException.class)
                .hasMessageContaining("company 1");
    }

    @Test
    void weightsAboveOneInTotalAreRejected() {
        assertThatThrownBy(() -> portfolio(new Holding(1L, 0.7), new Holding(2L, 0.4)))
                .isInstanceOf(InvalidPortfolioException.class)
                .hasMessageContaining("1.1");
    }

    @Test
    void weightsSummingExactlyToOneAreAcceptedDespiteFloatingPoint() {
        // 0.1 + 0.2 + 0.7 is 1.0000000000000002 in double arithmetic
        Portfolio portfolio = portfolio(new Holding(1L, 0.1), new Holding(2L, 0.2), new Holding(3L, 0.7));

        assertThat(portfolio.getHoldings()).hasSize(3);
    }
}
