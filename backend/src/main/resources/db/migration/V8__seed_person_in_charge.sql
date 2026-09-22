-- Seeds the training-and-placement cell's first person in charge.
--
-- TNP_PIC has no self-service route by design (see Role.java): registration always
-- creates a STUDENT, and only an existing TNP_PIC can promote anyone, so the very first
-- one cannot come from the API - something has to plant it directly. A migration is the
-- right place rather than a manual psql command, because it means the account exists the
-- moment anyone runs this project from a clean database, without an extra undocumented
-- step.
--
-- The hash below is a real BCrypt digest of the password, generated with this project's
-- own BCryptPasswordEncoder and verified with encoder.matches(...) before being pasted
-- in - not typed by hand, which is how a seed account quietly becomes unable to log in.
-- BCrypt is one-way, so committing the hash does not expose the password itself; the
-- password is a placeholder for local development, not something to reuse anywhere real.
--
-- ON CONFLICT DO NOTHING makes this migration safe to have existed if the account was
-- somehow already created another way (e.g. by hand, before this migration was written) -
-- Flyway runs every migration exactly once per database anyway, but this keeps the
-- statement itself honest about not wanting to clobber a row that is already there.
INSERT INTO users (email, password_hash, role)
VALUES (
    'tnp@pic.nits.ac.in',
    '$2a$10$fF1xEcVvMlAbDwHqlcWUr.bVyO8oTWPdY.R41xreFqoY.bJUa0Zd6',
    'TNP_PIC'
)
ON CONFLICT (email) DO NOTHING;
