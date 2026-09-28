# 게시물 목록 LATEST를 작성일 기준으로 되돌리고 UPDATED(최근 수정순) 정렬 추가

Planner mode: full (task-planner-skill) / QA: prompt-separated self-review (단일 메인 에이전트 런타임)

## Test Case Decision
- [x] Regression (repo): 작성일 순서와 수정일 순서가 엇갈린 두 게시물 → `sort=LATEST` 결과가 작성일 내림차순 — Decision: Required — (지금 RED 이유: 현재 LATEST는 updated_at_utc DESC로 정렬)
- [x] Unit/behavior (repo): 같은 픽스처 → `sort=UPDATED` 결과가 수정일 내림차순 — Decision: Required — (지금 RED 이유: UPDATED 값이 없어 컴파일 실패)
- [x] Regression (repo): LATEST size=1 첫 페이지의 nextCursor로 두 번째 페이지 조회 → 작성일이 더 오래된 게시물 1건 — Decision: Required — (지금 RED 이유: 커서가 updatedAt을 담아 두 번째 페이지가 비어 있음)
- [x] Unit/behavior (repo): UPDATED size=1 첫 페이지의 nextCursor로 두 번째 페이지 조회 → 수정일이 더 오래된 게시물 1건 — Decision: Required — (지금 RED 이유: 컴파일 실패)
- [x] Unit/behavior (repo): 500일 전 작성·방금 수정한 게시물 + `sort=UPDATED, period=MONTH` → 결과에서 제외(기간은 작성일 기준) — Decision: Required — (지금 RED 이유: 컴파일 실패)
- [x] Integration/contract (controller): `GET /open-api/posts?sort=UPDATED` → 200, 방금 다시 저장한 가장 오래된 게시물이 첫 번째 — Decision: Required — (지금 RED 이유: enum 변환 실패로 400)
- [x] Unit/behavior (dto): `PostCursor.from(post, UPDATED, sortValue)` → cursor.createdAt == post.updatedAt, `from(post, LATEST, sortValue)` → cursor.createdAt == post.createdAt — Decision: Skip (repo 커서 페이지네이션 케이스 2건이 같은 경로를 DB까지 포함해 검증)

### Open Test Questions
- [x] 불확실성 없음 (기간 기준·이름·형태는 사용자 답변으로 확정)

## Approval Record
- User approval source: 호출 메시지 자체가 실행 지시("변경하고 ... 추가해줘") → SKILL.md 규칙 3의 실행 승인 carve-out. 모호성 3건은 AskUserQuestion으로 답변받음(정렬 옵션 추가 / 이름 UPDATED / 기간 필터는 작성일 기준 유지).
- Approved scope (지시에 직접 포함): (1) LATEST 정렬을 createdAt 기준으로 변경, (2) updatedAt 기준 정렬 값 UPDATED 추가.
- 지시에 없지만 (1)(2)를 만족하는 데 필요한 수단: 커서 생성/조건의 기준 컬럼 변경, Swagger 파라미터 설명·KDoc 갱신, 테스트.
- 모델이 스스로 추가한 항목: `PostCursor.from(post, sortType)` 2-인자 오버로드 삭제(dead-on-discovery, 아래 근거). 사용자에게 한 줄로 알리고 진행.
- Out-of-scope items: 프론트엔드(techtaurant-fe) 타입/UI 변경, DB 마이그레이션(필요 인덱스 이미 존재), `PostSortType.field` 프로퍼티 정리(아래 Ambiguity Sweep 참고).

