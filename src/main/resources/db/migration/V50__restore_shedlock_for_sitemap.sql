-- V13 removed the old statistics scheduler's lock table. Sitemap publication now needs a shared lease.
-- ShedLock usingDbTime stores UTC TIMESTAMP values; this is its library-owned schema.
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
