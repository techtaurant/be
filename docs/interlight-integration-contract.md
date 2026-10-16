# Techtaurant–Interlight 연동 계약 v1

Techtaurant는 로그인·사용자 자격·크레딧 원본을 소유한다. 면접 앱 Interlight는 iframe으로 표시하며 Techtaurant 서버 API를 직접 호출한다. 이 문서는 후속 구현의 계약이며, 현재 배포나 공개 유료 사용 승인을 의미하지 않는다.

## 1. 경계와 공개 조건

- API Gateway, tenant 저장소, Interlight 회원 원본·크레딧 지갑을 추가하지 않는다.
- 브라우저 인증은 쿠키다. 브라우저 Authorization, URL 토큰, localStorage 인증 토큰, 토큰 postMessage를 만들지 않는다.
- 서버 간 호출만 `Authorization: Bearer <service-secret>`을 사용한다. TLS 인증서 검증·timeout을 적용하고 고정 Techtaurant base URL만 호출한다.
- service secret은 배포 비밀 참조로 주입한다. 상수 시간 비교하며 사용자 principal과 별도 service principal로 처리한다. 키 누락·기능 비활성 상태는 fail closed다.
- 기존 로그인 HMAC 키·refresh 정책·영구 토큰을 변경하거나 Interlight RS256 키와 공유하지 않는다.
- Techtaurant accessToken의 서브도메인 공유는 bootstrap에만 사용한다. refreshToken과 원본 전체 Cookie 헤더는 서버 간 전달하지 않는다.
- 실제 prod/dev 부모·iframe·API 호스트, 초기 지급량, 기능별 가격·최대량·실패/부분 사용 비용은 **미정**이다. 운영 값이나 무료 정책을 임의로 정하지 않는다.
- 아래 설정이 모두 확정·검증되기 전 `public_enabled=false`, `paid_execution_enabled=false`를 유지한다. 사설 개발의 익명 모드는 public host에서 허용하지 않는다.

| 설정 계약 | 내용 / 미설정 시 동작 |
| --- | --- |
| Techtaurant base URL, JWKS URL | 고정 HTTPS 주소. 토큰의 jku/x5u 같은 임의 주소를 따라가지 않음 |
| iframe API Origin allowlist | iframe 자체의 정확한 scheme/host/port. bootstrap·변경 요청의 Origin 검사 |
| 부모 Origin allowlist | frame-ancestors·postMessage 검증용. iframe API Origin과 분리 |
| RS256 키, kid, service secret, CSRF 서명키 | 비밀 저장소 참조. 미설정 시 해당 경로 사용 불가 |
| versioned tariff / 기능별 실행 상한 / 임대·최대 작업 시간 | 불변 버전의 서버 정책. 미설정 기능은 예약 거절 |
| 초기 지급·실패 비용·불확실 사용량 처리 정책 | 제품 결정 필요. 테스트 fixture와 운영 정책 분리, 공개 유료 gate 차단 |

## 2. 응답·타입·오류 규칙

Techtaurant JSON 성공·오류는 기존 `ApiResponse<T>`의 `{status, data, message}`를 사용한다. 예: HTTP 200 `{"status":200,"data":{"balance":0,"held":0,"available":0,"unit":"credit"},"message":"OK"}`. `status`는 기존 앱 상태 코드이며 HTTP 상태와 항상 같은 값이라고 가정하지 않는다. 오류의 `data`는 null 또는 오류별 기존 상세 구조다. 새로운 실패 상태는 기존 StatusIfs 정책에 등록한다. message 문자열로 분기하지 않는다.

내부 연동 DTO 필드는 **snake_case**다. DTO 단위로 매핑하며 전역 Jackson 정책을 변경하지 않는다. 아래 응답 표는 wrapper 안의 `data`만 표시한다. 공개 JWKS는 표준 `{"keys":[...]}`이며 wrapper를 씌우지 않는다. Interlight 브라우저 API는 기존 ErrorCode/ApiError 응답 규칙을 유지한다.

