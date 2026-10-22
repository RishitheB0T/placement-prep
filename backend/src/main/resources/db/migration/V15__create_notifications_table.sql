-- In-app notifications for students: a new drive they are eligible for, a deadline that
-- is about to pass on a drive they have not applied to, and a change to an application's
-- status.
--
-- drive_id is nullable so a future notification that is not about one drive still fits.
-- Both foreign keys cascade: a notification about a deleted drive, or for a deleted
-- account, has nothing left to point at.
CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    drive_id   BIGINT        REFERENCES drives (id) ON DELETE CASCADE,
    kind       VARCHAR(32)   NOT NULL,
    message    VARCHAR(500)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT notifications_kind_valid CHECK (
        kind IN ('NEW_DRIVE', 'DEADLINE_3_DAYS', 'DEADLINE_1_DAY', 'STATUS_CHANGED')
    )
);

-- The reminder job runs every few minutes and must never send the same reminder twice.
-- This makes "one NEW_DRIVE / DEADLINE_3_DAYS / DEADLINE_1_DAY per student per drive" a
-- rule of the database, not just of the code. STATUS_CHANGED is left out on purpose: an
-- application can be shortlisted and later selected, and each change deserves a message.
CREATE UNIQUE INDEX notifications_once_per_drive
    ON notifications (user_id, drive_id, kind)
    WHERE kind <> 'STATUS_CHANGED';

-- A student's bell lists their newest notifications first.
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
