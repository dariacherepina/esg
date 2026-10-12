package com.esg.portfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One position in a portfolio: a company (by id from company-service) and its weight.
 * Name and score are a local copy, kept current by score.updated events.
 */
@Entity
@Table(name = "holdings")
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @Column(nullable = false)
    private double weight;

    /** null until the first score is known. */
    @Column(name = "esg_score")
    private Double esgScore;

    @Column(name = "score_calculated_at")
    private Instant scoreCalculatedAt;

    protected Holding() {
        // required by JPA
    }

    public Holding(Long companyId, double weight) {
        this.companyId = companyId;
        this.weight = weight;
    }

    void attachTo(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    /**
     * Applies a score unless an equal or newer one is already stored (events can arrive out of order).
     *
     * @return true if the holding changed
     */
    boolean applyScore(String companyName, double score, Instant calculatedAt) {
        if (scoreCalculatedAt != null && !calculatedAt.isAfter(scoreCalculatedAt)) {
            return false;
        }
        this.companyName = companyName;
        this.esgScore = score;
        this.scoreCalculatedAt = calculatedAt;
        return true;
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getCompanyName() { return companyName; }
    public double getWeight() { return weight; }
    public Double getEsgScore() { return esgScore; }
    public Instant getScoreCalculatedAt() { return scoreCalculatedAt; }
}
