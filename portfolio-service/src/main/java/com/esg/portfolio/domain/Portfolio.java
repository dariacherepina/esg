package com.esg.portfolio.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An investment portfolio. Its aggregate ESG score is the weighted average of the scores of its
 * holdings and is recalculated whenever holdings or their scores change.
 */
@Entity
@Table(name = "portfolios")
public class Portfolio {

    /** Tolerance for floating-point sums like 0.1 + 0.2 + 0.7 = 1.0000000000000002. */
    private static final double WEIGHT_TOLERANCE = 1e-9;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** null while no holding has a score yet. */
    @Column(name = "aggregate_score")
    private Double aggregateScore;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("companyId ASC")
    private List<Holding> holdings = new ArrayList<>();

    protected Portfolio() {
        // required by JPA
    }

    public Portfolio(String name) {
        this.name = name;
    }

    /** Replaces all holdings (PUT semantics) after validating them. */
    public void replaceHoldings(List<Holding> newHoldings) {
        validate(newHoldings);
        holdings.clear();
        newHoldings.forEach(holding -> {
            holding.attachTo(this);
            holdings.add(holding);
        });
        recalculateAggregate();
    }

    /**
     * Applies a new score to the holding of the given company, if this portfolio holds it.
     *
     * @return true if the portfolio changed
     */
    public boolean applyScore(Long companyId, String companyName, double score, Instant calculatedAt) {
        boolean changed = false;
        for (Holding holding : holdings) {
            if (holding.getCompanyId().equals(companyId)) {
                changed |= holding.applyScore(companyName, score, calculatedAt);
            }
        }
        if (changed) {
            recalculateAggregate();
        }
        return changed;
    }

    private void recalculateAggregate() {
        double weightedSum = 0;
        double weightOfScored = 0;
        for (Holding holding : holdings) {
            if (holding.getEsgScore() != null) {
                weightedSum += holding.getWeight() * holding.getEsgScore();
                weightOfScored += holding.getWeight();
            }
        }
        aggregateScore = weightOfScored == 0
                ? null
                : BigDecimal.valueOf(weightedSum / weightOfScored).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static void validate(List<Holding> holdings) {
        Set<Long> seen = new HashSet<>();
        double totalWeight = 0;
        for (Holding holding : holdings) {
            if (!seen.add(holding.getCompanyId())) {
                throw new InvalidPortfolioException("A portfolio may hold company " + holding.getCompanyId() + " only once");
            }
            totalWeight += holding.getWeight();
        }
        if (totalWeight > 1 + WEIGHT_TOLERANCE) {
            throw new InvalidPortfolioException("Holding weights must not sum to more than 1, but sum to "
                    + BigDecimal.valueOf(totalWeight).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        }
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getAggregateScore() { return aggregateScore; }
    public List<Holding> getHoldings() { return holdings; }
}
