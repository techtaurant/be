-- 실패한 아티클 URL의 소유자를 실행(run)에서 배치로 옮긴다.
-- 실행별 소유에서는 같은 URL이 실행 횟수만큼 별개 행으로 쌓여 failure_count가 매번 1부터 다시 세어졌고,
-- 그 탓에 MAX_FAILURE_COUNT 상한을 소진한 URL도 다음 실행에서 새 행이 되어 자동 재시도가 끝없이 반복됐다.
-- 배치 소유로 옮기면 URL 하나가 행 하나에 대응하므로 재시도 횟수가 누적된다.
-- 기존 run_id는 last_run_id로 남기고, 실행 이력이 삭제돼도 실패 URL은 배치에 남도록 nullable로 둔다.

ALTER TABLE link_crawl_failed_jobs ADD COLUMN batch_id UUID;
ALTER TABLE link_crawl_failed_jobs RENAME COLUMN run_id TO last_run_id;

UPDATE link_crawl_failed_jobs j
SET batch_id = r.batch_id
FROM link_crawl_runs r
WHERE r.id = j.last_run_id;

-- 소속 실행이 이미 사라진 고아 행은 어느 배치에도 귀속시킬 수 없어 버린다.
DELETE FROM link_crawl_failed_jobs WHERE batch_id IS NULL;

-- 같은 배치에서 같은 URL을 가리키던 실행별 중복 행을 대표 행 하나로 합친다.
-- 대표는 가장 최근에 실패한 행이고, 그룹 전체가 해소된 경우에만 해소로 본다.
-- 재시도 횟수는 미해소 행의 것만 합산한다. 해소됐던 URL이 다시 실패하면 1부터 세는 애플리케이션 규칙과 맞추기 위해서다.
-- 그룹 전체가 해소됐으면 합산할 미해소 행이 없어 전체 합을 남긴다. 다시 실패하면 어차피 1로 초기화된다.
UPDATE link_crawl_failed_jobs j
SET failure_count = merged.total_failure_count,
    last_failed_at_utc = merged.latest_failed_at,
    resolved_at_utc = CASE WHEN merged.is_all_resolved THEN merged.latest_resolved_at END,
    created_at_utc = merged.earliest_created_at,
    updated_at_utc = NOW()
FROM (
    SELECT batch_id,
           article_url,
           COALESCE(SUM(failure_count) FILTER (WHERE resolved_at_utc IS NULL), SUM(failure_count)) AS total_failure_count,
           MAX(last_failed_at_utc) AS latest_failed_at,
           bool_and(resolved_at_utc IS NOT NULL) AS is_all_resolved,
           MAX(resolved_at_utc) AS latest_resolved_at,
           MIN(created_at_utc) AS earliest_created_at
    FROM link_crawl_failed_jobs
    GROUP BY batch_id, article_url
) merged
WHERE j.batch_id = merged.batch_id
  AND j.article_url = merged.article_url
  AND j.id = (
      SELECT candidate.id
      FROM link_crawl_failed_jobs candidate
      WHERE candidate.batch_id = j.batch_id
        AND candidate.article_url = j.article_url
      ORDER BY candidate.last_failed_at_utc DESC, candidate.id DESC
      LIMIT 1
  );

DELETE FROM link_crawl_failed_jobs j
WHERE j.id <> (
    SELECT candidate.id
    FROM link_crawl_failed_jobs candidate
    WHERE candidate.batch_id = j.batch_id
      AND candidate.article_url = j.article_url
    ORDER BY candidate.last_failed_at_utc DESC, candidate.id DESC
    LIMIT 1
);

-- 병합 전에는 UNRESOLVED 실행마다 미해소 행이 연결돼 있었다. 병합 뒤 연결된 미해소 행이 없는 UNRESOLVED 실행은
-- 자기 미해소 행을 대표 행에 넘겨준 실행이므로, 해소가 아니라 이후 실행으로의 이월로 기록한다.
UPDATE link_crawl_runs r
SET status = 'CARRIED_OVER'
WHERE r.status = 'UNRESOLVED'
  AND NOT EXISTS (
      SELECT 1
      FROM link_crawl_failed_jobs j
      WHERE j.last_run_id = r.id
        AND j.resolved_at_utc IS NULL
  );

ALTER TABLE link_crawl_failed_jobs ALTER COLUMN batch_id SET NOT NULL;
ALTER TABLE link_crawl_failed_jobs ALTER COLUMN last_run_id DROP NOT NULL;

ALTER TABLE link_crawl_failed_jobs
    ADD CONSTRAINT fk_link_crawl_failed_jobs_batch
    FOREIGN KEY (batch_id) REFERENCES link_crawl_batches(id) ON DELETE CASCADE;

-- 실행이 지워져도 실패 URL 자체는 배치에 남아야 하므로 CASCADE에서 SET NULL로 바꾼다.
ALTER TABLE link_crawl_failed_jobs DROP CONSTRAINT link_crawl_failed_jobs_run_id_fkey;
ALTER TABLE link_crawl_failed_jobs
    ADD CONSTRAINT fk_link_crawl_failed_jobs_last_run
    FOREIGN KEY (last_run_id) REFERENCES link_crawl_runs(id) ON DELETE SET NULL;

ALTER TABLE link_crawl_failed_jobs DROP CONSTRAINT uk_link_crawl_failed_jobs_run_article_url;
ALTER TABLE link_crawl_failed_jobs
    ADD CONSTRAINT uk_link_crawl_failed_jobs_batch_article_url UNIQUE (batch_id, article_url);

DROP INDEX idx_link_crawl_failed_jobs_run_id;
DROP INDEX idx_link_crawl_failed_jobs_unresolved;

CREATE INDEX idx_link_crawl_failed_jobs_batch_id ON link_crawl_failed_jobs(batch_id);
CREATE INDEX idx_link_crawl_failed_jobs_batch_unresolved
    ON link_crawl_failed_jobs(batch_id) WHERE resolved_at_utc IS NULL;
CREATE INDEX idx_link_crawl_failed_jobs_last_run_id ON link_crawl_failed_jobs(last_run_id);
