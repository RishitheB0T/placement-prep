-- A resume a student uploaded for one drive, and the AI review of it against that
-- drive's job description.
--
-- The PDF itself is not stored in this table, only what is known about it. storage_key
-- is the name of the file inside the resume storage directory (resume.storage.directory
-- in application.yml). The server generates it, so nothing a client sends ever becomes a
-- path on disk. original_filename is kept purely to show back to the student.
--
-- This table is also the queue for the background review. A new row starts PENDING;
-- ResumeReviewJob takes the oldest PENDING row, marks it PROCESSING, asks the model, and
-- finishes it as COMPLETED or FAILED. Keeping the queue in the database means a review
-- that was waiting when the server stopped is still waiting when it starts again.
--
-- The foreign keys follow V9: drive_id cascades, because a review scored against a drive
-- that no longer exists has nothing left to refer to; student_id does not.
CREATE TABLE resumes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id        BIGINT       NOT NULL REFERENCES users(id),
    drive_id          BIGINT       NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(64)  NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    status            VARCHAR(32)  NOT NULL,

    -- Filled in only once the review has COMPLETED.
    score             INTEGER,
    summary           TEXT,
    strengths         TEXT[],
    improvements      TEXT[],

    -- Filled in only when the review FAILED, in words a student can act on.
    failure_reason    VARCHAR(255),

    uploaded_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    reviewed_at       TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT resumes_storage_key_unique UNIQUE (storage_key),
    CONSTRAINT resumes_status_valid CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT resumes_score_range CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT resumes_size_positive CHECK (size_bytes > 0)
);

-- "Everything this student uploaded", for their own resume page.
CREATE INDEX resumes_student_idx ON resumes (student_id);

-- "The oldest PENDING resume", which the background job asks every few seconds.
CREATE INDEX resumes_queue_idx ON resumes (status, uploaded_at);
