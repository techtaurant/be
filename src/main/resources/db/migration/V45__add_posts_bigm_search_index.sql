-- flyway:executeInTransaction=false
-- 게시물 제목 검색용 pg_bigm GIN 인덱스. CONCURRENTLY 실패 시 invalid 인덱스가 남을 수 있어
-- 본문 인덱스와 마이그레이션을 분리하고 이름 충돌을 숨기는 IF NOT EXISTS를 사용하지 않는다.
-- 실패 복구 절차는 docs/database-migration-runbook.md를 따른다.

-- 애플리케이션이 lower(컬럼) LIKE ? 로 질의하므로 인덱스도 같은 표현식이어야 매칭된다.
-- gin_bigm_ops는 LIKE만 지원하고 ILIKE는 지원하지 않는다.
CREATE INDEX CONCURRENTLY idx_posts_title_lower_bigm
    ON posts USING GIN (lower(title) gin_bigm_ops);
