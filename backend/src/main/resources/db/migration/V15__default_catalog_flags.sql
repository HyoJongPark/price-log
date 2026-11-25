UPDATE game
SET is_free = coalesce(is_free, false), coming_soon = coalesce(coming_soon, false)
WHERE is_free IS NULL OR coming_soon IS NULL;

ALTER TABLE game
    ALTER COLUMN is_free SET DEFAULT false,
    ALTER COLUMN is_free SET NOT NULL,
    ALTER COLUMN coming_soon SET DEFAULT false,
    ALTER COLUMN coming_soon SET NOT NULL;

ALTER TABLE game_purchase_option ALTER COLUMN is_best_option SET DEFAULT false;
