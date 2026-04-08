# Provider 등록 및 인증 설계

## 개요

Auth Service에 두 가지 기능을 추가한다:

1. **User Access/Refresh Token** — 기존 단일 JWT(1h)를 Access(1h) + Refresh(7d, Redis 저장, Rotation) 방식으로 변경
2. **Provider API Key** — Provider 등록 및 API Key 발급/폐기/재발급

## 스코프

### 이번 구현
- User Access Token (1h JWT) + Refresh Token (7d JWT, Redis 저장)
- Refresh Token Rotation + 재사용 감지
- `POST /auth/refresh` 엔드포인트
- Provider 등록 (`POST /auth/provider/register`)
- Provider API Key 발급/폐기/재발급
- Provider 도메인 모델 + MongoDB 영속화

### 스코프 밖
- Kafka 토큰 교환, JWKS 엔드포인트, Gateway /validate 엔드포인트

---

## 1. User Access/Refresh Token

### 현재 문제

- 단일 JWT (1h) → 만료 시 Google 재로그인 필요
- Refresh Token이 없어 세션 유지가 불가능

### 변경 후 토큰 구조

| 토큰 | 형식 | 만료 | 저장소 | 용도 |
|------|------|------|--------|------|
| Access Token | JWT (RS256) | 1시간 | 없음 (Stateless) | API 호출 인증 |
| Refresh Token | JWT (RS256) | 7일 | Redis | Access Token 갱신 |

### Access Token (JWT)

기존 User JWT와 동일. 변경 없음.

```json
{
  "iss": "bara-auth",
  "aud": "bara-world",
  "sub": "user-id",
  "jti": "uuid",
  "iat": 1712505600,
  "exp": 1712509200,
  "email": "user@example.com",
  "role": "USER"
}
```

### Refresh Token (JWT)

```json
{
  "iss": "bara-auth",
  "aud": "bara-refresh",
  "sub": "user-id",
  "jti": "uuid",
  "iat": 1712505600,
  "exp": 1713110400,
  "family": "token-family-uuid"
}
```

- `aud`: `bara-refresh`로 Access Token과 구분
- `family`: Token Family ID — Rotation 시 동일 family를 유지하여 재사용 감지에 활용

### Redis 저장 구조

```
Key:   refresh:{userId}
Value: { "jti": "current-jti", "family": "family-uuid" }
TTL:   7일
```

userId당 하나의 유효한 Refresh Token만 존재한다.

### Refresh Token Rotation

Access Token 갱신 시 Refresh Token도 새로 발급한다. 7일 이내에 접속하면 세션이 계속 연장된다.

```
Day 0: 로그인 → Access(1h) + Refresh_1(7d)   [family: "abc"]
Day 3: 갱신  → Access(1h) + Refresh_2(7d)    [Refresh_1 무효화, family: "abc"]
Day 9: 갱신  → Access(1h) + Refresh_3(7d)    [Refresh_2 무효화, family: "abc"]
```

7일 연속 미접속 시에만 재로그인이 필요하다.

### 재사용 감지 (Reuse Detection)

이미 사용된 Refresh Token이 다시 사용되면 토큰 탈취로 간주한다.

```
정상 사용자: Refresh_2로 갱신 → Refresh_3 발급
공격자:     Refresh_1(탈취)로 갱신 시도
            → Redis에 저장된 jti와 불일치
            → family "abc" 전체 무효화 (Redis에서 삭제)
            → 정상 사용자도 재로그인 필요
```

### Grace Period

동시 요청 대비로 이전 Refresh Token을 **30초간 유효**하게 유지한다. 이전 JTI를 Redis에 30초 TTL로 별도 저장한다.

```
Key:   refresh:grace:{old-jti}
Value: "valid"
TTL:   30초
```

### `POST /auth/refresh` 엔드포인트

**요청:**
```
Content-Type: application/json

{ "refreshToken": "eyJhbG..." }
```

