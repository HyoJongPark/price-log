ALTER TABLE game
    ADD COLUMN store_visibility varchar(32)
        CHECK (store_visibility IN ('VISIBLE', 'REGION_RESTRICTED', 'HIDDEN'));
