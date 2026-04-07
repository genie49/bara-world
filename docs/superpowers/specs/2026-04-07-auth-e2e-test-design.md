# Auth Service E2E 테스트 설계

## 개요

Auth Service의 전체 비즈니스 흐름을 실제 인프라(MongoDB, Redis)와 함께 검증하는 E2E 테스트 인프라를 구축한다. Google OAuth는 WireMock으로 stub하고, gateway-level 스모크 테스트는 curl 스크립트로 별도 제공한다.

## 결정 사항

| 항목         | 결정                                                                            |
| ------------ | ------------------------------------------------------------------------------- |
| E2E 범위     | API-level (TestContainers) + Gateway 스모크 (curl 스크립트)                     |
| Google OAuth | WireMock stub (token/userinfo 엔드포인트)                                       |
| CI 통합      | 하지 않음. 로컬 수동 실행 (`./gradlew :apps:auth:e2eTest`)                      |
| 테스트 전략  | 혼합: happy-path 시나리오 체인 + 독립 에러 케이스                               |
| 커버 범위    | 로그인, token refresh, validate (JWT/API Key), Provider CRUD, API Key lifecycle |

## 기술 스택

### 추가 의존성

| 의존성                              | 용도                                       |
| ----------------------------------- | ------------------------------------------ |
| `org.testcontainers:testcontainers` | GenericContainer로 MongoDB, Redis 컨테이너 |
| `org.testcontainers:junit-jupiter`  | JUnit 5 라이프사이클 통합                  |
| `org.wiremock:wiremock-standalone`  | Google token/userinfo API stub             |

### Gradle 분리

E2E 테스트는 별도 source set `e2eTest`로 분리한다.

- `./gradlew :apps:auth:e2eTest` — E2E만 실행
- `./gradlew :apps:auth:test` — 기존 단위/슬라이스 테스트만 실행 (변경 없음)

### 테스트 프로파일

`application-e2e.yml` — TestContainers가 `@DynamicPropertySource`로 주입하는 MongoDB URI, Redis 포트, WireMock URL을 사용한다.

## 테스트 구조

```
apps/auth/src/e2eTest/
├── kotlin/com/bara/auth/e2e/
│   ├── support/
│   │   ├── E2eTestBase.kt          # 공통 설정 (TestContainers, WireMock, TestRestTemplate)
│   │   ├── GoogleOAuthStub.kt      # WireMock Google token/userinfo stub
│   │   └── TokenFixture.kt         # JWT 직접 생성 헬퍼 (에러 케이스용)
│   ├── scenario/
│   │   └── FullFlowScenarioTest.kt # Happy-path 시나리오 체인
│   └── error/
│       ├── ValidateErrorTest.kt    # 토큰/API Key 검증 에러
│       ├── ProviderErrorTest.kt    # Provider 관련 에러
│       └── ApiKeyErrorTest.kt      # API Key 관련 에러
└── resources/
    └── application-e2e.yml         # E2E 전용 프로파일
```

