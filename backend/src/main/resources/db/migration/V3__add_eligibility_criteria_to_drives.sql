-- Academic eligibility criteria that Indian campus drives almost always carry.
-- The two percentage cutoffs are nullable, matching cgpa_cutoff: NULL means the drive
-- sets no requirement for that qualification.
-- backlogs_allowed is NOT NULL because "unknown" has no sensible meaning here; existing
-- rows take the conservative FALSE (no active backlogs permitted).
ALTER TABLE drives
    ADD COLUMN tenth_cutoff     NUMERIC(5, 2),
    ADD COLUMN twelfth_cutoff   NUMERIC(5, 2),
    ADD COLUMN backlogs_allowed BOOLEAN NOT NULL DEFAULT FALSE;
