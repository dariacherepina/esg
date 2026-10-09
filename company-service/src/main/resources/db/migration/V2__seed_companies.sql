-- 20 fictional companies (any resemblance to real companies is unintended).
-- 5 per sector, spread across the rating bands (5 Excellent, 5 Good, 5 Fair, 5 Poor overall)
-- so experiments always see the full score range. Insert order is fixed, so ids 1-20 are
-- identical on every fresh database.
--
-- Columns: name, sector, co2 (t/EUR 1M), renewable %, water (m3/EUR 1M), board diversity %,
--          corruption incidents (3y), transparency score

INSERT INTO companies (name, sector, co2_emissions, renewable_energy_pct, water_consumption,
                       board_diversity_pct, corruption_incidents, transparency_score)
VALUES
    -- Technology
    ('Lumina Cloud Systems',       'TECHNOLOGY',     15, 95,  120, 45, 0, 92),  -- Excellent
    ('Quantix Software',           'TECHNOLOGY',     25, 85,  200, 40, 0, 85),  -- Excellent
    ('Novabyte Devices',           'TECHNOLOGY',    120, 55,  900, 30, 1, 70),  -- Good
    ('Pixelcore Hardware',         'TECHNOLOGY',    260, 30, 1800, 20, 2, 50),  -- Fair
    ('Hexabit Semiconductors',     'TECHNOLOGY',    520,  8, 4200,  8, 6, 22),  -- Poor

    -- Energy
    ('Voltara Renewables',         'ENERGY',         40, 98,  300, 42, 0, 88),  -- Excellent
    ('Helios Grid Partners',       'ENERGY',        180, 65, 1200, 35, 1, 72),  -- Good
    ('Northwind Gas & Power',      'ENERGY',        450, 35, 2200, 25, 2, 55),  -- Fair
    ('Deepwell Petroleum',         'ENERGY',        780,  6, 3900, 15, 4, 35),  -- Poor
    ('Carbonex Fuels',             'ENERGY',        920,  3, 4600, 10, 5, 25),  -- Poor

    -- Finance
    ('Greenleaf Asset Management', 'FINANCE',         5, 90,   60, 48, 0, 95),  -- Excellent
    ('Alderpoint Bank',            'FINANCE',        12, 45,  150, 30, 1, 68),  -- Good
    ('Crestmoor Insurance',        'FINANCE',        18, 40,  200, 28, 1, 65),  -- Good
    ('Silverline Investments',     'FINANCE',        30, 15,  300, 18, 3, 45),  -- Fair
    ('Ironclad Credit Group',      'FINANCE',        45,  5,  400,  6, 8, 15),  -- Poor

    -- Manufacturing
    ('Ecoforge Industries',        'MANUFACTURING',  90, 85,  600, 40, 0, 85),  -- Excellent
    ('Tessaro Automotive',         'MANUFACTURING', 200, 55, 1500, 30, 1, 70),  -- Good
    ('Polymera Chemicals',         'MANUFACTURING', 480, 25, 2800, 26, 2, 58),  -- Fair
    ('Kronberg Steelworks',        'MANUFACTURING', 650, 20, 3200, 22, 2, 60),  -- Fair
    ('Ashgrove Cement',            'MANUFACTURING', 980,  4, 4800,  5, 7, 20);  -- Poor
