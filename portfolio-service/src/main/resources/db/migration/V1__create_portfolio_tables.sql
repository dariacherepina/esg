-- Portfolios and their holdings. company_id refers to company-service (another database),
-- so there is no foreign key for it; company_name and esg_score are a local copy kept
-- current by score.updated events.

CREATE TABLE portfolios (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(100)     NOT NULL UNIQUE,
    aggregate_score  DOUBLE PRECISION CHECK (aggregate_score BETWEEN 0 AND 100)
);

CREATE TABLE holdings (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    portfolio_id         BIGINT           NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    company_id           BIGINT           NOT NULL,
    company_name         VARCHAR(100),
    weight               DOUBLE PRECISION NOT NULL CHECK (weight > 0 AND weight <= 1),
    esg_score            DOUBLE PRECISION CHECK (esg_score BETWEEN 0 AND 100),
    score_calculated_at  TIMESTAMPTZ,
    UNIQUE (portfolio_id, company_id)
);

-- score.updated events look up every holding of one company
CREATE INDEX idx_holdings_company_id ON holdings (company_id);
