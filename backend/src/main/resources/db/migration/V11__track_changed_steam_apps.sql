ALTER TABLE pipeline_execution
    DROP CONSTRAINT pipeline_execution_pipeline_check,
    DROP CONSTRAINT pipeline_execution_check1,
    ADD CHECK (pipeline IN ('STEAM_GAME_LIST', 'STEAM_DLC_LIST', 'STEAM_PRICE', 'STEAM_CHANGED_APPS')),
    ADD CHECK (pipeline = 'STEAM_CHANGED_APPS' OR (pipeline = 'STEAM_PRICE') = (target_ids IS NOT NULL));
