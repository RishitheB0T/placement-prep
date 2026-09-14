-- Academic details a student is judged on, so eligibility can be decided from the
-- logged-in account instead of being passed in on every request.
--
-- Every column except backlogs is nullable: an account exists from the moment someone
-- registers, but they fill in their marks afterwards. Code must therefore treat a profile
-- as possibly incomplete rather than assuming these are present. TNP administrators never
-- fill them in at all, which is a second reason they cannot be NOT NULL.
--
-- backlogs is NOT NULL DEFAULT 0 because "unknown number of backlogs" has no useful
-- meaning, and zero is both the common case and the safe default.
ALTER TABLE users
    ADD COLUMN cgpa               NUMERIC(4, 2),
    ADD COLUMN branch             VARCHAR(32),
    ADD COLUMN tenth_percentage   NUMERIC(5, 2),
    ADD COLUMN twelfth_percentage NUMERIC(5, 2),
    ADD COLUMN backlogs           INTEGER NOT NULL DEFAULT 0;

-- The API validates these too, but a constraint here also catches anything written
-- directly to the database, and documents the intent next to the data itself.
ALTER TABLE users
    ADD CONSTRAINT users_cgpa_range     CHECK (cgpa IS NULL OR (cgpa >= 0 AND cgpa <= 10)),
    ADD CONSTRAINT users_tenth_range    CHECK (tenth_percentage IS NULL OR (tenth_percentage >= 0 AND tenth_percentage <= 100)),
    ADD CONSTRAINT users_twelfth_range  CHECK (twelfth_percentage IS NULL OR (twelfth_percentage >= 0 AND twelfth_percentage <= 100)),
    ADD CONSTRAINT users_backlogs_range CHECK (backlogs >= 0);
