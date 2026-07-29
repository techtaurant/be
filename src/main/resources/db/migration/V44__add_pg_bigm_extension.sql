-- 게시물 제목·본문의 부분 문자열 검색을 위한 pg_bigm 확장.
-- pg_trgm은 LIKE '%X%'에서 쓸 수 있는 트라이그램이 (길이 - 2)개라 두 글자 검색어에서 0개가 되어 인덱스를 사용할 수 없다.
-- pg_bigm은 2-gram이라 두 글자 검색어와 단어 중간 매칭을 모두 인덱스로 처리한다.

-- 확장은 shared_preload_libraries에 pg_bigm이 등록된 상태여야 생성할 수 있고, 등록에는 DB 재시작이 필요하다.
-- 인프라(techtaurant-infra #29)가 선행되지 않으면 이 마이그레이션은 실패한다.
-- 인덱스 생성은 CONCURRENTLY라 트랜잭션 밖에서 실행해야 하므로 V45로 분리했다.
CREATE EXTENSION IF NOT EXISTS pg_bigm;
