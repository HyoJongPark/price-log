ALTER TABLE purchase_option ADD COLUMN sale_ended_at timestamptz;

DROP INDEX purchase_option_active_key;
CREATE UNIQUE INDEX purchase_option_active_key ON purchase_option (type, steam_id)
    WHERE deleted_at IS NULL AND sale_ended_at IS NULL;

CREATE INDEX purchase_option_sale_ended_at ON purchase_option (sale_ended_at)
    WHERE deleted_at IS NULL AND sale_ended_at IS NOT NULL;

CREATE INDEX game_purchase_option_by_option ON game_purchase_option (purchase_option_id);
