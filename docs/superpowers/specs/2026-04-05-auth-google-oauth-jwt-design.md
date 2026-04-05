# Auth Service: Google OAuth + JWT 설계

## 개요

Bara World Auth Service의 첫 기능 구현. Google OAuth 로그인을 통해 사용자를 인증하고 자체 RS256 JWT를 발급한다. React(Vite) 기반 프론트엔드를 `clients/web/`에 정식 구조로 함께 세팅하여 end-to-end 로그인 플로우를 검증한다.

## 범위

### 포함

1. **JWT 모듈** — RS256 발급/검증, 환경변수 기반 키 관리
2. **Google OAuth 플로우** — authorization code → ID token 검증 → User upsert → 자체 JWT 발급
3. **User MongoDB 저장** — 최소 필드 (googleId, email, name, role, createdAt)
4. **프론트엔드** — React + Vite + TypeScript + Tailwind, 로그인/콜백/토큰 표시 페이지

### 제외 (후속 작업 명시)

- Refresh Token rotate
- JTI 1회용 블랙리스트 (Redis)
- JWKS 엔드포인트 (`/auth/.well-known/jwks.json`) — Kafka 토큰 도입 시 함께 구현
- 로그아웃 (서버 측 토큰 무효화)
- `/validate` 엔드포인트 (Nginx `auth_request`용) — API Service 스캐폴딩 시 함께 구현
- Provider 토큰 관련 기능 전체
- Kafka Access Token 발급
- User의 확장 필드 (`allowed_agents`, `telegram_id`, `link_token`, `link_token_expiry`)
- Nginx 리버스 프록시 설정 — `/validate` 구현 시 함께 도입
- MongoDB 통합 테스트 (Testcontainers) — 별도 작업으로 일괄 도입

## 아키텍처

### 컴포넌트 위치

```
apps/auth/                          # 기존 Spring Boot 스캐폴딩에 추가
└── src/main/kotlin/com/bara/auth/
    ├── domain/
    │   └── model/User.kt
    ├── application/
    │   ├── port/in/command/LoginWithGoogleUseCase.kt
    │   ├── port/out/
    │   │   ├── UserRepository.kt
    │   │   ├── GoogleOAuthClient.kt
    │   │   ├── OAuthStateStore.kt
    │   │   └── JwtIssuer.kt
    │   └── service/command/LoginWithGoogleService.kt
    ├── adapter/
    │   ├── in/rest/AuthController.kt
    │   ├── out/persistence/
    │   │   ├── UserDocument.kt
    │   │   ├── UserMongoDataRepository.kt
    │   │   └── UserMongoRepository.kt
    │   └── out/external/
    │       ├── GoogleOAuthHttpClient.kt
    │       ├── Rs256JwtAdapter.kt
    │       └── RedisOAuthStateStore.kt
    └── config/
        ├── JwtProperties.kt
        ├── GoogleOAuthProperties.kt
        └── RestClientConfig.kt

clients/web/                        # 신규 React SPA
├── package.json
├── pnpm-lock.yaml
├── vite.config.ts
├── tsconfig.json
├── tsconfig.node.json
├── tailwind.config.ts
├── postcss.config.js
├── index.html
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── index.css
    ├── pages/
    │   ├── LoginPage.tsx
    │   ├── CallbackPage.tsx
    │   └── MePage.tsx
    ├── lib/
    │   ├── auth.ts
    │   └── jwt.ts
    └── __tests__/
        ├── auth.test.ts
        └── jwt.test.ts
```

### 헥사고날 원칙

- **도메인 모델(`User`)**: MongoDB/Spring 어노테이션 없는 순수 Kotlin 데이터 클래스
- **Port**: 모든 외부 의존성(DB, Google, Redis, JWT 라이브러리)은 `application/port/out`의 interface로 추상화
- **Adapter**: 기술 구현은 `adapter/out/*`에 격리. 도메인/애플리케이션 레이어는 어댑터를 알지 못함

## JWT 모듈

### 알고리즘 및 클레임

- **알고리즘**: RS256 (비대칭, 공개키 검증 가능)
- **발급자**: `bara-auth` (고정)
- **대상**: `bara-world` (고정)
- **만료**: 3600초 (1시간)

**클레임**:

```json
{
  "iss": "bara-auth",
  "sub": "<internal_user_id>",
  "aud": "bara-world",
  "iat": 1712300000,
  "exp": 1712303600,
  "jti": "<uuid_v4>",
  "email": "user@example.com",
  "role": "USER"
}
```

`jti`는 이번 iteration에서 클레임에만 포함하고 블랙리스트 검증은 하지 않는다. 후속 작업에서 Redis 기반 1회용 검증 추가 예정.

### 키 관리

