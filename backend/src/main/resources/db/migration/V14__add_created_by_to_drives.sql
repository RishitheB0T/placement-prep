-- Who posted each drive, so a recruiter sees only their own drives and their applicants.
--
-- Nullable: drives posted before this column existed have no recorded poster, and they
-- stay visible to the cell (coordinators and the person in charge) as before - only a
-- recruiter's view is filtered on this column.
--
-- ON DELETE SET NULL rather than CASCADE: removing a recruiter's account must not take
-- their drives, and the students' applications to them, down with it. The drive simply
-- passes back to the cell.
ALTER TABLE drives
    ADD COLUMN created_by BIGINT REFERENCES users (id) ON DELETE SET NULL;

-- A recruiter's every board, applicant and dashboard request filters on this column.
CREATE INDEX idx_drives_created_by ON drives (created_by);
