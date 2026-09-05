-- flyway:executeInTransaction=false
-- 미확정 첨부 정리 배치가 매시간 쓰는 조합(status, reference_id IS NULL, created_at_utc 오름차순)에 맞춘 부분 인덱스.
-- idx_attachments_status는 status 단독 인덱스라 created_at_utc 정렬을 지원하지 못하고,
-- TMP가 전체 행의 다수를 차지하면 선택도도 낮다.
--
-- status를 WHERE 조건이 아니라 인덱스 선두 컬럼에 두는 이유는 배치가 status를 bind 파라미터로 보내기 때문이다.
-- jOOQ가 렌더링하는 cast(? as attachment_status)를 플래너가 부분 인덱스의 status = 'TMP' 조건과
-- 같다고 증명하지 못해 seq scan + sort로 되돌아간다. 선두 컬럼이면 같은 식이 인덱스 조건으로 쓰인다.
-- CONCURRENTLY 실패 시 invalid 인덱스가 남을 수 있어 이름 충돌을 숨기는 IF NOT EXISTS를 쓰지 않는다.

CREATE INDEX CONCURRENTLY idx_attachments_unclaimed_status_created_at
    ON attachments (status, created_at_utc)
    WHERE reference_id IS NULL;
