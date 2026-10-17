# Attachment 정합성 CLI

PostgreSQL `attachments` 테이블과 현재 `AWS_S3_BUCKET_NAME` 버킷의 정합성을 배치로 점검한다.
애플리케이션 서버와 Flyway를 실행하지 않으며 DB에는 조회 쿼리만 수행한다.

## 명령

```bash
# DB의 CONFIRMED attachment가 S3에 모두 존재하는지 점검
./gradlew attachmentCli --args="verify --batch-size=500"

# DB에서 참조하지 않는 S3 object 점검(삭제하지 않음)
./gradlew attachmentCli --args="orphan --batch-size=500 --min-age-hours=24"

# orphan object 삭제
./gradlew attachmentCli --args="orphan --delete --confirm-bucket=techtaurant-media-dev --batch-size=500 --min-age-hours=24"
```

배포 이미지에서는 기존 JAR에 `attachment` 명령을 전달한다.

```bash
java -jar app.jar attachment verify --batch-size=500
java -jar app.jar attachment orphan --batch-size=500 --min-age-hours=24
java -jar app.jar attachment orphan --delete --confirm-bucket=techtaurant-media-dev --batch-size=500 --min-age-hours=24
```

## 필요한 환경변수

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- `AWS_REGION`
- `AWS_S3_BUCKET_NAME`: 점검할 버킷. 기본값 없이 반드시 명시한다.

로컬의 `./gradlew attachmentCli` 실행은 저장소 루트의 `.env`를 자동으로 읽는다.
동일한 키가 실제 시스템 환경변수에도 있으면 시스템 환경변수를 우선한다.

AWS access key를 별도로 받는 구조가 아니다. AWS SDK 기본 credential chain을 사용하므로 EC2에서는 기존 instance role을 사용하고,
로컬에서는 허용된 AWS profile 또는 환경변수 인증을 사용한다.

## 안전 장치

- `verify`는 서비스에서 사용하는 `CONFIRMED` attachment만 점검한다. TMP attachment는 S3 lifecycle 정리 대상이므로 제외한다.
- `orphan` 기본 동작은 점검만 수행한다.
- `tmp/` object는 orphan 삭제 대상에서 항상 제외한다.
- 기본 24시간 이내에 생성된 object는 업로드/DB 반영 중일 수 있어 제외한다.
- 삭제에는 `--delete`와 현재 버킷명과 일치하는 `--confirm-bucket`이 모두 필요하다.
- 현재 버킷 기준으로 해석할 수 없는 CONFIRMED attachment 참조가 하나라도 있으면 삭제를 시작하지 않는다.
- 삭제 후보를 모두 수집한 뒤 DB 참조 여부를 배치로 다시 확인하고 삭제한다.
- S3 API 제한에 맞춰 batch size는 최대 1,000으로 제한한다.

종료 코드는 `0`(이상 없음 또는 삭제 완료), `1`(실행 오류), `2`(누락/orphan 발견), `64`(잘못된 인자)다.
