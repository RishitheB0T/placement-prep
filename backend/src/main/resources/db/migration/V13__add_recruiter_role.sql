-- Adds RECRUITER: a company's hiring contact, created by the person in charge.
--
-- Recreated rather than edited for the same reason as V7: V5 and V7 have already been
-- applied, and Flyway refuses to start if an applied migration's checksum changes.
ALTER TABLE users DROP CONSTRAINT users_role_valid;

ALTER TABLE users ADD CONSTRAINT users_role_valid
    CHECK (role IN ('STUDENT', 'TNP_COORDINATOR', 'RECRUITER', 'TNP_PIC'));
