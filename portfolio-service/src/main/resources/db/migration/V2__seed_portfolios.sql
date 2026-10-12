-- Three fixed demo portfolios over the 20 seed companies of company-service.
-- Scores start empty and are filled by score.updated events. Expected aggregates once all
-- scores are calculated:  1 -> 94.75 (Excellent),  2 -> 69.04 (Good),  3 -> 20.99 (Poor)

INSERT INTO portfolios (name) VALUES
    ('Sustainable Leaders'),   -- id 1
    ('Balanced Mix'),          -- id 2
    ('Heavy Industry');        -- id 3

INSERT INTO holdings (portfolio_id, company_id, weight) VALUES
    -- Sustainable Leaders: Lumina Cloud Systems, Voltara Renewables, Greenleaf Asset Management
    (1, 1,  0.40),
    (1, 6,  0.30),
    (1, 11, 0.30),
    -- Balanced Mix: one Good/Fair company from each sector
    (2, 3,  0.25),   -- Novabyte Devices
    (2, 8,  0.25),   -- Northwind Gas & Power
    (2, 12, 0.25),   -- Alderpoint Bank
    (2, 17, 0.25),   -- Tessaro Automotive
    -- Heavy Industry: Deepwell Petroleum, Carbonex Fuels, Ashgrove Cement
    (3, 9,  0.30),
    (3, 10, 0.30),
    (3, 20, 0.40);
