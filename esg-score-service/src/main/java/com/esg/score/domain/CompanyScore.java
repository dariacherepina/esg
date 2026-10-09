package com.esg.score.domain;

import com.esg.score.scoring.EsgScore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The latest calculated ESG score of one company. One row per company, overwritten on recalculation. */
@Entity
@Table(name = "company_scores")
public class CompanyScore {

    /** Id of the company in company-service — not generated here. */
    @Id
    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(nullable = false)
    private double environmental;

    @Column(nullable = false)
    private double social;

    @Column(nullable = false)
    private double governance;

    @Column(nullable = false)
    private double total;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    protected CompanyScore() {
        // required by JPA
    }

    public CompanyScore(Long companyId, String companyName, EsgScore score, Instant calculatedAt) {
        this.companyId = companyId;
        update(companyName, score, calculatedAt);
    }

    public void update(String companyName, EsgScore score, Instant calculatedAt) {
        this.companyName = companyName;
        this.environmental = score.environmental();
        this.social = score.social();
        this.governance = score.governance();
        this.total = score.total();
        this.calculatedAt = calculatedAt;
    }

    public Long getCompanyId() { return companyId; }
    public String getCompanyName() { return companyName; }
    public double getEnvironmental() { return environmental; }
    public double getSocial() { return social; }
    public double getGovernance() { return governance; }
    public double getTotal() { return total; }
    public Instant getCalculatedAt() { return calculatedAt; }
}
