# S3 사이트맵 운영

공개 게시글을 하루 한 번 XML로 생성해 전용 S3의 `sitemap.xml`에 게시한다.
기존 링크 크롤링 스케줄/데이터 모델과 분리한 `sitemap` 도메인이다.

## 데이터와 실행

- `PUBLISHED` 글의 UUID와 실제 `updated_at_utc`만 단일 쿼리로 조회한다. 비공개·임시저장·삭제 글은 제외한다.
- URL은 `https://techtaurant.com/posts/{uuid}`이며 UTF-8 XML의 `lastmod`는 실제 수정 시각이다.
- 기본 실행 시각은 매일 04:00 Asia/Seoul이다. JVM 및 DB 절대 시각은 UTC로 유지한다.
- 별도 스케줄러를 사용해 기존 링크 크롤링/정리 작업의 실행 스레드를 점유하지 않는다.
- ADMIN 쿠키 인증으로 `POST /admin/sitemap/runs`를 호출하면 동기 생성한다. 성공 시 URL 수·바이트 수·객체 키를 반환한다.
- 정기·수동 실행 모두 같은 PostgreSQL ShedLock을 사용한다. 잠금 중 수동 호출은 409, 정기 호출은 건너뛴다.
- 잠금은 DB 시간을 기준으로 최대 10분, 최소 1분이다. 별도 heartbeat/실행 이력 테이블은 만들지 않는다.
- DB 조회 제한은 30초, S3 API 호출은 각 30초(시도당 10초)이다. 시작 후 90초를 넘기면 업로드를 시작하지 않는다.
- ShedLock의 외부 라이브러리 테이블은 UTC TIMESTAMP 형식을 사용한다.

## 게시와 실패

XML을 완성·검증한 뒤 단일 PutObject로 교체한다. 검증/조회 실패 시 기존 파일을 유지한다.
처음에는 `If-None-Match: *`, 이후에는 조회한 ETag의 `If-Match`로 게시해 중간에 내용이 바뀐 파일의 덮어쓰기를 거부한다.
이는 DB 임대 잠금을 보완하는 조건부 쓰기이며, 임의로 긴 프로세스 정지에 대한 엄격한 fencing 보장은 아니다.
S3 타임아웃은 서버에서 쓰기가 완료됐지만 응답만 유실된 경우도 있으므로, 재시도 전 객체 수정 시각을 확인한다.

최대 50,000개 URL·비압축 50MiB까지 지원한다. 초과하면 실패하고 기존 파일을 유지하며 조용히 잘라내지 않는다.
분할이 필요해지면 동일 `/sitemap.xml`을 sitemapindex로 전환하고 FE 도메인의 하위 경로를 추가하는 후속 작업이 필요하다.
이번 구현에는 인덱스/분할 파일이 없다.

매일 갱신하므로 신규 공개·비공개 전환·삭제는 정상 실행 기준 최대 약 24시간 후 반영된다.
즉시 반영이 필요하면 관리자 실행을 사용한다. 사이트맵은 원문 접근 권한을 대신하지 않는다.
앱이 실행 시각에 꺼져 있으면 누락 회차를 자동 보충하지 않는다. 재기동 후 관리자 실행 또는 다음 정기 실행을 사용한다.

## 설정

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `SITEMAP_ENABLED` | `false` | 활성화 시에만 스케줄러·관리자 API·메트릭 등록 |
| `SITEMAP_BUCKET_NAME` | 빈 값 | 활성화 시 필수, 환경별 전용 버킷 |
| `SITEMAP_SITE_URL` | `https://techtaurant.com` | 공개 FE origin, HTTPS |
| `SITEMAP_CRON` | `0 0 4 * * *` | Asia/Seoul 기준 cron; `-`는 정기 생성만 중지 |

기존 AWS 기본 자격증명 체인/리전을 사용한다. 별도 AWS 키를 추가하지 않는다.
개발은 비공개 개발 버킷, 운영은 운영 버킷을 사용한다.

## FE 연결 계약

운영 origin 예상 주소: `https://techtaurant-sitemap.s3.ap-northeast-2.amazonaws.com/sitemap.xml`.
실제 주소는 infra의 `sitemap_origin_url` 출력으로 확정한다.
FE는 `https://techtaurant.com/sitemap.xml`을 해당 origin으로 서버 측 rewrite하고 XML 응답·상태 코드를 보존한다.
초기에는 S3 `Cache-Control: no-store`를 유지하고 Worker/CDN의 강제 캐시를 적용하지 않는다.
S3 실패를 빈 정상 XML로 바꾸지 않는다. 서버 측 rewrite에는 S3 CORS 설정이 필요하지 않다.
FE robots.txt에 공개 FE 사이트맵 주소를 추가하고 Search Console에도 같은 주소를 제출한다.
FE rewrite/robots/GSC 변경은 프론트엔드 담당 작업이다.

## 배포와 확인

1. infra에서 전용 버킷·IAM·SSM을 준비한다. 기본 비활성 상태로 BE 마이그레이션과 코드를 배포한다.
2. 정상 종료 대기 배포 스크립트를 적용한다. 사이트맵 스케줄러는 종료 시 최대 150초 기다린다.
3. 개발에서 활성화해 공개/비공개/임시저장/삭제 샘플과 lastmod, 관리자 권한, 동시 실행을 검증한다.
4. 운영 설정 활성화 후 ADMIN API로 첫 XML을 생성하고 S3에서 인증 없는 GET 200과 XML을 확인한다.
5. FE rewrite와 robots 연결 후 공개 FE URL을 확인하고 GSC에 제출한다.
6. SRE의 갱신 지연 알림과 다음 정기 실행을 확인한다.

`SITEMAP_ENABLED=false`로 생성과 수동 API를 끌 수 있지만 이미 게시된 파일은 남는다.
잘못된 공개 파일을 즉시 내릴 때는 FE 경로 차단 또는 운영자 권한으로 S3 객체를 제거하는 별도 조치가 필요하다.
앱에는 삭제 권한을 부여하지 않는다.

## 관측

- `sitemap_last_published_timestamp`: S3 객체의 마지막 수정 epoch초. 5분마다 HEAD로 복원하여 앱 재시작/다른 노드의 게시를 반영한다. 객체가 없으면 0.
- `sitemap_generation_failures_total`: 잠금 획득 이후 실패 횟수.
- `sitemap_generation_duration_seconds`: 실행 시간.
- 생성 성공/실패 로그: URL 수, XML 바이트 수, 객체 키 및 예외.

30시간 이상 갱신되지 않은 상태가 10분 지속되면 SRE 알림을 보낸다. 비활성 상태는 메트릭을 노출하지 않는다.
HEAD 실패는 기존 시각을 유지하고 로그를 남기므로, 장기 장애는 갱신 지연 알림으로 드러난다.
수집 자체가 중단된 경우에는 기존 BE scrape 알림이 담당한다.

참고: [Google 사이트맵 지침](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap),
[AWS 조건부 쓰기](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html).
