## Google OAuth 인증

### 로그인 요청

```
GET /oauth2/authorization/google
```

브라우저에서 위 URL로 이동하면 Google 로그인 페이지로 리다이렉트됩니다.

### 인증 성공

Google 인증 성공 시 프론트엔드 URL로 리다이렉트되며, HttpOnly 쿠키로 토큰이 설정됩니다.

**리다이렉트 URL**: `http://localhost:3000/oauth/callback`

**프론트엔드 라우트**: `/oauth/callback`

**설정되는 쿠키**:

| 쿠키명 | 설명 | 만료 시간 | 속성 |
|--------|------|-----------|------|
| `accessToken` | API 인증용 토큰 | 1시간 | HttpOnly, Secure, SameSite=Lax, Path=/ |
| `refreshToken` | 토큰 갱신용 | 7일 | HttpOnly, Secure, SameSite=Lax, Path=/open-api/auth/refresh |

**프론트엔드 처리 예시** (`/oauth/callback` 페이지):

```javascript
// React 예시
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

export default function OAuthCallback() {
  const navigate = useNavigate();

  useEffect(() => {
    // 쿠키는 자동으로 설정되어 있음 (HttpOnly라 JS에서 접근 불가)
    // 바로 메인 페이지로 이동
    navigate('/');
  }, [navigate]);

  return <div>로그인 처리 중...</div>;
}
```

### 인증 실패

인증 실패 시 에러 페이지로 리다이렉트됩니다.

**리다이렉트 URL**: `http://localhost:3000/oauth/error?error={code}&message={message}`

**프론트엔드 라우트**: `/oauth/error`

**쿼리 파라미터**:

| 파라미터 | 타입 | 설명 | 예시 |
|----------|------|------|------|
| `error` | number | 에러 코드 | `4003` |
| `message` | string | 에러 메시지 (URL 인코딩됨) | `OAuth+인증에+실패했습니다` |

**프론트엔드 처리 예시** (`/oauth/error` 페이지):

```javascript
// React 예시
import { useSearchParams, useNavigate } from 'react-router-dom';

export default function OAuthError() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const errorCode = searchParams.get('error');
  const errorMessage = searchParams.get('message');

  const getErrorDescription = (code) => {
    switch (code) {
      case '4001':
        return '지원하지 않는 로그인 방식입니다.';
      case '4002':
        return '이메일 정보를 가져올 수 없습니다. Google 계정 설정을 확인해주세요.';
      case '4003':
        return '로그인에 실패했습니다. 다시 시도해주세요.';
      case '4004':
        return '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.';
      default:
        return errorMessage || '알 수 없는 오류가 발생했습니다.';
    }
  };

  return (
    <div>
      <h1>로그인 실패</h1>
      <p>{getErrorDescription(errorCode)}</p>
      <button onClick={() => navigate('/login')}>
        다시 로그인하기
      </button>
    </div>
  );
}
```

### API 요청 시 인증

쿠키가 자동으로 포함되므로 별도 설정 불필요. CORS 환경에서는 `credentials: 'include'` 필요.

```javascript
// 브라우저 fetch
fetch('/api/data', {
  credentials: 'include'
})

// axios
axios.get('/api/data', {
  withCredentials: true
})
```

또는 Authorization 헤더 사용:

```
Authorization: Bearer {accessToken}
```

---

## 에러 코드

### PostStatus / TagStatus

게시물 도메인은 2000번대 커스텀 코드를 사용한다. (기존 3000번대는 `JwtStatus`와 겹쳐 2000번대로 이전)

| HTTP Status | Custom Code | 설명 |
|-------------|-------------|------|
| 404 | 2001 | 게시물을 찾을 수 없습니다 |
| 400 | 2002 | 카테고리 깊이는 최대 5단계까지 가능합니다 |
| 400 | 2005 | 유효하지 않은 정렬 타입입니다 |
| 403 | 2006 | 다른 사용자의 게시물을 수정할 수 없습니다 |
| 400 | 2008 | 제목은 필수입니다 |
| 400 | 2009 | 본문은 필수입니다 |
| 400 | 2010 | 태그는 최대 10개까지 설정할 수 있습니다 |

**위치**: `post/enums/PostStatus.kt`, `post/enums/TagStatus.kt`

### OAuthStatus

| HTTP Status | Custom Code | 설명 |
|-------------|-------------|------|
| 400 | 4001 | 지원하지 않는 OAuth Provider입니다 |
| 400 | 4002 | OAuth 응답에서 이메일을 찾을 수 없습니다 |
| 401 | 4003 | OAuth 인증에 실패했습니다 |
| 500 | 4004 | 사용자 정보를 불러오는데 실패했습니다 |

**위치**: `security/oauth/status/OAuthStatus.kt`

---

## 테스트 실행

### 전체 테스트 (jacoco 커버리지 측정 포함)

```bash
./gradlew test
```

jacoco 보고서는 `build/reports/jacoco/test/html/index.html`에서 확인할 수 있습니다.

### 빠른 테스트 (jacoco 제외)

jacoco 커버리지 측정을 건너뛰고 테스트만 실행하려면:

```bash
./gradlew test -x jacocoTestReport
```

이 방법은 커버리지 생성 과정을 생략하여 테스트 실행 시간을 단축합니다.

---

## Code Formatting (Spotless)

### 포맷 검사

```bash
./gradlew spotlessCheck
```

### 자동 포맷 적용

```bash
./gradlew spotlessApply
```

CI에서 spotless 위반 시 PR merge가 차단됩니다.
커밋 전에 `spotlessApply`를 실행하세요.

---

## CI/CD 브랜치 보호 규칙

`main` 브랜치는 브랜치 보호 규칙이 적용되어 있습니다.

### 머지 조건

다음 조건을 모두 만족해야 PR 머지가 가능합니다:

- ✅ **테스트 통과**: `test` 워크플로우 성공
- ✅ **코드 스타일**: Spotless Check 통과
- ✅ **최소 커버리지**: 전체 50% 이상, 변경 파일 50% 이상
- ✅ **브랜치 최신화**: `main`과 동기화 필요

### 동작 방식

| 상황 | 머지 가능 여부 |
|------|----------------|
| 테스트 진행 중 | ❌ 불가 (완료 대기) |
| 테스트 실패 | ❌ 불가 (수정 필요) |
| Spotless 위반 | ❌ 불가 (`spotlessApply` 실행) |
| 커버리지 미달 | ❌ 불가 (테스트 추가) |
| 모든 체크 통과 | ✅ 가능 |

### 설정 방법

브랜치 보호 규칙은 GitHub CLI로 설정되었습니다:

```bash
# 브랜치 보호 규칙 적용
gh api repos/Techtaurant/be/branches/main/protection \
  -X PUT \
  --input - << 'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["test"]
  },
  "enforce_admins": false,
  "allow_force_pushes": false
}
EOF
```

설정 확인: [GitHub Settings](https://github.com/Techtaurant/be/settings/branches)