환경변수에 **Base64 인코딩된 PEM**으로 저장하여 로컬/Docker Compose/K8s에서 동일한 방식으로 주입한다.

**환경변수**:

- `BARA_AUTH_JWT_PRIVATE_KEY` — base64(RSA private key PEM)
- `BARA_AUTH_JWT_PUBLIC_KEY` — base64(RSA public key PEM)

**키 생성 (수동, 1회)**:

```bash
openssl genrsa -out jwt-priv.pem 2048
openssl rsa -in jwt-priv.pem -pubout -out jwt-pub.pem
echo "BARA_AUTH_JWT_PRIVATE_KEY=$(base64 < jwt-priv.pem | tr -d '\n')"
echo "BARA_AUTH_JWT_PUBLIC_KEY=$(base64 < jwt-pub.pem | tr -d '\n')"
```

출력을 `.env` 파일에 붙여넣는다. 생성된 PEM 파일은 커밋하지 않고 안전한 위치에 보관한다 (혹은 폐기 후 필요 시 재생성).

**`.env` 로드**: `me.paulschwarz:spring-dotenv` 라이브러리 사용. 로컬에서는 `.env` 자동 로드, CI/운영에서는 무시 (이미 환경변수 주입되므로).

### 포트

```kotlin
interface JwtIssuer {
    fun issue(user: User): String
}

interface JwtVerifier {
    fun verify(token: String): JwtClaims
}

data class JwtClaims(
    val userId: String,
    val email: String,
    val role: String,
)
```

### 어댑터

`Rs256JwtAdapter`가 `JwtIssuer`, `JwtVerifier` 두 인터페이스를 동시 구현.

- 라이브러리: **`com.auth0:java-jwt:4.4.0`**
- 키 로딩: `JwtProperties`에서 base64 decode → PEM 파싱 → `RSAPrivateKey`/`RSAPublicKey` 객체 생성 (`KeyFactory` + `PKCS8EncodedKeySpec`/`X509EncodedKeySpec`)
- 검증 실패(만료, 서명 불일치, issuer/audience 불일치) 시 `InvalidTokenException` throw

### ConfigurationProperties

```kotlin
@ConfigurationProperties(prefix = "bara.auth.jwt")
data class JwtProperties(
    val issuer: String,
    val audience: String,
    val expirySeconds: Long,
    val privateKeyBase64: String,
    val publicKeyBase64: String,
)
```

`application.yml`:

```yaml
bara:
  auth:
    jwt:
      issuer: bara-auth
      audience: bara-world
      expiry-seconds: 3600
      private-key-base64: ${BARA_AUTH_JWT_PRIVATE_KEY}
      public-key-base64: ${BARA_AUTH_JWT_PUBLIC_KEY}
```

## Google OAuth 플로우

### 엔드포인트

| 엔드포인트                                     | 역할                                                                            |
| ---------------------------------------------- | ------------------------------------------------------------------------------- |
| `GET /auth/google/login`                       | state 생성 후 Google 인증 URL로 302 리다이렉트                                  |
| `GET /auth/google/callback?code=...&state=...` | state 검증 → code 교환 → ID token 검증 → User upsert → 자체 JWT 발급 → FE로 302 |

### 흐름 상세

```
브라우저 → Auth Service → Google → Auth Service → 브라우저
```

1. 사용자가 FE에서 Login 버튼 클릭 → `GET /auth/google/login`
2. Auth Service가 랜덤 state 생성 → Redis에 5분 TTL로 저장 → Google auth URL 조립 후 302
3. 사용자가 Google에서 로그인 + 동의
4. Google이 redirect_uri로 302: `?code=...&state=...`
5. Auth Service가 `OAuthStateStore.consume(state)` 호출 — 존재 확인 + 즉시 삭제, 없으면 예외
6. Auth Service가 `POST https://oauth2.googleapis.com/token`에 code 교환 요청
7. 응답에서 `id_token` 추출, Google JWKS(`https://www.googleapis.com/oauth2/v3/certs`)로 서명 검증
8. ID token 페이로드에서 `sub` (Google ID), `email`, `name` 추출
9. `UserRepository.findByGoogleId(googleId)`로 기존 사용자 조회
   - 존재: 그대로 사용
   - 없음: UUID v4로 `id` 생성, `role=USER`, `createdAt=now()`로 저장
10. `JwtIssuer.issue(user)` 호출 → 자체 JWT
11. FE로 302: `http://localhost:5173/auth/callback?token=<JWT>`

### Scope

`openid email profile` — 최소 권한

### Google API 라이브러리

**`com.google.api-client:google-api-client:2.7.0`**

제공 기능:

- `GoogleIdTokenVerifier` — JWKS 자동 fetch + 캐싱 + 서명 검증
- `GoogleAuthorizationCodeTokenRequest` — code ↔ token 교환

