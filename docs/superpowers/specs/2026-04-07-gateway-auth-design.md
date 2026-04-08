# 게이트웨이 인증 + 경로 변경 설계

## 개요

Traefik forwardAuth 미들웨어를 통해 게이트웨이 레벨에서 인증을 처리한다. Auth 서비스 경로를 `/auth` → `/api/auth`로 변경하여 FE와 라우팅 충돌을 해소한다. 기존 컨트롤러의 `JwtVerifier` 직접 호출을 `X-User-Id` 헤더로 대체한다.

## 범위

1. `GET /api/auth/validate` 엔드포인트 구현
2. Auth 서비스 경로 `/auth` → `/api/auth` 변경
3. 컨트롤러 리팩터링 — `JwtVerifier` 직접 호출 제거
4. FE URL 일괄 변경 + Vite proxy
5. K8s HTTPRoute + Traefik forwardAuth 미들웨어
6. Traefik CORS 미들웨어

## 1. GET /api/auth/validate

토큰 타입을 자동 판별하여 인증 결과를 응답 헤더로 반환하는 엔드포인트.

### 요청

- `GET /api/auth/validate`
- 클라이언트 요청의 `Authorization: Bearer {token}` 헤더를 Traefik이 그대로 전달

### 토큰 타입 판별

- `bk_` prefix → API Key
- 그 외 → User JWT

### 응답

**200 OK (User JWT):**

- `X-User-Id: {userId}`
- `X-User-Role: {role}`
- `X-Request-Id: {uuid}`

**200 OK (API Key):**

- `X-Provider-Id: {providerId}`
- `X-Request-Id: {uuid}`

**401 Unauthorized:**

- 토큰 없음, 만료, 잘못된 형식, 잘못된 API Key

### 헥사고날 구조

- `ValidateTokenUseCase` 포트 (in) — `validate(token: String): ValidateResult`
- `ValidateResult` — sealed class: `UserResult(userId, role)`, `ProviderResult(providerId)`
- `ValidateTokenService` — JwtVerifier로 JWT 검증 시도, 실패 시 API Key 검증 (keyHash 조회)
- `ValidateController` — GET 핸들러, 결과에 따라 응답 헤더 설정

### API Key 검증 흐름

1. `bk_` prefix 확인
2. SHA-256 해시 계산
3. `ApiKeyRepository.findByKeyHash(hash)` 조회
4. 존재하면 → providerId로 Provider 조회 → ACTIVE 상태 확인
5. 성공 시 `X-Provider-Id` 반환

## 2. 경로 변경 `/auth` → `/api/auth`

### Spring Boot 설정

`application.yml`에 context-path 추가:

```yaml
server:
  servlet:
    context-path: /api/auth
```

### 컨트롤러 매핑 변경

| 컨트롤러                  | 기존 @RequestMapping     | 변경 후                    |
| ------------------------- | ------------------------ | -------------------------- |
| AuthController            | `/auth`                  | 제거 (context-path가 처리) |
| RefreshController         | `/auth`                  | 제거                       |
| ProviderController        | `/auth/provider`         | `/provider`                |
| ApiKeyController          | `/auth/provider/api-key` | `/provider/api-key`        |
| ValidateController (신규) | —                        | `/validate`                |

최종 경로 예시: context-path(`/api/auth`) + controller(`/provider`) = `/api/auth/provider`

### AuthController 콜백 리다이렉트

콜백 후 FE로 리다이렉트하는 경로 `/auth/callback?token=...`은 FE 경로이므로 변경 불필요. 단, `context-path` 적용 후 `redirect:` prefix가 상대 경로로 해석되면 `/api/auth/auth/callback`이 될 수 있으므로, 절대 경로 리다이렉트를 사용해야 한다: `redirect:/auth/callback?token=...` → context-path 밖의 FE 경로로 보내려면 응답에서 직접 `Location` 헤더를 설정하거나, Spring의 `redirect:` 동작을 확인하여 조정한다.

### Google Cloud Console

redirect URI를 변경 필요:

