CREATE TABLE game (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    steam_app_id bigint NOT NULL UNIQUE CHECK (steam_app_id BETWEEN 1 AND 4294967295),
    name text NOT NULL,
    search_aliases text[] NOT NULL DEFAULT '{}',
    app_type text NOT NULL CHECK (app_type IN ('GAME', 'DLC', 'UNKNOWN')),
    parent_game_id bigint REFERENCES game (id),
    description text,
    image_url text,
    korean_support_text text,
    release_date date,
    release_text text,
    coming_soon boolean,
    sale_status text NOT NULL DEFAULT 'unknown' CHECK (sale_status IN (
        'unknown', 'base_available', 'free_to_play', 'unreleased',
        'unavailable_in_kr', 'bundle_only', 'base_unconfirmed'
    )),
    detail_checked_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CHECK (parent_game_id IS NULL OR (parent_game_id <> id AND app_type <> 'GAME'))
);