두 기능만 사용. 나머지 Google API 호출은 없음.

### OAuth State Store (Redis)

**Port**:

```kotlin
interface OAuthStateStore {
    fun issue(): String
    fun consume(state: String)
}
```

**Adapter**: `RedisOAuthStateStore` — Spring `StringRedisTemplate` 사용

- Key: `oauth:state:{state}`
- Value: 빈 문자열 또는 생성 타임스탬프
- TTL: 300초
- `consume`: `SETNX`가 아닌 단순 GET + DEL 조합 (원자성은 이번 범위에서 불필요)

### 에러 처리

| 상황                            | HTTP | FE 리다이렉트                                 |
| ------------------------------- | ---- | --------------------------------------------- |
| state 누락/불일치/만료          | 400  | `/auth/callback?error=invalid_state`          |
| code 교환 실패 (Google 4xx/5xx) | 502  | `/auth/callback?error=google_exchange_failed` |
| ID token 검증 실패              | 401  | `/auth/callback?error=invalid_id_token`       |
| MongoDB 저장 실패               | 500  | `/auth/callback?error=server_error`           |

`@RestControllerAdvice`로 도메인 예외 → HTTP 응답 매핑.

### ConfigurationProperties

```kotlin
@ConfigurationProperties(prefix = "bara.auth.google")
data class GoogleOAuthProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)
```

`application.yml`:

```yaml
bara:
  auth:
    google:
      client-id: ${BARA_AUTH_GOOGLE_CLIENT_ID}
      client-secret: ${BARA_AUTH_GOOGLE_CLIENT_SECRET}
      redirect-uri: ${BARA_AUTH_GOOGLE_REDIRECT_URI}
```

## 도메인 모델 & MongoDB

### User 도메인

`domain/model/User.kt`:

```kotlin
data class User(
    val id: String,
    val googleId: String,
    val email: String,
    val name: String,
    val role: Role,
    val createdAt: Instant,
) {
    enum class Role { USER, ADMIN }
}
```

- `id`: UUID v4 (내부 식별자, JWT `sub`로 사용)
- `googleId`: Google `sub` 클레임 (unique)
- `role`: 기본 `USER`, `ADMIN`은 DB에서 수동 변경

### UserRepository Port

```kotlin
interface UserRepository {
    fun findByGoogleId(googleId: String): User?
    fun save(user: User): User
}
```

### MongoDB Document

```kotlin
@Document(collection = "users")
data class UserDocument(
    @Id val id: String,
    @Indexed(unique = true) val googleId: String,
    val email: String,
    val name: String,
    val role: String,
    val createdAt: Instant,
)
```

- Document ↔ Domain 변환은 `toDomain()`, `fromDomain()` 메서드로 명시적 처리
- `role`은 String으로 저장 (enum 직렬화 호환성)

### Spring Data Repository

```kotlin
interface UserMongoDataRepository : MongoRepository<UserDocument, String> {
    fun findByGoogleId(googleId: String): UserDocument?
}
```

### Adapter Bridge

`UserMongoRepository`가 `UserRepository`를 구현하고 내부적으로 `UserMongoDataRepository`를 위임 — 도메인 타입 ↔ Document 타입 변환 책임.

## 프론트엔드 (clients/web)

### 스택

| 항목          | 선택            |
| ------------- | --------------- |
| 번들러        | Vite 7          |
| 언어          | TypeScript 5.x  |
| 라이브러리    | React 19        |
| 라우팅        | React Router v7 |
| 스타일        | Tailwind CSS 4  |
| 테스트        | Vitest + jsdom  |
| 패키지 매니저 | pnpm            |

### 페이지

| 경로             | 컴포넌트       | 역할                                                                                                                                     |
| ---------------- | -------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `/`              | `LoginPage`    | "Login with Google" 버튼 — `<a href="/auth/google/login">`                                                                               |
| `/auth/callback` | `CallbackPage` | URL 쿼리에서 `token` 또는 `error` 추출. 토큰이면 localStorage 저장 후 `/me`로 이동. 에러면 메시지 표시                                   |
| `/me`            | `MePage`       | localStorage에서 토큰 조회. 없으면 `/`로 리다이렉트. decode된 payload(email/role/exp) 표시, 원본 JWT 복사 버튼, 로그아웃(토큰 삭제) 버튼 |

### 유틸

**`lib/auth.ts`**:

```ts
export function saveToken(token: string): void;
export function getToken(): string | null;
export function clearToken(): void;
```

- localStorage key: `bara.auth.token`

**`lib/jwt.ts`**:

```ts
export function decodeJwtPayload(token: string): Record<string, unknown>;
```

- base64url decode만 수행 (서명 검증은 하지 않음 — 서버 책임)

### Vite 설정