### 공통 인프라 (E2eTestBase)

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("e2e")`
- TestContainers: `GenericContainer("mongo:7")`, `GenericContainer("redis:7-alpine")`
- WireMock: Google OAuth 엔드포인트 stub 서버
- `@DynamicPropertySource`로 컨테이너 포트/URL을 Spring 프로퍼티에 주입
- `TestRestTemplate`으로 HTTP 요청

### GoogleOAuthStub

WireMock으로 두 엔드포인트를 stub한다:

- `POST /token` — authorization code → access_token 교환 응답
- `GET /userinfo` — access_token → 사용자 정보(google_id, email, name) 응답

Auth Service의 `bara.auth.google.*` 프로퍼티를 WireMock URL로 오버라이드하여 실제 Google 대신 WireMock을 호출하게 한다.

### TokenFixture

에러 케이스 테스트에서 사용할 JWT 생성 헬퍼:

- 만료된 JWT 생성 (expiry를 과거로)
- 잘못된 서명의 JWT 생성 (다른 RSA 키 사용)
- 유효한 JWT 생성 (정상 케이스 fixture)

## Happy-path 시나리오 (FullFlowScenarioTest)

`@TestMethodOrder(OrderAnnotation)` + `@TestInstance(PER_CLASS)`로 상태를 공유하며 순서대로 실행한다.

| 순서 | 테스트                         | 동작                                                                                                                  | 검증                                                      |
| ---- | ------------------------------ | --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 1    | Google 로그인                  | `GET /api/auth/google/login` → redirect URL 확인 → `GET /api/auth/google/callback?code=xxx&state=yyy` (WireMock 응답) | 302 redirect에 `token`, `refreshToken` 쿼리 파라미터 포함 |
| 2    | JWT validate                   | `GET /api/auth/validate` + `Authorization: Bearer {jwt}`                                                              | 200 + `X-User-Id`, `X-User-Role` 헤더                     |
| 3    | Token refresh                  | `POST /api/auth/refresh` + refreshToken body                                                                          | 새 accessToken, refreshToken 반환                         |
| 4    | 이전 refresh token 재사용 거부 | `POST /api/auth/refresh` + 이전 refreshToken                                                                          | 401 (재사용 감지)                                         |
| 5    | Provider 등록                  | `POST /api/auth/provider/register` + `X-User-Id` 헤더                                                                 | 201 + provider 정보                                       |
| 6    | Provider 조회                  | `GET /api/auth/provider` + `X-User-Id` 헤더                                                                           | 200 + status: PENDING                                     |
| 7    | API Key 발급                   | DB에서 Provider status를 ACTIVE로 변경 → `POST /api/auth/provider/api-key`                                            | 201 + rawKey 반환                                         |
| 8    | API Key validate               | `GET /api/auth/validate` + `Authorization: Bearer bk_xxx`                                                             | 200 + `X-Provider-Id` 헤더                                |
| 9    | API Key 목록 조회              | `GET /api/auth/provider/api-key` + `X-User-Id` 헤더                                                                   | 200 + 1개 항목                                            |
| 10   | API Key 이름 수정              | `PATCH /api/auth/provider/api-key/{keyId}`                                                                            | 200                                                       |
| 11   | API Key 삭제                   | `DELETE /api/auth/provider/api-key/{keyId}`                                                                           | 204                                                       |
| 12   | 삭제된 API Key validate 실패   | `GET /api/auth/validate` + 삭제된 key                                                                                 | 401                                                       |

순서 5~11의 protected 엔드포인트는 gateway를 거치지 않으므로 `X-User-Id` 헤더를 직접 넣어서 호출한다 (Traefik forwardAuth가 주입하는 것과 동일).

## 에러 케이스 테스트

각 테스트는 독립 실행 가능하며, `E2eTestBase`를 상속한다.

### ValidateErrorTest

| 테스트                       | 동작                                              | 기대 |
| ---------------------------- | ------------------------------------------------- | ---- |
| Authorization 헤더 없음      | `GET /api/auth/validate` (헤더 없이)              | 401  |
| 잘못된 JWT 서명              | 다른 RSA 키로 서명한 JWT                          | 401  |
| 만료된 JWT                   | expiry를 과거로 설정한 JWT                        | 401  |
| 존재하지 않는 API Key        | `Bearer bk_nonexistent`                           | 401  |
| SUSPENDED Provider의 API Key | DB에 SUSPENDED provider + api key 삽입 → validate | 401  |

### ProviderErrorTest

| 테스트                        | 동작                                 | 기대 |
| ----------------------------- | ------------------------------------ | ---- |
| Provider 중복 등록            | 같은 userId로 register 2번           | 409  |
| 미등록 Provider 조회          | 존재하지 않는 userId로 조회          | 404  |
| PENDING 상태에서 API Key 발급 | Provider PENDING 상태 → api-key 발급 | 403  |

### ApiKeyErrorTest

| 테스트                   | 동작                                | 기대 |
| ------------------------ | ----------------------------------- | ---- |
| API Key 5개 초과 발급    | ACTIVE provider + 5개 발급 후 6번째 | 409  |
| 존재하지 않는 keyId 수정 | PATCH 잘못된 keyId                  | 404  |
| 존재하지 않는 keyId 삭제 | DELETE 잘못된 keyId                 | 404  |

## Gateway 스모크 테스트

`scripts/smoke-test.sh` — k3d 클러스터 실행 중인 상태에서 curl로 핵심 라우팅을 확인한다.

**실행:** `./scripts/smoke-test.sh <jwt>`

| #   | 테스트                    | 명령                                                                               | 기대                                    |
| --- | ------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------- |
| 1   | FE 정적 파일              | `curl http://localhost/`                                                           | 200                                     |
| 2   | Public 경로               | `curl http://localhost/api/auth/google/login`                                      | 302                                     |
| 3   | Protected 경로 인증 없이  | `curl http://localhost/api/auth/provider`                                          | 401                                     |
| 4   | Protected 경로 유효한 JWT | `curl -H "Authorization: Bearer {jwt}" http://localhost/api/auth/provider`         | 200 또는 404                            |
| 5   | CORS preflight            | `curl -X OPTIONS -H "Origin: http://localhost" http://localhost/api/auth/validate` | `Access-Control-Allow-Origin` 헤더 포함 |
| 6   | Health check              | `curl http://localhost/api/auth/actuator/health`                                   | 200                                     |

**스크립트 동작:**

- 각 테스트 pass/fail 컬러 출력
- 실패 시 상세 응답 출력
- 종료 코드로 전체 결과 반환 (0: 전체 pass, 1: 실패 있음)
- JWT는 첫 번째 인자로 전달