| 타입 | 직렬화·제약 |
| --- | --- |
| UUID | 소문자 표준 UUID 문자열. user_id는 Techtaurant users.id |
| credit | JSON 숫자 정수 0..9007199254740991(JavaScript safe integer), DB BIGINT. 부동소수점 연산 금지 |
| 시각 | UTC RFC3339 문자열, 저장 TIMESTAMPTZ, Kotlin Instant |
| hash | SHA-256 소문자 64자리 hex |
| event_id / operation_id / usage_id | 서로 다른 UUID. 재시도 시 같은 값과 같은 본문 사용 |
| scope | JWT에서는 공백 구분 문자열, 세션 상태에서는 문자열 배열 |

HTTP 오류는 400(형식·허용되지 않은 plan), 401(인증 누락·만료·잘못된 서명), 403(현재 이용 금지·scope·Origin/CSRF 실패), 404(사용자/예약 없음 또는 소유자 불일치), 409(멱등 충돌·잔액 부족·예약 상태/한도 충돌), 503(필수 정책/키 미설정 또는 upstream 일시 불가)을 구분한다. 5xx·timeout은 성공 여부 불명일 수 있으므로 새 키로 재실행하지 않는다. 잔액 부족과 멱등 충돌은 서로 다른 앱 상태 코드로 구분한다.

토큰·service secret·Cookie·Authorization·개인 입력 원문을 로그에 넣지 않는다. 운영 로그에는 operation_id/reservation_id/event_id와 비민감 오류 코드만 남긴다. 세션·잔액·정산 응답은 `Cache-Control: no-store`다.

## 3. 서버 간 세션 교환과 브라우저 쿠키

### Techtaurant 발급 API

`POST /internal/interlight/session/exchange`

- service 인증과 `Cookie: accessToken=<원본 값 하나>`가 필요하다. body에 user_id를 받지 않는다.
- Interlight는 bootstrap에서 공유 accessToken만 읽어 전달한다. 중복된 accessToken을 임의로 첫 값으로 선택하지 않는다. 현재 로그인 필터의 만료/영구 토큰 정책을 재사용하며 모호한 다중 유효 사용자 값은 거절한다.
- Techtaurant는 사용자 존재·현재 역할·활성/금지 상태·Interlight 이용 자격을 DB에서 확인한다. 누락/만료는 401, 현재 이용 금지는 403이다.
- HTTP 200 data: `{access_token: string, expires_in: 300}`. 토큰은 서버 간 응답에만 있으며 Techtaurant가 Interlight 호스트 쿠키를 직접 설정하지 않는다.
- 내부 인증이 꺼져 있거나 키가 없으면 접근을 허용하지 않는다. 로그인/refresh 기존 API에는 영향을 주지 않는다.

`GET /open-api/interlight/jwks`는 RSA 공개키만 제공한다. 각 JWK는 `kty=RSA`, `use=sig`, `alg=RS256`, kid/n/e를 포함한다. 현재 서명키와 아직 유효한 토큰 검증에 필요한 이전 공개키를 함께 유지한다. 개인키·로그인 HMAC secret은 응답하지 않는다.

### JWT 고정 계약

| 필드 | 값 / 검증 |
| --- | --- |
| alg / kid | RS256만 허용, 구성된 공개키 집합의 kid 필요 |
| iss | `urn:techtaurant:interlight`, 발급기와 검증기의 고정 expected issuer |
| aud | `interlight-api`, 다른 audience 토큰 거절 |
| sub | users.id UUID, 브라우저 입력 user_id를 대신 사용하지 않음 |
| scope | 일반 사용자 `interlight:use`; 현재 관리자 자격일 때만 `interlight:admin` 추가 |
| iat / exp | 발급 시각 및 정확히 iat+300초, 만료된 토큰 거절 |
| jti | 발급마다 고유 UUID, CSRF 결합 및 세션 구분 |

잔액·refresh token·개인 자료는 claim에 넣지 않는다. Interlight는 고정 JWKS 주소의 제한된 캐시를 사용하고 미지의 kid에 대해 제한된 재조회만 한다. 서명·필수 claim·iss/aud·sub UUID·scope·iat/exp를 검증한다. 무제한 키 조회, 알고리즘 fallback, 검증 실패 시 자체 토큰 발급을 하지 않는다.

