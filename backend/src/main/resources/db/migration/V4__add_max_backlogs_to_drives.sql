-- How many active backlogs a drive tolerates. Meaningful only when backlogs_allowed is
-- true; NULL there means the drive sets no ceiling. No cross-column constraint is added,
-- because rows created before this migration legitimately have no value yet.
ALTER TABLE drives ADD COLUMN max_backlogs INTEGER;

ALTER TABLE drives ADD CONSTRAINT drives_max_backlogs_non_negative
    CHECK (max_backlogs IS NULL OR max_backlogs >= 0);
