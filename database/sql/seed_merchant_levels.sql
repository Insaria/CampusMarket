USE campus_market_dev;

INSERT INTO merchant_levels (level_code, level_name, fee_rate)
SELECT seed.level_code, seed.level_name, seed.fee_rate
FROM (
    SELECT 1 AS level_code, '一级' AS level_name, 0.001000 AS fee_rate
    UNION ALL SELECT 2, '二级', 0.002000
    UNION ALL SELECT 3, '三级', 0.005000
    UNION ALL SELECT 4, '四级', 0.007500
    UNION ALL SELECT 5, '五级', 0.010000
) AS seed
WHERE NOT EXISTS (
    SELECT 1 FROM merchant_levels existing WHERE existing.level_code = seed.level_code
);
