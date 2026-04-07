# Auth Refresh Token 로그 흐름

## POST /auth/refresh

### 성공

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `user_id` | 유저 ID | RefreshTokenService.refresh() |
| `user_email` | 유저 이메일 | RefreshTokenService.refresh() |
| `token_family` | 토큰 패밀리 ID | RefreshTokenService.refresh() |
| `outcome` | `"token_refreshed"` | RefreshTokenService.refresh() |
| `message` | `"토큰 갱신 성공"` | RefreshTokenService.refresh() |

Wide event 출력 예시:
```json
{
  "correlation_id": "...",
  "request_id": "...",
  "method": "POST",
  "path": "/auth/refresh",
  "status_code": 200,
  "duration_ms": 15,
  "user_id": "user-123",
  "user_email": "user@example.com",
  "token_family": "family-uuid",
  "outcome": "token_refreshed",
  "level": "info"
}
```

### 성공 (Grace Period 사용)

동시 요청으로 이전 Refresh Token이 30초 이내에 재사용된 경우.

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `user_id` | 유저 ID | RefreshTokenService.refresh() |
| `user_email` | 유저 이메일 | RefreshTokenService.refresh() |
| `token_family` | 토큰 패밀리 ID | RefreshTokenService.refresh() |
| `grace_period_used` | `true` | RefreshTokenService.refresh() |
| `outcome` | `"token_refreshed"` | RefreshTokenService.refresh() |
| `message` | `"토큰 갱신 성공"` | RefreshTokenService.refresh() |

### 실패 — Refresh Token 재사용 감지

이미 사용된 Refresh Token이 Grace Period 이후에 다시 사용된 경우. 토큰 탈취로 간주하여 해당 token family 전체를 무효화한다.

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `user_id` | 유저 ID | RefreshTokenService.refresh() |
| `token_family` | 토큰 패밀리 ID | RefreshTokenService.refresh() |
| `reuse_detected_family` | 무효화된 패밀리 ID | RefreshTokenService.refresh() |
| `outcome` | `"reuse_detected"` | RefreshTokenService.refresh() |
| `message` | `"Refresh Token 재사용 감지 — family 무효화"` | RefreshTokenService.refresh() |

### 실패 — 잘못된 토큰 (서명/만료/형식)

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `error_type` | `"InvalidTokenException"` | AuthExceptionHandler |
| `outcome` | `"invalid_token"` | AuthExceptionHandler |
| `message` | `"토큰 검증 실패"` | AuthExceptionHandler |