**성공 (200 OK):**
```json
{
  "accessToken": "eyJhbG...",
  "refreshToken": "eyJhbG...",
  "expiresIn": 3600
}
```

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 401 | Refresh Token 서명 검증 실패 |
| 401 | Refresh Token 만료 |
| 401 | JTI가 Redis와 불일치 (재사용 감지 → family 무효화) |

### 로그인 응답 변경

기존 Google OAuth 콜백 응답에 Refresh Token을 추가한다.

**기존:**
```json
{ "token": "access-jwt" }
```

**변경:**
```json
{
  "accessToken": "access-jwt",
  "refreshToken": "refresh-jwt",
  "expiresIn": 3600
}
```

---

## 2. Provider 도메인 모델

### Provider

```kotlin
data class Provider(
    val id: String,              // UUID
    val userId: String,          // 등록한 User ID (1:1 관계)
    val name: String,            // Provider 이름
    val status: ProviderStatus,  // PENDING → ACTIVE → SUSPENDED
    val createdAt: Instant,
)

enum class ProviderStatus {
    PENDING, ACTIVE, SUSPENDED
}
```

- **1 User : 1 Provider** — User당 Provider 하나만 등록 가능
- **1 Provider : N Agent** — 하나의 Provider가 여러 Agent를 등록 (Agent 등록은 API Service 범위)
- **1 Provider : N API Key** — 환경별/서버별 키 분리 가능 (최대 5개)
- Admin 승인은 MongoDB Compass에서 `status`를 직접 변경 (`PENDING` → `ACTIVE`)

### ApiKey

```kotlin
data class ApiKey(
    val id: String,              // UUID
    val providerId: String,      // 소속 Provider
    val name: String,            // 키 이름 (e.g. "Production", "Staging")
    val keyHash: String,         // SHA-256 해시
    val keyPrefix: String,       // 앞 8자 (식별용, e.g. "bk_a3f2e1")
    val createdAt: Instant,
)
```

### MongoDB Collections

**`providers`** 컬렉션. `userId`에 unique index.

```json
{
  "_id": "uuid-provider-id",
  "userId": "uuid-user-id",
  "name": "My Agent Server",
  "status": "ACTIVE",
  "createdAt": "2026-04-07T12:00:00Z"
}
```

**`api_keys`** 컬렉션. `keyHash`에 unique index, `providerId`에 index.

```json
{
  "_id": "uuid-key-id",
  "providerId": "uuid-provider-id",
  "name": "Production Key",
  "keyHash": "a1b2c3d4e5f6...sha256hex",
  "keyPrefix": "bk_a3f2e1",
  "createdAt": "2026-04-07T12:00:00Z"
}
```

API Key 검증 시 `keyHash` index로 O(1) 조회 → Provider 식별 + status 확인.

### API Key 형식

```
bk_a3f2e1c8d9b0f1234567890abcdef1234567890abcdef1234567890abcdef12
```

- Prefix `bk_` (bara-key) + 64자 hex 랜덤 문자열
- 총 67자
- 발급 시 1회만 원본 표시, 이후 prefix + name으로 식별

### 암호화 전략

- **SHA-256 단방향 해시**로 MongoDB에 저장
- 원본은 발급 시 응답에 1회만 표시
- 검증: 요청의 API Key → SHA-256 해싱 → DB `keyHash`와 비교
- DB 유출 시에도 원본 키 복원 불가

### 제한

- Provider당 API Key 최대 **5개**
- 초과 시 기존 키를 삭제 후 발급

---

## 3. Provider API 엔드포인트

모든 Provider 엔드포인트는 **User JWT(Access Token) 인증 필수**.

### `POST /auth/provider/register`

**요청:**
```
Authorization: Bearer {access_token}
Content-Type: application/json

{ "name": "My Agent Server" }
```

**성공 (201 Created):**
```json
{
  "id": "uuid-provider-id",
  "name": "My Agent Server",
  "status": "PENDING",
  "createdAt": "2026-04-07T12:00:00Z"
}
```

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 400 | `name` 누락 또는 빈 문자열 |
| 401 | Access Token 없음 또는 유효하지 않음 |
| 409 | 해당 User가 이미 Provider를 등록함 |