### Interlight 브라우저 API

| 메서드·경로 | 요청 | 성공 |
| --- | --- | --- |
| POST `/api/v1/auth/session` | `Content-Type: application/json`, body `{}`, `X-Interlight-Bootstrap: 1`, 공유 accessToken 쿠키, 정확한 iframe Origin | 204, 아래 두 쿠키 설정. 토큰 body 없음 |
| GET `/api/v1/auth/session` | 전용 인증 쿠키 | 200 `{user_id: UUID, scopes: string[], expires_at: timestamp}` |
| DELETE `/api/v1/auth/session` | 전용 인증 쿠키 + 아래 Origin/CSRF | 204, 인증·CSRF 쿠키를 함께 만료 |

POST는 Techtaurant 교환 결과를 검증한 뒤 아래 쿠키를 설정한다. 다른 사용자로 bootstrap하면 기존 두 쿠키를 함께 교체한다. 실패하면 stale 두 쿠키를 삭제하고 401/403/503을 구분한다. upstream token·사용자 cookie를 브라우저 body/postMessage로 노출하지 않는다.

| 쿠키 | 속성 |
| --- | --- |
| `__Host-interlightAccessToken` | Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=300; **Domain 생략** |
| `__Host-interlightCSRF` | Secure; SameSite=Lax; Path=/; Max-Age=300; **Domain 생략**, iframe JS 읽기 위해 HttpOnly 생략 |

로그아웃 삭제는 같은 이름·Path·Secure·SameSite 및 Domain 생략으로 Max-Age=0을 설정한다. 중복 인증·CSRF 쿠키나 중복 CSRF 헤더는 모호하게 선택하지 않고 거절한다.

CSRF 값은 서버 비밀로 서명한 nonce이며 JWT의 jti와 토큰 지문(SHA-256)에 결합한다. 변경 요청은 `X-Interlight-CSRF`와 읽기 가능한 CSRF 쿠키의 일치뿐 아니라 서명·현재 jti/토큰 지문·유효기간을 서버에서 검증한다. 재bootstrap/토큰 교체 시 재발급하고 이전 값은 사용할 수 없다.

상태 변경 POST/PATCH/PUT/DELETE와 session 삭제는 정확한 iframe Origin과 CSRF를 요구한다. bootstrap은 아직 세션이 없으므로 Origin+JSON+custom header로 보호하며 이 경로만 기존 세션 CSRF 요구를 면제한다. 누락/`null`/허용되지 않은 Origin은 403이다. CORS가 필요하면 credentials와 정확한 출처만 허용하며 wildcard를 사용하지 않는다. 같은 사이트의 서브도메인이라는 이유로 Origin 검사를 생략하지 않는다.

iframe 내부 fetch의 Origin은 **Interlight 자신**이다. Techtaurant 부모 Origin은 CSP frame-ancestors와 postMessage의 exact origin+source 검사에만 사용한다. 부모와 iframe은 auth-required / refresh-result / logout 등 비밀 없는 메시지만 주고받는다. Techtaurant FE의 기존 refresh 단일 실행·재시도 경로를 재사용하고 refreshToken을 Interlight로 보내지 않는다.

JWT 로컬 검증은 최대 300초의 폐기 지연이 있다. 부모 logout은 로컬 쿠키·캐시 삭제로 연결하지만 이미 복사된 JWT의 즉시 전역 폐기를 보장하지 않는다. 새 유료 실행은 아래 예약에서 현재 사용자 자격을 다시 확인한다. 이미 승인된 비용 정산은 로그아웃 이후에도 service 권한으로 처리한다.

## 4. 크레딧 조회·운영자 지급

`GET /internal/interlight/credits/{user_id}`: service 인증 필요. HTTP 200 data `{balance: credit, held: credit, available: credit, unit:"credit"}`. `available=balance-held`이며 writer 기준으로 읽는다. 존재하는 사용자에게 지갑이 없으면 모두 0이고 조회로 지급하지 않는다. Interlight 브라우저 proxy는 principal의 UUID만 사용한다.

