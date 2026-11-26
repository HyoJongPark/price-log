-- 담당일만 예약하고 실제로 확인한 날짜가 없는 행은 수집 구간에서 제외한다.
DELETE FROM review_collection WHERE collected_through IS NULL;

ALTER TABLE review_collection
    DROP CONSTRAINT review_collection_game_id_key,
    DROP COLUMN next_due_on,
    ALTER COLUMN collected_through SET NOT NULL,
    ADD UNIQUE (game_id, first_review_date);