## Ambiguity Sweep
| Category | Applies? | Question asked / Assumption recorded |
| --- | --- | --- |
| Wording readings | yes — "필터"가 정렬 옵션인지 조건 파라미터인지 두 읽기 | 질문함 → 사용자: "정렬 옵션 추가". 이름 질문 → "UPDATED". |
| Boundary/edge inputs | yes — 기간 필터 기준일 | 질문함 → 사용자: "작성일 기준 유지"(모든 정렬에서 period는 created_at_utc). |
| Error/guard paths | yes — 알 수 없는 sort 값 | 기존 동작 유지: `@RequestParam` enum 변환 실패 → 400(기존 `@ApiErrorResponses(includeValidationError = true)`). `dailyStatSum`은 UPDATED에도 INVALID_SORT_TYPE을 던지도록 LATEST와 같은 분기에 둠. |
| Target identification | no — 기존 레코드를 지목하는 연산 아님 | — |
| Empty/null/duplicate/concurrent requests | yes — 배포 전 발급된 LATEST 커서 | Assumption: 이전 커서는 updatedAt 값을 담고 있어 배포 직후 진행 중인 페이지네이션은 경계가 한 번 어긋날 수 있다. 커서는 불투명·단명 값이므로 호환 처리를 두지 않는다. |
| State write paths | no — 읽기 경로만 변경, 저장 상태를 쓰지 않음 | `grep -rn "UPDATED_AT_UTC" src/main` → 쓰기 경로(save, updateThumbnailImage)는 그대로. |
| Conflicts with existing similar features | yes — 커밋 d709905(2026-03-13)가 "수정된 글도 상단 노출"을 위해 LATEST를 updatedAt으로 바꿨음 | 사용자에게 알린 뒤 답변받음: 그 목적은 UPDATED로 분리하고 LATEST는 작성일로 되돌린다. 위키 `kb_search_notes` 조회 결과 관련 결정 노트 없음(none found). |
| Containment | yes — `PostSortType`·`findLatestPostIds` 내부만 바꿔 만족 가능 | 채택. 공개 시그니처 변경 없음. `findLatestPostIds`/`latestCursorCondition`은 private이라 정렬 컬럼 파라미터 추가는 호출자 외부에 영향 없음. |
| Removal reach | yes — 관측 가능한 정렬 결과 변경 + 2-인자 `from` 삭제 | `grep -rn "PostSortType.LATEST\|\"LATEST\"" src` → 테스트 3파일(PostRepositoryCustomImplTest 13, PostListReadServiceTest 20, PostControllerTest 1)은 모두 sortType 인자로만 쓰거나 작성 순서=수정 순서인 픽스처라 깨지지 않음. `grep -rn "PostCursor.from(" src` → 호출 1건(PostListReadService:173, 3-인자)뿐이라 2-인자 오버로드는 참조 0 → dead-on-discovery. 외부 소비자 techtaurant-fe(dev 브랜치): `grep -rn LATEST src` 10파일 — 값 "LATEST"를 계속 보내므로 코드 변경 불필요, 화면 정렬 의미만 바뀜(아래 완료 보고에 명시). |
| Scope boundary ("done") | yes | 세 목록 엔드포인트(/open-api/posts, /open-api/users/{id}/posts, /api/users/me/posts)가 공유하는 PostSortType이 바뀌므로 세 곳 모두 UPDATED를 받는다. Swagger 설명 3파일 갱신까지가 완료. FE·마이그레이션은 제외. |
| (관찰) `PostSortType.field` 프로퍼티 | 참조 0(`grep -rn "\.field" src/main src/test`) | 공개 프로퍼티이고 CommentSortType도 같은 패턴이라 escalate — 삭제하지 않고 완료 보고에 남김. |

## Business Goal
게시물 목록의 "최신순"이 작성일이 아닌 수정일로 동작해, 오래된 글을 고치면 새 글처럼 맨 위로 올라오는 현상을 없앤다. 수정된 글을 보고 싶은 사용자를 위해 "최근 수정순"을 별도 정렬 값으로 제공한다.

## Scope
- **In Scope**: PostSortType(LATEST→createdAt, UPDATED 추가), 커서 생성/조건, jOOQ 정렬 쿼리, Swagger 파라미터 설명 3곳, 서비스 KDoc, 테스트.
- **Out of Scope**: FE 변경, DB 스키마, 댓글/링크 정렬.

## Codebase Analysis Summary
- 목록 조회는 `PostReadOpenApiController` → `PostListReadService.getPosts` → `PostRepositoryCustomImpl.findPostsWithConditions`(jOOQ) 경로. LATEST는 `findLatestPostIds`가 `POSTS.UPDATED_AT_UTC DESC, ID DESC`로 정렬하고 커서 조건 `latestCursorCondition`도 같은 컬럼을 쓴다. 통계 정렬(VIEW/LIKE/COMMENT)은 `findStatRankedPostIds`.
- 커서(`PostCursor`)는 `sortType|sortValue|createdAt|id` 형식이며 LATEST일 때 `createdAt` 필드에 updatedAt을 담는다.
- 인덱스: `idx_posts_cursor_utc (created_at_utc DESC, id DESC)`, `idx_posts_updated_at_utc (updated_at_utc DESC, id DESC)` 둘 다 V36에 존재 → 마이그레이션 불필요.
- 아키텍처 컨벤션: 모듈별 `application / entity / dto / infrastructure/in / infrastructure/out` 디렉터리(헥사고날식 in/out 명명), 실행 가능한 경계 규칙 없음(`grep -i archunit build.gradle.kts` 무결과), 선언 문서 없음 → 형제 모듈 형태를 따른다. 이 변경은 새 import·새 계층을 만들지 않는다.