`POST /admin/interlight/credits/grants`: 기존 관리자 사용자 인증·권한만 사용한다. service secret만으로 관리자가 되지 않는다. 요청 `{event_key: UUID, user_id: UUID, amount: positive credit, reason: nonempty string}`. HTTP 200 data `{event_key, user_id, amount, balance, held, available}`. 지갑 생성/증액과 GRANT 원장을 한 트랜잭션에 기록한다. 자동 초기 지급·결제 충전은 추가하지 않는다.

같은 grant event_key와 같은 정규화 요청에는 최초 응답을 재사용한다. 다른 user/amount/reason에는 409다. event_key는 지급 이벤트 UUID이며 수신함 event_id로도 사용한다. 원장 키는 GRANT namespace로 구분하고 금액·사유의 길이/범위는 입력 검증한다. 관리자 권한은 매 요청 현재 상태를 확인한다.

## 5. 사용량을 먼저 예약하고 실제 비용을 확정한다

모든 `/internal/interlight/credits/**`, `/internal/interlight/reservations/**`는 같은 고정 service principal에만 허용한다. user_id는 인증된 Interlight principal에서 얻으며 예약·정산·조회에서 service/user/operation 소속을 일관되게 대조한다. 브라우저가 보낸 임의 사용자 헤더는 신뢰하지 않는다.

### 예약

`POST /internal/interlight/reservations`

요청 `{operation_id: UUID, user_id: UUID, feature: string, plan: Plan, request_hash: hash}`.

- `Plan={calls: CallLimit[]}`. `CallLimit={provider:string, model:string, max_calls:positive integer, max_input_tokens:integer, max_output_tokens:integer, max_audio_ms:integer, max_characters:integer}`. max_calls는 양의 정수이며 나머지 상한은 음이 아닌 정수다. 해당 없는 단위는 0이다.
- plan에는 실제 가능한 제공자 호출 수·입출력·재시도·fallback 상한을 모두 포함한다. model/provider/feature 조합과 상한은 서버 allowlist 정책으로 확인한다. 클라이언트의 임의 max_amount를 신뢰하지 않는다.
- feature는 면접 질문/답변/피드백/TTS, 이력서, 채용공고, 페르소나, 기업 조사, 실전 면접 embedding별로 정책에 등록한다. 등록되지 않거나 가격/상한이 없는 기능은 503으로 거절한다.
- request_hash는 Interlight의 정규화된 원본 작업 입력 지문이다. Techtaurant는 user/feature/plan/request_hash를 포함한 전체 요청의 canonical hash를 별도로 계산·저장한다. operation_id 재사용 시 원본 입력 또는 plan이 달라져도 409다.
- 현재 사용자 활성·기능 자격을 확인한 다음, 서버 정책으로 계산한 **최대 비용**을 reserved로 잡는다. 당시 tariff_version은 불변이다. 가격·반올림·상한 실제 값은 미정이며 fixture를 운영 값으로 사용하지 않는다.
- 지갑 행 잠금/조건부 UPDATE로 `balance-held>=reserved`를 원자 검사한다. held 증가, ACTIVE 예약, RESERVE 원장을 함께 커밋한다. 지갑 없음/부족은 409이며 사용자 입력금액으로 우회하지 않는다.

HTTP 200 data `{reservation_id:UUID, reserved:credit, tariff_version:string, expires_at:timestamp}`. 동일 operation/본문 재시도는 최초 예약 응답이다. DB 트랜잭션 안에서 AI 제공자나 원격 서버를 호출하지 않는다.

### 정산

`POST /internal/interlight/reservations/{reservation_id}/capture`

요청 `{event_id:UUID, operation_id:UUID, user_id:UUID, usage:Usage}`.

