CREATE TABLE tag (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    steam_tag_id bigint NOT NULL UNIQUE CHECK (steam_tag_id > 0),
    name_ko text
);

CREATE TABLE game_tag (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id bigint NOT NULL REFERENCES game(id),
    tag_id bigint NOT NULL REFERENCES tag(id),
    display_order integer NOT NULL CHECK (display_order > 0),
    UNIQUE (game_id, tag_id)
);

CREATE INDEX game_tag_by_tag ON game_tag (tag_id, game_id);
