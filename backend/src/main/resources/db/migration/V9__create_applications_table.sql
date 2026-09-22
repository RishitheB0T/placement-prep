-- A student applying to a drive, and where that application has got to.
--
-- Real foreign keys here, which the drives and users tables did not need to reach across
-- any boundary to get: this is one database, so "this application must point at a real
-- student and a real drive" is a constraint Postgres itself can enforce, not just
-- application code.
--
-- drive_id cascades on delete: an application with no drive behind it is clutter, not
-- history worth keeping. student_id does not, because there is no way to delete a user
-- account in this application yet - if that ever changes, this default (the delete is
-- simply refused while applications reference the account) is the conservative one to
-- revisit deliberately rather than silently.
CREATE TABLE applications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id BIGINT      NOT NULL REFERENCES users(id),
    drive_id   BIGINT      NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    status     VARCHAR(32) NOT NULL,
    note       TEXT,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT applications_one_per_drive UNIQUE (student_id, drive_id),
    CONSTRAINT applications_status_valid CHECK (
        status IN ('APPLIED', 'SHORTLISTED', 'REJECTED', 'SELECTED', 'WITHDRAWN'))
);

-- The two lookups this feature serves: everything one student applied to, and everyone
-- who applied to one drive. The UNIQUE constraint above already indexes (student_id,
-- drive_id) together, which does not help a query filtering on drive_id alone.
CREATE INDEX applications_student_idx ON applications (student_id);
CREATE INDEX applications_drive_idx   ON applications (drive_id);