- `Usage={items:UsageItem[]}`. `UsageItem={usage_id:UUID, provider:string, model:string, outcome:string, input_tokens:integer|null, output_tokens:integer|null, audio_ms:integer|null, characters:integer|null}`.
- 원시 계측은 음이 아닌 정수이며 음수는 400으로 거절한다. null은 미측정으로 보존한다.
- outcome은 SUCCESS/FAILED/UNKNOWN이다. 사용되지 않는 계측 단위도 정책이 해당 없음을 확인해야 하며, 미계측 null/UNKNOWN을 임의 0으로 변환하지 않는다. 실패/부분 응답 비용은 제품 정책이 확정되기 전 확정 정산하지 않는다.
- usage_id는 실제 제공자 호출 하나의 고유 식별자다. 같은 호출이 여러 사용자 메시지/수신자에 연결되어도 한 번만 청구한다. operation 안에서 중복 usage_id를 거절하고 다른 operation/예약으로 재청구되지 않게 영속 유일성을 보장한다.
- 예약 당시 tariff_version으로 원시 사용량을 정수 크레딧 actual로 계산한다. 제공자·모델·호출 수·각 사용량이 승인 plan 범위 안인지 검증한다. 이미 credit으로 계산한 클라이언트 금액을 신뢰하지 않는다.
- ACTIVE이고 완전한 계측일 때 `actual<=reserved`, `balance-=actual`, `held-=reserved`, CAPTURED 상태, CAPTURE 원장과 수신 영수증을 한 트랜잭션으로 기록한다. 사용하지 않은 예약분은 이 정산에서 held 해제로 반영한다.
- RECONCILING, 만료 예약, actual 초과, 모르는 단가/계측은 맹목 차감하거나 무료 release하지 않는다. 409와 보류 상태로 응답하며 아래 증거 확인 절차로 해결한다. 음수 잔액·추가 무단 청구를 허용하지 않는다.

HTTP 200 data `{receipt_id:UUID, captured:credit, balance:credit, held:credit}`. receipt_id는 저장한 event_id다. 같은 event/본문은 최초 영수증을 그대로 반환한다. 다른 이벤트로 이미 종료된 예약에 추가 정산은 409다.

### 실행 전 해제·상태 조회

`POST /internal/interlight/reservations/{reservation_id}/release`: 요청 `{event_id:UUID, operation_id:UUID, user_id:UUID, reason:string, execution_not_started:true}`. Interlight의 영속 실행 상태가 제공자 호출 전 취소를 증명할 때만 호출한다. 단순 timeout·연결 종료·사용자 창 닫음은 미실행 증거가 아니다.

ACTIVE이며 미실행이 확인되면 RELEASED, `held-=reserved`, balance 불변, RELEASE 원장·수신 영수증을 원자 반영한다. HTTP 200 data `{receipt_id:UUID, released:credit, balance:credit, held:credit}`. CAPTURED 해제·다른 종료 이벤트는 409. 중복 이벤트는 최초 결과만 재생한다. 실행 후 환불 정책은 이 API 범위 밖이다.

`GET /internal/interlight/reservations/{reservation_id}?user_id=<UUID>&operation_id=<UUID>`: HTTP 200 data `{reservation_id, operation_id, user_id, status, reserved, captured, tariff_version, expires_at, receipts:Receipt[]}`. `Receipt={event_id:UUID, action:string, data:object}`이며 해당 예약의 저장된 결과만 반환한다. 존재/소속 불일치는 404다. 응답 유실 시 이 조회로 먼저 복구한다.

### 임대 갱신·불확실 상태

`POST /internal/interlight/reservations/{reservation_id}/renew`: 요청 `{event_id:UUID, operation_id:UUID, user_id:UUID, expires_at:timestamp, progress:{step_id:UUID, observed_at:timestamp}}`. 현재 ACTIVE, 아직 만료 전, 승인된 진행 단계/시간 근거와 최대 작업 시간 안에서만 expires_at을 갱신한다. HTTP 200 data `{receipt_id:UUID, reservation_id:UUID, expires_at:timestamp}`. 같은 이벤트 재전송은 저장된 결과, 다른 본문은 409다. 갱신은 금전 원장을 추가하지 않는다.

