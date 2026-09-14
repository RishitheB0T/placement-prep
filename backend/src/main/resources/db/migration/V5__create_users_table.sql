-- Accounts for both students and the training-and-placement cell.
-- password_hash stores a BCrypt hash, never a plain-text password. BCrypt output is
-- always 60 characters, but the column is sized generously so the hashing algorithm can
-- be changed later without another migration.
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_role_valid   CHECK (role IN ('STUDENT', 'TNP_ADMIN'))
);

-- Every login looks a user up by email, so the lookup should not scan the table.
-- The UNIQUE constraint above already creates an index, so no separate one is needed.
