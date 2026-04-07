# Auth Google Login 로그 흐름

## GET /auth/google/login

정상 흐름:

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.buildLoginUrl() |
| `outcome` | `"redirect_to_google"` | AuthController.login() |
| `message` | `"Google 로그인 URL 리다이렉트"` | AuthController.login() |

Wide event 출력 예시:
```json
{
  "correlation_id": "...",
  "request_id": "...",
  "method": "GET",
  "path": "/auth/google/login",
  "status_code": 302,
  "duration_ms": 12,
  "oauth_provider": "google",
  "outcome": "redirect_to_google",
  "level": "info"
}
```

## GET /auth/google/callback

### 성공

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `user_id` | 유저 ID | LoginWithGoogleService.login() |
| `user_email` | 유저 이메일 | LoginWithGoogleService.login() |
| `is_new_user` | `true`/`false` | LoginWithGoogleService.login() |
| `outcome` | `"success"` | AuthController.callback() |
| `message` | `"Google OAuth 콜백 성공"` | AuthController.callback() |

### 실패 — 잘못된 OAuth state

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `error_type` | `"InvalidOAuthStateException"` | AuthExceptionHandler |
| `outcome` | `"invalid_state"` | AuthExceptionHandler |
| `message` | `"OAuth state 검증 실패"` | AuthExceptionHandler |

### 실패 — Google code 교환 실패

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `error_type` | `"GoogleExchangeFailedException"` | AuthExceptionHandler |
| `outcome` | `"exchange_failed"` | AuthExceptionHandler |
| `message` | `"Google code 교환 실패"` | AuthExceptionHandler |

### 실패 — ID token 검증 실패

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `error_type` | `"InvalidIdTokenException"` | AuthExceptionHandler |
| `outcome` | `"invalid_id_token"` | AuthExceptionHandler |
| `message` | `"ID token 검증 실패"` | AuthExceptionHandler |
