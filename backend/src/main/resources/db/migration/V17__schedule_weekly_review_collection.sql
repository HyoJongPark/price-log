CREATE TABLE review_collection (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id bigint NOT NULL UNIQUE REFERENCES game (id),
    first_review_date date NOT NULL,
    collected_through date,
    next_due_on date NOT NULL,
    CHECK (collected_through >= first_review_date)
);

CREATE INDEX review_collection_due ON review_collection (next_due_on, game_id);
