-- 게시물 전문 검색을 tsvector에서 LIKE 부분 일치로 전환하면서 죽은 tsvector 자산을 제거한다.
-- content_tsvector는 어디서도 읽지 않으면서 매 INSERT/UPDATE마다 to_tsvector 계산 비용만 발생시킨다.

-- ACCESS EXCLUSIVE 락을 바로 얻지 못하면 트래픽을 DDL 뒤에 세우지 않고 배포를 실패시킨다.
SET LOCAL lock_timeout = '5s';

-- 삭제 순서를 지켜야 한다. DROP COLUMN은 딸린 인덱스를 함께 정리하지만 트리거는 남긴다.
-- 컬럼을 먼저 지우면 남은 트리거가 다음 게시물 저장에서
-- ERROR: record "new" has no field "content_tsvector" 로 실패한다.
DROP TRIGGER IF EXISTS trigger_update_posts_content_tsvector ON posts;
DROP FUNCTION IF EXISTS update_posts_content_tsvector();
DROP INDEX IF EXISTS idx_posts_content_tsvector;
ALTER TABLE posts DROP COLUMN IF EXISTS content_tsvector;
