ALTER TABLE link_crawl_batches ADD COLUMN IF NOT EXISTS end_page INTEGER;

UPDATE link_crawl_batches
SET end_page = start_page
WHERE end_page IS NULL;

ALTER TABLE link_crawl_batches ALTER COLUMN end_page SET DEFAULT 1;
ALTER TABLE link_crawl_batches ALTER COLUMN end_page SET NOT NULL;

ALTER TABLE link_crawl_batches
ADD CONSTRAINT chk_link_crawl_batches_page_range CHECK (end_page >= start_page);
