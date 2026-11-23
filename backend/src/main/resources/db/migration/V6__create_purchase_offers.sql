CREATE TABLE steam_offer (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offer_type text NOT NULL CHECK (offer_type IN ('PACKAGE', 'BUNDLE')),
    steam_id bigint NOT NULL CHECK (steam_id > 0),
    name text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE UNIQUE INDEX steam_offer_active_key ON steam_offer (offer_type, steam_id)
    WHERE deleted_at IS NULL;

CREATE TABLE game_offer (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id bigint NOT NULL REFERENCES game (id),
    offer_id bigint NOT NULL REFERENCES steam_offer (id),
    is_best_option boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE UNIQUE INDEX game_offer_active_key ON game_offer (game_id, offer_id)
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX game_offer_one_best ON game_offer (game_id)
    WHERE is_best_option AND deleted_at IS NULL;
