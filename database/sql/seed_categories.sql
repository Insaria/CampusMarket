USE campus_market_dev;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO categories (name, description, sort_order, is_enabled)
SELECT seed.name, NULL, seed.sort_order, 1
FROM (
    SELECT '教材与学习用品' AS name, 10 AS sort_order
    UNION ALL SELECT '数码电子', 20
    UNION ALL SELECT '宿舍与生活用品', 30
    UNION ALL SELECT '服饰鞋包', 40
    UNION ALL SELECT '运动与户外', 50
    UNION ALL SELECT '其他', 60
) AS seed
WHERE NOT EXISTS (
    SELECT 1 FROM categories existing WHERE existing.name = seed.name
)
ORDER BY seed.sort_order;

COMMIT;

SELECT id, name, sort_order, is_enabled
FROM categories
WHERE name IN ('教材与学习用品', '数码电子', '宿舍与生活用品', '服饰鞋包', '运动与户外', '其他')
ORDER BY sort_order, id;