### Relevant Files
| File | Role | Action |
|------|------|--------|
| src/main/kotlin/com/techtaurant/mainserver/post/entity/PostSortType.kt | 정렬 enum | Modify |
| src/main/kotlin/com/techtaurant/mainserver/post/dto/PostCursor.kt | 커서 인코딩/생성 | Modify (+ dead 2-인자 from 삭제) |
| src/main/kotlin/com/techtaurant/mainserver/post/infrastructure/out/PostRepositoryCustomImpl.kt | jOOQ 정렬 쿼리 | Modify |
| src/main/kotlin/com/techtaurant/mainserver/post/application/PostListReadService.kt | KDoc의 sortType 열거 | Modify (주석 1줄) |
| src/main/kotlin/com/techtaurant/mainserver/post/infrastructure/in/PostReadOpenApiControllerDocs.kt | Swagger sort 설명 | Modify |
| src/main/kotlin/com/techtaurant/mainserver/user/infrastructure/in/UserOpenApiControllerDocs.kt | Swagger sort 설명 | Modify |
| src/main/kotlin/com/techtaurant/mainserver/user/infrastructure/in/UserPostControllerDocs.kt | Swagger sort 설명 | Modify |
| src/test/kotlin/com/techtaurant/mainserver/post/infrastructure/out/PostRepositoryCustomImplTest.kt | 저장소 통합 테스트 | Modify (Nested 클래스 추가) |
| src/test/kotlin/com/techtaurant/mainserver/post/infrastructure/in/PostControllerTest.kt | 컨트롤러 통합 테스트 | Modify (테스트 1건 추가) |

### Conventions to Follow
| Convention | Source | Rule |
|-----------|--------|------|
| 정렬 타입별 컬럼 선택 | `PostRepositoryCustomImpl.dailyStatSum` | `when(sortType)`으로 jOOQ Field를 고르고 해당 없는 타입은 `ApiException(PostStatus.INVALID_SORT_TYPE)` |
| 테스트 픽스처 | `PostRepositoryCustomImplTest.createPost/movePostCreatedAt` | 기존 헬퍼 재사용, `@Nested @DisplayName` 그룹, given/when/then 주석, AssertJ |
| 컨트롤러 테스트 | `PostControllerTest.SortTypeTest` | RestAssured, `TypeRef<ApiResponse<CursorPageResponse<...>>>` |
| 주석 | 사용자 code-principles | 한국어 서술형, 번호 매기지 않음 |
| 시간 | CLAUDE.md Temporal Rules | Instant/ZoneOffset.UTC만 사용 |

## Architecture Decisions
| Decision | Choice | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| 정렬 컬럼 선택 위치 | `PostRepositoryCustomImpl`에 private `temporalSortField(sortType)` 추가, `findLatestPostIds`/`latestCursorCondition`이 그 Field를 받음 | `dailyStatSum(sortType)`이 같은 종류의 결정(정렬 타입→jOOQ Field)을 이미 이 파일에서 내리고 있음. 그 선례를 따름 | `findUpdatedPostIds`를 복제(중복), enum이 jOOQ Field를 들고 있기(엔티티에 인프라 타입 유입) |
| 커서 시간 필드 | `PostCursor.from(post, sortType, sortValue)`에서 `UPDATED`일 때만 updatedAt, 그 외 createdAt | 기존 분기 위치 그대로, 조건만 LATEST→UPDATED | — |
| 기간 필터 | 모든 정렬에서 created_at_utc 기준 유지 | 사용자 결정 | 수정일 기준 |

## Design Placement
| New logic | Target module (path) | Reuse candidates + search run | Visibility | Placement rationale |
| --- | --- | --- | --- | --- |
| `PostSortType.UPDATED` | post/entity/PostSortType.kt | 같은 enum 내 LATEST("updatedAt") 값이 곧 이 의미였음 → 값만 분리 | public (API 계약값) | 선례: 같은 enum의 형제 값 |
| `temporalSortField(sortType): TableField<PostsRecord, OffsetDateTime?>` | post/infrastructure/out/PostRepositoryCustomImpl.kt (private) | `grep -n "when (sortType)" PostRepositoryCustomImpl.kt` → `dailyStatSum`이 동일 패턴 | private | 선례 `dailyStatSum`을 따름(정렬 타입→Field 매핑은 이 파일이 소유) |

