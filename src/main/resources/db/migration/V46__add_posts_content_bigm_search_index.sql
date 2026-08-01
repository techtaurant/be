-- flyway:executeInTransaction=false
-- 게시물 본문 검색용 pg_bigm GIN 인덱스. V45와 분리해 한 마이그레이션의 부분 적용을 막고,
-- 실패 후 남은 invalid 인덱스를 이름만으로 정상 처리하지 않도록 IF NOT EXISTS를 사용하지 않는다.
-- 실패 복구 절차는 docs/database-migration-runbook.md를 따른다.

CREATE INDEX CONCURRENTLY idx_posts_content_lower_bigm
    ON posts USING GIN (lower(content) gin_bigm_ops);
