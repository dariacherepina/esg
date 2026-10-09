package com.esg.company.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A company profile with its raw ESG data.
 * Emissions and water are intensities (per EUR 1M revenue) so companies of different size are comparable.
 * Scoring happens in esg-score-service; this service only stores raw values.
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Sector sector;

    /** Environmental: tonnes CO2 per EUR 1M revenue (0-1000, lower is better). */
    @DecimalMin("0") @DecimalMax("1000")
    @Column(name = "co2_emissions", nullable = false)
    private double co2Emissions;

    /** Environmental: share of energy from renewable sources in % (0-100, higher is better). */
    @DecimalMin("0") @DecimalMax("100")
    @Column(name = "renewable_energy_pct", nullable = false)
    private double renewableEnergyPct;

    /** Environmental: m3 water per EUR 1M revenue (0-5000, lower is better). */
    @DecimalMin("0") @DecimalMax("5000")
    @Column(name = "water_consumption", nullable = false)
    private double waterConsumption;

    /** Social: share of women/underrepresented board members in % (0-100, higher is better). */
    @DecimalMin("0") @DecimalMax("100")
    @Column(name = "board_diversity_pct", nullable = false)
    private double boardDiversityPct;

    /** Social: confirmed corruption cases in the last 3 years (0-10, lower is better). */
    @Min(0) @Max(10)
    @Column(name = "corruption_incidents", nullable = false)
    private int corruptionIncidents;

    /** Governance: disclosure quality rating (0-100, higher is better). */
    @DecimalMin("0") @DecimalMax("100")
    @Column(name = "transparency_score", nullable = false)
    private double transparencyScore;

    protected Company() {
        // required by JPA
    }

    public Company(String name, Sector sector, double co2Emissions, double renewableEnergyPct,
                   double waterConsumption, double boardDiversityPct, int corruptionIncidents,
                   double transparencyScore) {
        this.name = name;
        this.sector = sector;
        this.co2Emissions = co2Emissions;
        this.renewableEnergyPct = renewableEnergyPct;
        this.waterConsumption = waterConsumption;
        this.boardDiversityPct = boardDiversityPct;
        this.corruptionIncidents = corruptionIncidents;
        this.transparencyScore = transparencyScore;
    }

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Sector getSector() { return sector; }
    public void setSector(Sector sector) { this.sector = sector; }

    public double getCo2Emissions() { return co2Emissions; }
    public void setCo2Emissions(double co2Emissions) { this.co2Emissions = co2Emissions; }

    public double getRenewableEnergyPct() { return renewableEnergyPct; }
    public void setRenewableEnergyPct(double renewableEnergyPct) { this.renewableEnergyPct = renewableEnergyPct; }

    public double getWaterConsumption() { return waterConsumption; }
    public void setWaterConsumption(double waterConsumption) { this.waterConsumption = waterConsumption; }

    public double getBoardDiversityPct() { return boardDiversityPct; }
    public void setBoardDiversityPct(double boardDiversityPct) { this.boardDiversityPct = boardDiversityPct; }

    public int getCorruptionIncidents() { return corruptionIncidents; }
    public void setCorruptionIncidents(int corruptionIncidents) { this.corruptionIncidents = corruptionIncidents; }

    public double getTransparencyScore() { return transparencyScore; }
    public void setTransparencyScore(double transparencyScore) { this.transparencyScore = transparencyScore; }
}
