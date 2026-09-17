-- Splits the single TNP_ADMIN role into two: a coordinator who manages drives, and a
-- person-in-charge who can additionally promote students into the cell.
--
-- The CHECK constraint is recreated rather than edited because V5 has already been
-- applied and Flyway records a checksum of every applied migration, so changing that
-- file would make the application refuse to start.

-- Any account created under the old name becomes the person in charge, which is the
-- closer of the two new roles in authority. No such rows exist yet, but a constraint
-- added while a row violates it fails the whole migration, so this runs first.
UPDATE users SET role = 'TNP_PIC' WHERE role = 'TNP_ADMIN';

ALTER TABLE users DROP CONSTRAINT users_role_valid;

ALTER TABLE users ADD CONSTRAINT users_role_valid
    CHECK (role IN ('STUDENT', 'TNP_COORDINATOR', 'TNP_PIC'));
