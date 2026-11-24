ALTER TABLE pipeline_execution
    DROP CONSTRAINT pipeline_execution_pipeline_check,
    ADD CHECK (pipeline IN ('STEAM_GAME_LIST', 'STEAM_DLC_LIST', 'STEAM_PRICE')),
    ADD COLUMN target_ids bigint[],
    ADD COLUMN next_index integer NOT NULL DEFAULT 0,
    ADD COLUMN failed_ids bigint[] NOT NULL DEFAULT '{}',
    ADD CHECK ((pipeline = 'STEAM_PRICE') = (target_ids IS NOT NULL)),
    ADD CHECK (next_index BETWEEN 0 AND coalesce(cardinality(target_ids), 0));
