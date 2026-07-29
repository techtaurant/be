-- flyway:executeInTransaction=false
-- 게시물 제목·본문 검색용 pg_bigm GIN 인덱스. CONCURRENTLY는 트랜잭션 안에서 실행할 수 없어
-- V44의 확장 생성과 분리했다.

-- 애플리케이션이 lower(컬럼) LIKE ? 로 질의하므로 인덱스도 같은 표현식이어야 매칭된다.
-- gin_bigm_ops는 LIKE만 지원하고 ILIKE는 지원하지 않는다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_title_lower_bigm
    ON posts USING GIN (lower(title) gin_bigm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_content_lower_bigm
    ON posts USING GIN (lower(content) gin_bigm_ops);
