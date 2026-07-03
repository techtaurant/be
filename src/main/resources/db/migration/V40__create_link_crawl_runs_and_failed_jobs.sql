CREATE TABLE link_crawl_runs (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES link_crawl_batches(id) ON DELETE CASCADE,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    collected_count INTEGER NOT NULL,
    new_link_count INTEGER NOT NULL,
    existing_link_count INTEGER NOT NULL,
    skipped_count INTEGER NOT NULL,
    failed_job_count INTEGER NOT NULL,
    started_at_utc TIMESTAMPTZ NOT NULL,
    finished_at_utc TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_link_crawl_runs_batch_id_started_at ON link_crawl_runs(batch_id, started_at_utc DESC);

CREATE TABLE link_crawl_failed_jobs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES link_crawl_runs(id) ON DELETE CASCADE,
    source_page INTEGER NOT NULL,
    source_page_url VARCHAR(2048) NOT NULL,
    article_url VARCHAR(2048) NOT NULL,
    title VARCHAR(200),
    summary TEXT,
    error_status_code INTEGER NOT NULL,
    error_message TEXT NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 1,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at_utc TIMESTAMPTZ,
    last_failed_at_utc TIMESTAMPTZ NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_link_crawl_failed_jobs_run_article_url UNIQUE (run_id, article_url)
);

CREATE INDEX idx_link_crawl_failed_jobs_run_id ON link_crawl_failed_jobs(run_id);
CREATE INDEX idx_link_crawl_failed_jobs_unresolved ON link_crawl_failed_jobs(run_id) WHERE resolved = FALSE;
CREATE INDEX idx_link_crawl_failed_jobs_created_at ON link_crawl_failed_jobs(created_at_utc);