`vite.config.ts`:

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/auth/google': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
```

**프록시 경로 주의**: `/auth/google/*`만 백엔드로 프록시. FE 라우트인 `/auth/callback`은 프록시 대상이 아니므로 React Router가 처리.

### URL 플로우

| 단계             | URL                                                             | 처리                                                    |
| ---------------- | --------------------------------------------------------------- | ------------------------------------------------------- |
| 진입             | `http://localhost:5173/`                                        | LoginPage 렌더                                          |
| 로그인 클릭      | `http://localhost:5173/auth/google/login`                       | Vite proxy → `:8081/auth/google/login` → 302 Google     |
| Google 로그인 후 | `http://localhost:5173/auth/google/callback?code=...&state=...` | Vite proxy → `:8081/auth/google/callback` → 처리 후 302 |
| 토큰 수신        | `http://localhost:5173/auth/callback?token=<JWT>`               | React Router → CallbackPage                             |
| 최종             | `http://localhost:5173/me`                                      | MePage                                                  |

### Google OAuth Console 등록

**Authorized redirect URIs**: `http://localhost:5173/auth/google/callback`

## 환경변수 전체 목록

`.env` (gitignored, 프로젝트 루트):

```bash
# JWT RSA Keys (base64 encoded PEM)
BARA_AUTH_JWT_PRIVATE_KEY=
BARA_AUTH_JWT_PUBLIC_KEY=

# Google OAuth
BARA_AUTH_GOOGLE_CLIENT_ID=
BARA_AUTH_GOOGLE_CLIENT_SECRET=
BARA_AUTH_GOOGLE_REDIRECT_URI=http://localhost:5173/auth/google/callback
```

`.env.example` (git 포함): 위와 동일한 구조, 값은 비어있음.

`.gitignore` 추가:

```
.env
*.pem
clients/web/node_modules/
clients/web/dist/
```

## 테스트 전략

### 백엔드 (apps/auth)

| 대상                     | 유형 | 도구                                                                                     |
| ------------------------ | ---- | ---------------------------------------------------------------------------------------- |
| `Rs256JwtAdapter`        | 단위 | JUnit5. 테스트용 RSA 키쌍으로 issue → verify 라운드트립, 만료/잘못된 서명/잘못된 iss/aud |
| `LoginWithGoogleService` | 단위 | JUnit5 + MockK. 각 port mock, happy path + 모든 에러 케이스                              |
| `AuthController`         | 통합 | `@WebMvcTest` + MockMvc. 서비스 레이어 mock, 302 리다이렉트 URL 검증                     |
| `HealthCheckTest`        | 기존 | 영향 없음, 그대로 유지                                                                   |

**이번 범위 밖**:

- Google API 실제 호출 테스트 (WireMock 통합 테스트)
- MongoDB Testcontainers 통합 테스트
- Redis Testcontainers 통합 테스트

### 프론트엔드 (clients/web)

| 대상          | 유형 | 도구                                                      |
| ------------- | ---- | --------------------------------------------------------- |
| `lib/auth.ts` | 단위 | Vitest + jsdom. saveToken/getToken/clearToken 라운드트립  |
| `lib/jwt.ts`  | 단위 | Vitest. 샘플 JWT payload decode, 잘못된 형식 입력 시 예외 |

컴포넌트 테스트는 생략 — 이번 iteration의 페이지들은 로직이 거의 없고 브라우저에서 수동 검증 가능.

## 수용 기준

1. `.env`에 키 세팅 후 `./scripts/infra.sh up dev` + `./gradlew :apps:auth:bootRun` + `pnpm dev` 로 전체 스택 실행 가능
2. `http://localhost:5173/` 접속 → "Login with Google" 클릭 → Google 로그인 → `/me` 페이지에 이메일/역할/만료시간 표시됨
3. 같은 Google 계정으로 재로그인 시 MongoDB `users` 컬렉션에 중복 row 생성되지 않음 (googleId unique)
4. `/me`에서 복사한 JWT를 [jwt.io](https://jwt.io)에서 decode 시 예상한 클레임 구조와 일치
5. 백엔드/프론트엔드 모든 단위 테스트 통과
6. `./gradlew :apps:auth:build` 성공, `pnpm --dir clients/web build` 성공

## 후속 작업 (이번 iteration 제외)

1. `/validate` 엔드포인트 + Nginx auth_request 연동
2. Refresh Token rotate
3. JTI 1회용 블랙리스트 (Redis)
4. JWKS 엔드포인트 (`/auth/.well-known/jwks.json`)
5. 로그아웃 (서버 측 토큰 무효화)
6. Provider 가입/토큰 발급
7. Kafka OAUTHBEARER Access Token
8. User 확장 필드 (`allowed_agents`, Telegram 연동)
9. MongoDB/Redis Testcontainers 통합 테스트
10. Nginx 리버스 프록시 설정
