-- One row per background job: whichever copy of the app holds the row runs the job; the others skip that
-- turn. A copy that dies mid-job holds it no longer than locked_until.
CREATE TABLE job_locks (
    name         VARCHAR(64)  PRIMARY KEY,
    locked_until TIMESTAMPTZ  NOT NULL,
    locked_at    TIMESTAMPTZ  NOT NULL,
    locked_by    VARCHAR(255) NOT NULL
);
