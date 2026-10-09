package com.esg.company.api;

import com.esg.company.domain.Company;
import com.esg.company.domain.Sector;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST and PUT requests. Has no id: ids are assigned by the database.
 * Wrapper types (Double, Integer) so a missing field is rejected as null instead of silently becoming 0.
 */
public record CompanyRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Sector sector,
        @NotNull @DecimalMin("0") @DecimalMax("1000") Double co2Emissions,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double renewableEnergyPct,
        @NotNull @DecimalMin("0") @DecimalMax("5000") Double waterConsumption,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double boardDiversityPct,
        @NotNull @Min(0) @Max(10) Integer corruptionIncidents,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double transparencyScore
) {

    public Company toEntity() {
        return new Company(name, sector, co2Emissions, renewableEnergyPct, waterConsumption,
                boardDiversityPct, corruptionIncidents, transparencyScore);
    }
}
