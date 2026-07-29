# 데이터베이스 마이그레이션 런북

## `posts` DDL 배포

`V43__drop_posts_content_tsvector.sql`은 `posts` 테이블의 `ACCESS EXCLUSIVE` 락이 필요하다.
게시물 트래픽이 적은 시간에 배포하고, `lock_timeout`으로 실패하면 장기 실행 트랜잭션이
종료된 것을 확인한 뒤 배포를 다시 실행한다. 제한 시간을 늘려 우회하지 않는다.

## Concurrent 인덱스 실패 복구

`CREATE INDEX CONCURRENTLY` 실패 후에는 같은 이름의 invalid 인덱스가 남을 수 있다.
V45 또는 V46 실패 시 다음 쿼리로 상태를 확인한다.

```sql
SELECT indexrelid::regclass AS index_name, indisvalid
FROM pg_index
WHERE indexrelid IN (
    to_regclass('idx_posts_title_lower_bigm'),
    to_regclass('idx_posts_content_lower_bigm')
);
```

`indisvalid = false`인 인덱스만 트랜잭션 밖에서 제거한다.

```sql
DROP INDEX CONCURRENTLY idx_posts_title_lower_bigm;
DROP INDEX CONCURRENTLY idx_posts_content_lower_bigm;
```

실제로 invalid인 인덱스의 구문만 실행해야 한다. 이후 동일 데이터베이스와 스키마를 대상으로
Flyway `repair`를 실행해 실패 이력을 제거하고 배포를 재실행한다. V45와 V46은 인덱스를 하나씩
생성하며 `IF NOT EXISTS`를 사용하지 않으므로, 남은 invalid 인덱스가 정상 인덱스로 오인되지 않는다.