## API Contracts
### GET /open-api/posts, GET /open-api/users/{userId}/posts, GET /api/users/me/posts
- `sort` (query, optional, default `LATEST`): `LATEST`(작성일 최신순) | `UPDATED`(최근 수정순, 신규) | `VIEW` | `LIKE` | `COMMENT`
- 성공 200 / 응답 스키마·상태 코드·경로 변경 없음. 잘못된 값 → 400(기존).
- 변경되는 관측값: `LATEST` 결과 순서가 updated_at → created_at 기준으로 바뀜(FE 코드 변경 없이 의미 변경). `nextCursor`는 불투명 문자열이며 내부 형식 동일.

## Implementation Todos

### Todo 1: RED 테스트 작성
- **Priority**: 1
- **Dependencies**: none
- **Goal**: Test Case Decision의 Required 6건을 먼저 작성해 현재 코드에서 실패(또는 컴파일 실패)를 관찰한다.
- **Work**:
  - `PostRepositoryCustomImplTest`에 `@Nested @DisplayName("시간 정렬")` 클래스 추가. 픽스처: `olderPost = movePostCreatedAt(createPost(userA), daysAgo = 2)`, `newerPost = movePostCreatedAt(createPost(userA), daysAgo = 1)`, 이후 `postRepository.saveAndFlush(olderPost)`로 olderPost의 updatedAt만 최신으로 만든다. 케이스 5건(LATEST 순서, UPDATED 순서, LATEST 커서 2페이지, UPDATED 커서 2페이지, UPDATED+MONTH 작성일 기준).
  - `PostControllerTest.SortTypeTest`에 `whenSortByUpdated_thenPostsOrderedByUpdatedAtDesc` 추가: `postRepository.saveAndFlush(testPosts[0])` 후 `sort=UPDATED` 요청 → `[testPosts[0], testPosts[2], testPosts[1]]`.
- **Convention Notes**: 기존 헬퍼 재사용, AssertJ `extracting("id").containsExactly(...)`.
- **Verification**:
  - `RED (expected — unimplemented): SPRING_PROFILES_ACTIVE=test ./gradlew test --tests '*PostRepositoryCustomImplTest*' --tests '*PostControllerTest*' -x jacocoTestCoverageVerification → exit=1, > Task :compileTestKotlin FAILED, e: PostRepositoryCustomImplTest.kt:645:45 Unresolved reference: UPDATED`
  - 회귀 2건의 수정 전 실패(LATEST 매핑만 updatedAt으로 임시 복원 후 실행): `PostRepositoryCustomImplTest > 시간 정렬 > 최신순은 수정일이 아니라 작성일 내림차순으로 정렬한다 FAILED (AssertionFailedError at PostRepositoryCustomImplTest.kt:630)`, `최신순 커서는 작성일을 기준으로 다음 페이지를 조회한다 FAILED (AssertionFailedError at PostRepositoryCustomImplTest.kt:678)`; 나머지 3건은 통과.
- **Exit Criteria**: 6건 모두 작성되고 RED 관측 기록됨.
- **Status**: completed

### Todo 2: 프로덕션 변경
- **Priority**: 2
- **Dependencies**: Todo 1
- **Goal**: LATEST=createdAt, UPDATED=updatedAt 정렬과 커서.
- **Work**:
  - `PostSortType`: `LATEST("createdAt")`, `UPDATED("updatedAt")` 추가, 낡은 LATEST KDoc(updatedAt 근거) 삭제. enum KDoc에 두 시간 정렬의 기준을 한 줄로 명시.
  - `PostCursor`: 2-인자 `from(post, sortType)` 삭제(dead-on-discovery). 3-인자 `from`의 `cursorDate`를 `sortType == PostSortType.UPDATED`로 변경. 클래스 KDoc의 LATEST/UPDATED 설명 갱신.
  - `PostRepositoryCustomImpl`: `findPostsWithConditions`의 `when`에 `PostSortType.LATEST, PostSortType.UPDATED ->` 분기; `findLatestPostIds`에 `sortType` 파라미터 추가 후 `temporalSortField(sortType)`로 select/orderBy/fetch 컬럼 결정; `latestCursorCondition(cursor, sortField)`; `dailyStatSum`의 throw 분기에 `UPDATED` 포함; `temporalSortField` 신규(private).
  - `PostListReadService` KDoc `@param sortType` 열거에 UPDATED 추가.
  - Docs 3파일의 sort `@Parameter(description)`을 "정렬 기준 (LATEST: 작성일 최신순, UPDATED: 최근 수정순, VIEW: 조회순, LIKE: 추천순, COMMENT: 댓글순)"으로 갱신.
