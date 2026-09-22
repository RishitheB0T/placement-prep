-- Staff details for the person in charge specifically - not the cell in general.
--
-- Designation, department, a staff id, office location and a phone number: the things a
-- professor-in-charge record needs that a coordinator's account never has and a student's
-- never had. All nullable, for the same reason the academic columns in V6 are nullable -
-- the account exists from registration or promotion, and these are filled in afterwards
-- through the account owner's own self-service endpoint.
ALTER TABLE users
    ADD COLUMN designation     VARCHAR(100),
    ADD COLUMN department      VARCHAR(100),
    ADD COLUMN staff_id        VARCHAR(50),
    ADD COLUMN office_location VARCHAR(100),
    ADD COLUMN phone_number    VARCHAR(20);

-- No two people share a staff id. A UNIQUE index allows any number of NULLs in Postgres,
-- so this does not block students, coordinators, or a PIC who has not filled it in yet.
ALTER TABLE users ADD CONSTRAINT users_staff_id_unique UNIQUE (staff_id);

-- These columns belong to TNP_PIC alone. A student never has them, and neither does a
-- coordinator - that split is a deliberate product decision, not an oversight, and this
-- constraint is what stops a future bug (or a future PATCH endpoint that forgets the
-- rule) from quietly writing them onto the wrong kind of account.
ALTER TABLE users ADD CONSTRAINT users_staff_fields_pic_only CHECK (
    role = 'TNP_PIC' OR (
        designation IS NULL AND department IS NULL AND staff_id IS NULL
        AND office_location IS NULL AND phone_number IS NULL
    )
);
