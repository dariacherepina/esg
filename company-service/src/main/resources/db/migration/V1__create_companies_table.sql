-- Company profiles with raw ESG data.
-- Emissions and water are intensities per EUR 1M revenue. Ranges mirror the Bean Validation
-- constraints on the Company entity, so invalid data is rejected even when inserted via SQL.

CREATE TABLE companies (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                  VARCHAR(100)     NOT NULL UNIQUE,
    sector                VARCHAR(20)      NOT NULL
        CHECK (sector IN ('TECHNOLOGY', 'ENERGY', 'FINANCE', 'MANUFACTURING')),
    co2_emissions         DOUBLE PRECISION NOT NULL CHECK (co2_emissions BETWEEN 0 AND 1000),
    renewable_energy_pct  DOUBLE PRECISION NOT NULL CHECK (renewable_energy_pct BETWEEN 0 AND 100),
    water_consumption     DOUBLE PRECISION NOT NULL CHECK (water_consumption BETWEEN 0 AND 5000),
    board_diversity_pct   DOUBLE PRECISION NOT NULL CHECK (board_diversity_pct BETWEEN 0 AND 100),
    corruption_incidents  INTEGER          NOT NULL CHECK (corruption_incidents BETWEEN 0 AND 10),
    transparency_score    DOUBLE PRECISION NOT NULL CHECK (transparency_score BETWEEN 0 AND 100)
);