### `POST /auth/provider/api-key`

API Key 발급. ACTIVE 상태만 가능. Provider당 최대 5개. **발급된 키는 이 응답에서만 확인 가능.**

**요청:**
```
Authorization: Bearer {access_token}
Content-Type: application/json

{ "name": "Production Key" }
```

**성공 (201 Created):**
```json
{
  "id": "uuid-key-id",
  "name": "Production Key",
  "apiKey": "bk_a3f2e1c8d9b0f123...",
  "prefix": "bk_a3f2e1",
  "createdAt": "2026-04-07T12:00:00Z"
}
```

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 400 | `name` 누락 또는 빈 문자열 |
| 401 | Access Token 없음 또는 유효하지 않음 |
| 403 | Provider status가 ACTIVE가 아님 |
| 404 | 해당 User의 Provider가 없음 |
| 409 | API Key 5개 한도 초과 |

### `GET /auth/provider/api-key`

내 API Key 목록 조회. 원본 키는 포함하지 않는다.

**요청:**
```
Authorization: Bearer {access_token}
```

**성공 (200 OK):**
```json
{
  "keys": [
    {
      "id": "uuid-key-id-1",
      "name": "Production Key",
      "prefix": "bk_a3f2e1",
      "createdAt": "2026-04-07T12:00:00Z"
    },
    {
      "id": "uuid-key-id-2",
      "name": "Staging Key",
      "prefix": "bk_b7d9c2",
      "createdAt": "2026-04-07T13:00:00Z"
    }
  ]
}
```

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 401 | Access Token 없음 또는 유효하지 않음 |
| 404 | 해당 User의 Provider가 없음 |

### `PATCH /auth/provider/api-key/{keyId}`

API Key 이름 수정.

**요청:**
```
Authorization: Bearer {access_token}
Content-Type: application/json

{ "name": "New Key Name" }
```

**성공 (200 OK):**
```json
{
  "id": "uuid-key-id",
  "name": "New Key Name",
  "prefix": "bk_a3f2e1",
  "createdAt": "2026-04-07T12:00:00Z"
}
```

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 400 | `name` 누락 또는 빈 문자열 |
| 401 | Access Token 없음 또는 유효하지 않음 |
| 404 | 해당 키가 없거나 본인 Provider 소유가 아님 |

### `DELETE /auth/provider/api-key/{keyId}`

API Key 폐기. 삭제 후 해당 키를 사용하는 Agent 서버는 즉시 인증 실패.

**요청:**
```
Authorization: Bearer {access_token}
```

**성공 (204 No Content)**

**에러:**
| 상태 코드 | 조건 |
|-----------|------|
| 401 | Access Token 없음 또는 유효하지 않음 |
| 404 | 해당 키가 없거나 본인 Provider 소유가 아님 |

---

## 4. JWT 인증 방식

Provider 엔드포인트 및 `/auth/refresh`에서 User JWT를 검증할 때, Controller에서 `JwtVerifier.verify(token)` 포트를 직접 호출하여 userId를 추출한다.

```
Authorization 헤더 → Bearer 토큰 파싱 → JwtVerifier.verify() → userId 획득
```

- 향후 Gateway `/validate` 엔드포인트가 구현되면 `X-User-Id` 헤더 방식으로 전환 가능
- 현재는 인증이 필요한 엔드포인트가 소수이므로 Interceptor/Security 도입은 불필요

---

## 5. 헥사고날 아키텍처 구조

기존 Auth Service 패턴을 따른다.

### Ports (in) — Command

- `LoginWithGoogleUseCase` — (기존, 응답에 Refresh Token 추가)
- `RefreshTokenUseCase` — Refresh Token으로 Access + Refresh 재발급
- `RegisterProviderUseCase` — Provider 등록
- `IssueApiKeyUseCase` — API Key 발급
- `ListApiKeysUseCase` — API Key 목록 조회
- `UpdateApiKeyNameUseCase` — API Key 이름 수정
- `DeleteApiKeyUseCase` — API Key 폐기

