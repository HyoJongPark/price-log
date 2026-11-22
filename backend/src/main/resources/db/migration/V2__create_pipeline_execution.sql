CREATE TABLE pipeline_execution (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pipeline text NOT NULL CHECK (pipeline IN ('STEAM_GAME_LIST', 'STEAM_DLC_LIST')),
    scheduled_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED')),
    list_cursor bigint CHECK (list_cursor BETWEEN 1 AND 4294967295),
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    UNIQUE (pipeline, scheduled_at),
    CHECK ((status = 'COMPLETED') = (finished_at IS NOT NULL))
);
