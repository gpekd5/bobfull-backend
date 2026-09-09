-- Issue #138 배포 전 1회 실행하는 MySQL 8 마이그레이션이다.
-- 기존 테이블은 식당별 shared_table_id 오름차순으로 표시 번호를 부여한다.
ALTER TABLE shared_table
    ADD COLUMN display_number INT NULL AFTER restaurant_id;

UPDATE shared_table AS target
JOIN (
    SELECT
        shared_table_id,
        ROW_NUMBER() OVER (
            PARTITION BY restaurant_id
            ORDER BY shared_table_id
        ) AS display_number
    FROM shared_table
) AS numbered ON numbered.shared_table_id = target.shared_table_id
SET target.display_number = numbered.display_number;

ALTER TABLE shared_table
    MODIFY COLUMN display_number INT NOT NULL,
    ADD CONSTRAINT uk_shared_table_restaurant_display_number
        UNIQUE (restaurant_id, display_number);