- **Convention Notes**: 새 import는 `org.jooq.TableField`, `com.techtaurant.mainserver.jooq.tables.records.PostsRecord`, `java.time.OffsetDateTime`(모두 같은 파일이 이미 의존하는 jOOQ/JDK 범위).
- **Verification**: `GREEN: 같은 명령 → exit=0, BUILD SUCCESSFUL in 53s` / JUnit XML: testsuite "시간 정렬" tests="5" failures="0", "정렬 기준별 게시물 로딩 검증" tests="5" failures="0" (기존 4 + UPDATED 1).
- **Exit Criteria**: 6건 GREEN, 기존 두 테스트 클래스 전체 GREEN.
- **Status**: completed

### Todo 3: CI 게이트 전체 실행 + QA
- **Priority**: 3
- **Dependencies**: Todo 2
- **Goal**: 저장소 CI와 동일한 명령으로 검증하고 hunk 매핑을 작성한다.
- **Work**: `./gradlew spotlessCheck`(필요 시 `spotlessApply` 후 재확인) → `SPRING_PROFILES_ACTIVE=test ./gradlew test jacocoTestReport -x jacocoTestCoverageVerification --stacktrace`. 각 게이트 직전 `git rev-parse HEAD`·`git diff HEAD --binary | git hash-object --stdin` 기록. `git add -N . && git diff -U0` hunk 매핑.
- **Verification**: HEAD e07b159, 지문 59863e2f65dd26f6d5089b8b0a590e117b4fbde4(게이트 전후 동일). `./gradlew spotlessCheck` → exit=0. `SPRING_PROFILES_ACTIVE=test ./gradlew test jacocoTestReport -x jacocoTestCoverageVerification --stacktrace` → exit=0, BUILD SUCCESSFUL in 1m 37s, 실행 tests=602 skipped=0 failures=0 errors=0 (XML 134개). `git diff -U0` → 27 hunks / 9 files, unmapped 0, behavior-preserving 0.
- **Exit Criteria**: QA PASS.
- **Status**: completed

## Role Routing
| Todo | Owner | Dependencies | Parallelizable | Context allowed | Context forbidden |
| --- | --- | --- | --- | --- | --- |
| 1, 2 | backend (메인 에이전트) | — / 1 | no (같은 파일 집합) | post 모듈, 테스트 베이스 | FE 내부 |
| 3 | qa (prompt-separated self-review) | 2 | no | 전체 diff, CI 정의 | — |

## QA Matrix
| Gate | Command/artifact | Required | Expected evidence | Owner |
| --- | --- | --- | --- | --- |
| narrow | `./gradlew test --tests '*PostRepositoryCustomImplTest*' --tests '*PostControllerTest*' -x jacocoTestCoverageVerification` | yes | RED→GREEN 러너 출력, build/test-results XML | backend |
| CI-1 | `./gradlew spotlessCheck` | yes (CI verbatim) | exit 0 | qa |
| CI-2 | `SPRING_PROFILES_ACTIVE=test ./gradlew test jacocoTestReport -x jacocoTestCoverageVerification --stacktrace` | yes (CI verbatim) | exit 0, 실행 테스트 수 | qa |
| 외부 체크 | `gh pr checks`로 확인한 체크는 `test` 1개(위 워크플로우) → 로컬 재현 가능 | — | — | — |

## External Research Log
| Question | Skill used | Source-backed conclusion | Plan impact |
| --- | --- | --- | --- |
| (없음 — 로컬 코드로 모두 결정) | — | — | — |

## Halt Conditions
- Scope drift: FE 변경·마이그레이션·다른 정렬 enum 손대기.
- Product decision: `field` 프로퍼티 삭제 여부(보고만).
- Missing command/env: Docker 데몬 중단 시 Testcontainers 게이트 불가 → 재시작 후 재시도.

## Progress Tracking
- Total Todos: 3
- Completed: 3
- Status: Execution complete

## Change Log
- 2026-09-28: Plan created
- 2026-09-28: Todo 1 RED 테스트 6건 작성, Todo 2 프로덕션 변경 7파일, Todo 3 CI 게이트 2개 통과·QA PASS
