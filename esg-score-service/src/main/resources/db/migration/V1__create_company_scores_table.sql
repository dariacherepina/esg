-- Latest ESG score per company. company_id references a company in company-service
-- (a different database, so no foreign key — services own their data independently).

CREATE TABLE company_scores (
    company_id     BIGINT           PRIMARY KEY,
    company_name   VARCHAR(100)     NOT NULL,
    environmental  DOUBLE PRECISION NOT NULL CHECK (environmental BETWEEN 0 AND 100),
    social         DOUBLE PRECISION NOT NULL CHECK (social BETWEEN 0 AND 100),
    governance     DOUBLE PRECISION NOT NULL CHECK (governance BETWEEN 0 AND 100),
    total          DOUBLE PRECISION NOT NULL CHECK (total BETWEEN 0 AND 100),
    calculated_at  TIMESTAMPTZ      NOT NULL
);
