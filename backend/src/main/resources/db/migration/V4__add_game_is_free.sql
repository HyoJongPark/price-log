ALTER TABLE game
    ADD COLUMN is_free boolean,
    ADD COLUMN catalog_updated_at timestamptz;

-- 무료 표기의 수정 시각과 목록의 이름·종류를 비교하는 시각을 분리한다.
UPDATE game SET catalog_updated_at = updated_at;
ALTER TABLE game ALTER COLUMN catalog_updated_at SET NOT NULL;