기한이 지나면 ACTIVE→RECONCILING으로 전환한다. balance/held는 그대로 유지하고 만료만으로 자동 환불·해제하지 않는다. RECONCILING에서 새 AI 실행이나 일반 renew는 금지한다. 전용 recovery 처리에서 영속 operation/outbox/usage 증거를 확인한 뒤에만 다음 전이가 가능하다.

- 실사용 완전 확인, plan/예약 한도 이내: 예약 당시 가격으로 CAPTURED 및 정상 capture 원장/영수증을 원자 기록.
- 실행 전 취소 증거 확인: RELEASED 및 release 원장/영수증을 원자 기록.
- 사용량 불명·한도 초과·정책 미정: RECONCILING 유지, 운영 확인. 임의 0원/추가 차감 금지.

일반 capture/release가 RECONCILING을 무조건 처리하도록 넓히지 않는다. B14의 복구 경로는 동일 정산 트랜잭션을 재사용하되 증거 검사와 별도 제한된 recovery 권한을 요구한다. 원격 recovery endpoint 공개 여부는 구현 전에 계약 변경으로 명시한다. 현재 v1에는 범용 사용자/공개 강제 정산 endpoint가 없다.

## 6. 멱등성과 원장 불변식

멱등 키 범위는 예약 operation_id, 정산/해제/갱신 event_id, 운영 지급 event_key다. canonical hash는 action·service·예약·사용자·operation과 모든 의미 있는 필드를 포함한다. JSON 키 순서를 정렬하고 정수·UTC·UUID 표현을 정규화하며 usage items는 usage_id 순으로 정렬한다. 원래 순서가 의미 있는 plan.calls 순서는 유지한다. 추가/알 수 없는 필드는 400으로 거절하여 해시에서 조용히 빠지지 않게 한다.

같은 키/같은 canonical payload는 이미 저장된 최초 응답을 반환한다. 같은 키/다른 payload 또는 다른 action은 409이며 두 번째 차감을 하지 않는다. 네트워크 재시도는 동일 키·불변 payload·제한된 backoff를 사용한다. 401/403은 권한 문제, 409는 계약/상태 문제로 분리하고 새 event로 무조건 재시도하지 않는다.

| 사건 | balance 증감 | held 증감 | 예약 상태 |
| --- | ---: | ---: | --- |
| GRANT | +amount | 0 | 해당 없음 |
| RESERVE | 0 | +reserved | ACTIVE |
| CAPTURE | -actual | -reserved | CAPTURED |
| RELEASE | 0 | -reserved | RELEASED |
| 임대 만료 | 0 | 0 | RECONCILING |

항상 `0<=held<=balance`, `available=balance-held`, `0<=captured<=reserved`다. balance/held/available/reserved/captured/grant amount와 금액 합산 결과는 모두 위 safe integer 기술 상한을 검사하고 DB check constraint로도 강제한다. 이는 가격 정책이 아니라 직렬화 정밀도 제약이다. 동시 요청에서 키 유일성과 지갑·예약 잠금을 DB가 보장한다. 지갑 금액은 원장 증감 합계, held는 ACTIVE/RECONCILING 예약 잔여분과 운영 대조한다. 도중 실패 시 지갑·예약·원장·영수증 모두 rollback한다.

## 7. 저장 모델과 사용자 소유권

기본 테이블은 다음과 같다. PK/FK/UNIQUE/check constraint와 jOOQ 생성물도 각 schema PR diff 예산에 포함한다. status/kind/action 같은 닫힌 값은 앱 enum과 VARCHAR 저장으로 관리한다.

| 테이블 | 필수 기본 필드 |
| --- | --- |
| interlight_credit_wallets | user_id UUID PK/FK(users.id), balance BIGINT, held BIGINT, updated_at TIMESTAMPTZ |
| interlight_usage_reservations | id UUID PK, user_id UUID FK, operation_id UUID UNIQUE, reserved BIGINT, captured BIGINT, status VARCHAR, tariff_version VARCHAR, expires_at TIMESTAMPTZ |
| interlight_credit_ledger | id UUID PK, user_id UUID FK, reservation_id UUID NULL/FK, event_key VARCHAR UNIQUE, kind VARCHAR, delta_balance BIGINT, delta_held BIGINT, created_at TIMESTAMPTZ |
| interlight_usage_receipts | event_id UUID PK, user_id UUID FK NOT NULL, reservation_id UUID NULL/FK, payload_hash VARCHAR, receipt JSONB, created_at TIMESTAMPTZ |