### Ports (out)

- `UserRepository` — (기존)
- `ProviderRepository` — Provider 저장/조회
- `ApiKeyRepository` — API Key 저장/조회/삭제
- `JwtIssuer` — (기존, Access Token 발급)
- `RefreshTokenIssuer` — Refresh Token JWT 발급
- `RefreshTokenStore` — Redis에 Refresh Token 저장/조회/삭제
- `ApiKeyGenerator` — API Key 랜덤 생성 + SHA-256 해싱

### Services

- `RefreshTokenService` — Refresh Token 검증 → Rotation → 재사용 감지
- `RegisterProviderService` — userId + name 받아서 중복 체크 후 저장
- `IssueApiKeyService` — Provider 조회 → ACTIVE 확인 → 5개 한도 체크 → 키 생성 → 해시 저장
- `ListApiKeysService` — Provider의 API Key 목록 반환 (prefix + name만)
- `UpdateApiKeyNameService` — 소유권 확인 → 이름 수정
- `DeleteApiKeyService` — 소유권 확인 → API Key 삭제

### Adapters

- `AuthController` — (기존, 로그인 응답 변경)
- `RefreshController` — `POST /auth/refresh`
- `ProviderController` — Provider 등록
- `ApiKeyController` — API Key CRUD 엔드포인트 4개
- `ProviderMongoRepository` — Provider MongoDB 어댑터
- `ApiKeyMongoRepository` — API Key MongoDB 어댑터 (`ApiKeyDocument`, `ApiKeyMongoDataRepository`)
- `RefreshTokenRedisStore` — Redis Refresh Token 저장/조회
- `RefreshTokenJwtAdapter` — Refresh Token JWT 발급/검증
- `ApiKeyGeneratorAdapter` — SecureRandom + SHA-256

### 테스트

기존 패턴대로 각 레이어 MockK 단위 테스트:
- Service: 순수 MockK (Spring 없이)
- Controller: `@WebMvcTest` + `@MockkBean`
- Repository: MockK으로 Spring Data 모킹
- Redis: MockK으로 StringRedisTemplate 모킹

---

## 6. 설정

`application.yml` 추가:

```yaml
bara.auth.jwt:
  # (기존) Access Token
  issuer: bara-auth
  audience: bara-world
  expiry-seconds: 3600  # 1시간

bara.auth.refresh-token:
  audience: bara-refresh
  expiry-seconds: 604800  # 7일
  grace-period-seconds: 30
```

---

## 7. 인증 흐름 요약

```
[User 인증]
1. User가 Google로 로그인 → Access Token (1h) + Refresh Token (7d)
2. Access Token 만료 → POST /auth/refresh → 새 Access + 새 Refresh (Rotation)
3. 7일 연속 미접속 시에만 Google 재로그인

[Provider 인증]
4. User가 POST /auth/provider/register (Access Token 필수) → Provider 생성 (PENDING)
5. Admin이 MongoDB Compass에서 status → ACTIVE 변경
6. User가 POST /auth/provider/api-key (Access Token 필수, name 지정) → API Key 1회 표시
7. 필요 시 추가 키 발급 (환경별/서버별, 최대 5개)
8. User가 API Key를 Agent 서버 설정에 입력
9. (향후) Agent 서버가 API Key로 Kafka 토큰 교환
```

---

## 관련 문서

- [인증 스펙](../../spec/auth/authentication.md)
- [인증 아키텍처 다이어그램](../../spec/auth/diagrams/auth-architecture.excalidraw)
- [메시징 스펙 - Kafka OAUTHBEARER](../../spec/shared/messaging.md#인증-sasl-oauthbearer)
- [ADR-003: Provider Token](../../spec/decisions/adr-003-provider-token-over-mtls.md)