- dev: `http://localhost/api/auth/google/callback`
- prod: `https://baraworlds.com/api/auth/google/callback`

## 3. 컨트롤러 리팩터링

### 변경 대상

- `ProviderController` — GET, POST /register
- `ApiKeyController` — POST, GET, PATCH, DELETE

### 변경 내용

```kotlin
// Before
@RequestHeader("Authorization") authorization: String
val userId = extractUserId(authorization)  // JwtVerifier.verify()

// After
@RequestHeader("X-User-Id") userId: String
```

- 두 컨트롤러에서 `JwtVerifier` 의존성 제거
- `extractUserId()` private 메서드 삭제

### forwardAuth 미적용 엔드포인트

| 엔드포인트                      | 이유                             |
| ------------------------------- | -------------------------------- |
| `GET /api/auth/google/login`    | OAuth 시작, 인증 불필요          |
| `GET /api/auth/google/callback` | Google이 리다이렉트, 인증 불필요 |
| `POST /api/auth/refresh`        | refreshToken 자체가 인증 수단    |
| `GET /api/auth/validate`        | 인증을 수행하는 주체             |

## 4. FE 변경

### URL 변경

| 파일                     | 기존                                                                  | 변경                                                                              |
| ------------------------ | --------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `pages/LoginPage.tsx`    | `/auth/google/login`                                                  | `/api/auth/google/login`                                                          |
| `pages/ProviderPage.tsx` | `/auth/provider`, `/auth/provider/api-key`, `/auth/provider/register` | `/api/auth/provider`, `/api/auth/provider/api-key`, `/api/auth/provider/register` |
| `lib/api.ts`             | `/auth/refresh`                                                       | `/api/auth/refresh`                                                               |

### Vite proxy

```typescript
proxy: {
  '/api/auth': {
    target: 'http://localhost:8081',
    changeOrigin: true,
  },
}
```

context-path가 `/api/auth`이므로 path rewrite 불필요.

## 5. K8s HTTPRoute + Traefik forwardAuth

### Traefik Middleware — forwardAuth

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: auth-forward
  namespace: core
spec:
  forwardAuth:
    address: http://auth:8081/api/auth/validate
    authResponseHeaders:
      - X-User-Id
      - X-User-Role
      - X-Provider-Id
      - X-Request-Id
```

### Traefik Middleware — CORS

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: cors
  namespace: core
spec:
  headers:
    accessControlAllowOriginList:
      - 'http://localhost'
      - 'https://baraworlds.com'
    accessControlAllowMethods:
      - GET
      - POST
      - PATCH
      - DELETE
      - OPTIONS
    accessControlAllowHeaders:
      - Authorization
      - Content-Type
    accessControlMaxAge: 86400
```

### 라우팅 구조

| 경로                   | 대상             | forwardAuth | CORS |
| ---------------------- | ---------------- | ----------- | ---- |
| `/api/auth/google/*`   | auth:8081        | 없음        | 적용 |
| `/api/auth/refresh`    | auth:8081        | 없음        | 적용 |
| `/api/auth/validate`   | auth:8081        | 없음        | 없음 |
| `/api/auth/provider/*` | auth:8081        | **적용**    | 적용 |
| `/api/*`               | 향후 API Service | **적용**    | 적용 |
| `/*`                   | fe:5173          | 없음        | 없음 |

### 헤더 스푸핑 방지

forwardAuth 적용 경로에서는 `/validate` 응답 헤더 값으로 `X-User-Id` 등 내부 헤더를 덮어쓴다. 클라이언트가 보낸 값은 무시된다.

## 6. 관련 문서 업데이트

- `docs/spec/auth/authentication.md` — 엔드포인트 테이블 경로 변경, validate 설명 추가
- `docs/spec/shared/security.md` — "Nginx 헤더 주입" → "Traefik forwardAuth 헤더 주입"으로 업데이트
- `docs/spec/decisions/adr-005` — Traefik forwardAuth 사용 결정 반영
- `CLAUDE.md` — Auth Service 경로 변경 반영
