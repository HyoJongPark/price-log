ALTER TABLE purchase_option
    ADD COLUMN current_price bigint CHECK (current_price >= 0),
    ADD COLUMN regular_price bigint CHECK (regular_price >= 0),
    ADD COLUMN discount_ends_at timestamptz,
    ADD COLUMN price_observed_at timestamptz;

CREATE TABLE price_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_option_id bigint NOT NULL REFERENCES purchase_option (id),
    slot_at timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    current_price bigint NOT NULL CHECK (current_price >= 0),
    regular_price bigint CHECK (regular_price >= 0),
    discount_ends_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    UNIQUE (purchase_option_id, slot_at)
);
