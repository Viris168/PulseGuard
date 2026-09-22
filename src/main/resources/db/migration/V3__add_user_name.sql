-- Backfill existing rows with '' so the NOT NULL add succeeds, then drop the
-- default so new inserts must supply a name.
ALTER TABLE users
    ADD COLUMN name VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE users
    ALTER COLUMN name DROP DEFAULT;