예약 재시도·지급 멱등성·usage 중복 검증에는 원래 schema 이슈의 최소 필드만으로 부족하다. 후속 schema에서 다음 보완을 포함해야 하며 500줄을 넘으면 독립 schema issue/PR로 나눈다. 생성 코드를 수작업 절단하지 않는다.

- reservations: canonical request_hash VARCHAR, request_payload JSONB, created_at TIMESTAMPTZ. 원본 feature/plan/입력 지문과 당시 응답·작업 시작 시각을 보존한다. 현재 인증된 service principal은 고정 Interlight 하나이므로 service_id 컬럼을 추가하지 않는다.
- usage_receipts: 위 user_id 및 nullable reservation_id로 지급 수신함도 재사용한다. GRANT는 reservation_id=null, capture/release/renew는 해당 사용자 예약 FK를 반드시 요구한다. receipt JSONB에 action·정규화 요청·최초 결과를 보존하고 payload_hash로 충돌을 판정한다. 원장에 중복 영수증 필드를 추가하지 않는다.
- interlight_usage_charge_claims: usage_id UUID PK, reservation_id UUID FK, event_id UUID FK(usage_receipts.event_id). capture 트랜잭션에서 영수증과 함께 등록하여 같은 제공자 호출의 다른 이벤트/예약 재청구를 막는다. 별도 schema 분할이 필요하면 구현 전에 이슈로 추적한다. JSON 배열 조회나 메모리 집합만으로 동시 중복을 막았다고 주장하지 않는다.

Interlight에는 개인 root 자료의 owner_user_id(users.id UUID)를 저장한다. 자식 자료·이미지·벡터·worker·WebSocket/reconnect까지 root 소유자를 검증한다. 기존 소유자 불명 NULL 자료는 격리하며 최초 로그인 사용자에게 양도하지 않는다. admin 모델/전역 사용량 제어는 interlight:admin scope를 요구한다.

Interlight billing operation, usage links, durable outbox는 예약·이벤트·usage 식별자와 불변 payload를 보존한다. 사용량과 outbox를 같은 로컬 트랜잭션에 저장한 다음 원격 정산한다. CancelledError/GeneratorExit·응답 유실에서도 이미 시작한 제공자 호출을 무료로 잃지 않도록 worker가 복구한다. 최대 호출·출력 예산을 벗어난 후속 AI 실행은 중단한다.

## 8. 계약 검토와 공개 gate

이 문서는 B01의 계약만 고정한다. 실제 구현 완료·운영 설정 검증을 대신하지 않는다. B02–B14 및 I02–I04/I18은 이 계약과 DTO/오류/쿠키/상태 전이를 대조한다.

- 인증 교차 검토: 서버 간 exchange wrapper/snake_case, 고정 iss/aud/RS256/TTL, bootstrap header, CSRF 쿠키·헤더와 Origin 용도가 일치해야 한다.
- 정산 교차 검토: 각 요청/응답의 UUID·credit·UTC 타입, 멱등 키/해시, unknown usage와 만료 보류, 원장 증감이 일치해야 한다.
- 미정 정책 검토: 가격·초기 지급·실패 비용·실제 호스트에 임의 운영 값을 넣지 않았는지 확인한다. fixture 정책은 운영 공개 flag를 활성화하지 않는다.
- 공개 전 실환경 gate: HTTPS ingress·정확한 Origin/CORS/CSP·Secure 쿠키·키/secret 주입·소유권·전체 AI admission·영속 계측/outbox/recovery·가격 정책을 검증한 뒤에만 F03/I31에서 공개를 허용한다.
