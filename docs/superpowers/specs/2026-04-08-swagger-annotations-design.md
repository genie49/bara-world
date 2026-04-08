# Swagger OpenAPI 어노테이션 설계

## 개요

Auth Service와 API Service의 모든 컨트롤러 및 DTO에 OpenAPI 어노테이션을 추가한다. 인프라(springdoc 의존성, AutoConfiguration, Traefik 라우팅)는 이미 구축되어 있으며, 이번 작업은 컨트롤러/DTO 레벨의 상세 문서화에 집중한다.

## 결정 사항

| 항목 | 결정 |
|------|------|
| 범위 | Auth Service (5개 컨트롤러) + API Service (1개 컨트롤러) |
| 상세도 | 풀 스펙: @Operation, @ApiResponse, @Parameter, @Schema(description + example) |
| 접근 방식 | 컨트롤러/DTO에 직접 어노테이션 (인터페이스 분리 안 함) |
| validate 엔드포인트 | 포함 (Traefik forwardAuth용 내부 엔드포인트임을 description에 명시) |
| Google OAuth 엔드포인트 | 포함 (브라우저 리다이렉트 방식임을 description에 명시) |

## 어노테이션 구조

### 컨트롤러 레벨

```kotlin
@Tag(name = "그룹명", description = "그룹 설명")
```

### 메서드 레벨

```kotlin
@Operation(summary = "한줄 요약", description = "상세 설명")
@ApiResponses(value = [
    @ApiResponse(responseCode = "200", description = "성공", content = [...]),
    @ApiResponse(responseCode = "404", description = "리소스 없음", content = [...]),
])
```

### 파라미터 레벨

```kotlin
@Parameter(description = "설명", example = "예시값")
```

### DTO 레벨

```kotlin
@Schema(description = "필드 설명", example = "예시값")
```

## Tag 구성

| Tag | 컨트롤러 | 서비스 | 설명 |
|-----|---------|--------|------|
| `Google OAuth` | AuthController | Auth | Google 소셜 로그인 (브라우저 리다이렉트) |
| `Token` | RefreshController | Auth | Access Token 갱신 |
| `Token Validation` | ValidateController | Auth | JWT 토큰 검증 (Traefik forwardAuth) |
| `Provider` | ProviderController | Auth | Provider 등록/조회 |
| `API Key` | ApiKeyController | Auth | Provider API Key 관리 |
| `Agent` | AgentController | API | Agent 등록/조회/삭제 |

## Auth Service 엔드포인트 상세

### AuthController — `Google OAuth`

| 엔드포인트 | summary | 응답 | 비고 |
|-----------|---------|------|------|
| `GET /google/login` | Google 로그인 페이지로 리다이렉트 | 302 (Location 헤더) | description: "브라우저 리다이렉트 방식. Swagger UI에서 직접 테스트 불가." |
| `GET /google/callback` | Google OAuth 콜백 처리 | 302 (프론트엔드로 리다이렉트) | description: "Google이 호출하는 콜백 엔드포인트. 직접 호출할 필요 없음." |

### RefreshController — `Token`

| 엔드포인트 | summary | 응답 |
|-----------|---------|------|
| `POST /refresh` | Access Token 갱신 | 200 (RefreshResponse), 401 (만료/무효 토큰) |

### ValidateController — `Token Validation`

| 엔드포인트 | summary | 응답 | 비고 |
|-----------|---------|------|------|
| `GET /validate` | JWT 토큰 검증 | 200 (X-User-Id 또는 X-Provider-Id 헤더), 401 | description: "Traefik forwardAuth용 내부 엔드포인트. 유효한 토큰이면 200 + 사용자 정보 헤더, 무효하면 401 반환." |

### ProviderController — `Provider`

| 엔드포인트 | summary | 응답 |
|-----------|---------|------|
| `GET /provider` | 내 Provider 정보 조회 | 200 (ProviderResponse), 404 (미등록) |
| `POST /provider/register` | Provider 등록 | 201 (ProviderResponse), 409 (이름 중복) |

### ApiKeyController — `API Key`

| 엔드포인트 | summary | 응답 |
|-----------|---------|------|
| `POST /provider/api-key` | API Key 발급 | 201 (IssuedApiKeyResponse), 409 (이름 중복) |
| `GET /provider/api-key` | API Key 목록 조회 | 200 (ApiKeyListResponse) |
| `PATCH /provider/api-key/{keyId}` | API Key 이름 변경 | 200 (ApiKeyResponse), 404 (Key 없음) |
| `DELETE /provider/api-key/{keyId}` | API Key 삭제 | 204, 404 (Key 없음) |

## API Service 엔드포인트 상세

### AgentController — `Agent`

| 엔드포인트 | summary | 응답 | 보안 |
|-----------|---------|------|------|
| `POST /agents` | Agent 등록 | 201 (AgentDetailResponse), 409 (이름 중복) | X-Provider-Id 필수 |
| `GET /agents` | Agent 목록 조회 | 200 (AgentListResponse) | 공개 — `@SecurityRequirements(value = [])` |
| `GET /agents/{id}` | Agent 상세 조회 | 200 (AgentDetailResponse), 404 | 공개 — `@SecurityRequirements(value = [])` |
| `GET /agents/{id}/.well-known/agent.json` | Agent Card 조회 (A2A 프로토콜) | 200 (AgentCard), 404 | 공개 — `@SecurityRequirements(value = [])` |
| `DELETE /agents/{id}` | Agent 삭제 | 204, 404 | X-Provider-Id 필수 |

## DTO 예시 값

### Auth Service

| DTO | 필드 | example |
|-----|------|---------|
| `RefreshRequest` | `refreshToken` | `"eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."` |
| `RefreshResponse` | `accessToken` | `"eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."` |
| `RefreshResponse` | `refreshToken` | `"eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."` |
| `RefreshResponse` | `expiresIn` | `3600` |
| `RegisterProviderRequest` | `name` | `"my-ai-company"` |
| `ProviderResponse` | `id` | `"6615e1a2b3c4d5e6f7890abc"` |
| `ProviderResponse` | `name` | `"my-ai-company"` |
| `ProviderResponse` | `status` | `"ACTIVE"` |
| `ProviderResponse` | `createdAt` | `"2026-04-08T12:00:00Z"` |
| `IssueApiKeyRequest` | `name` | `"production-key"` |
| `IssuedApiKeyResponse` | `id` | `"6615e1a2b3c4d5e6f7890def"` |
| `IssuedApiKeyResponse` | `name` | `"production-key"` |
| `IssuedApiKeyResponse` | `apiKey` | `"bara_k1_a1b2c3d4e5f6..."` |
| `IssuedApiKeyResponse` | `prefix` | `"bara_k1_a1b2"` |
| `IssuedApiKeyResponse` | `createdAt` | `"2026-04-08T12:00:00Z"` |
| `UpdateApiKeyNameRequest` | `name` | `"renamed-key"` |
| `ApiKeyResponse` | `id` | `"6615e1a2b3c4d5e6f7890def"` |
| `ApiKeyResponse` | `name` | `"production-key"` |
| `ApiKeyResponse` | `prefix` | `"bara_k1_a1b2"` |
| `ApiKeyResponse` | `createdAt` | `"2026-04-08T12:00:00Z"` |
| `ErrorResponse` | `error` | `"invalid_token"` |
| `ErrorResponse` | `message` | `"토큰이 만료되었거나 유효하지 않습니다"` |

### API Service

| DTO | 필드 | example |
|-----|------|---------|
| `RegisterAgentRequest` | `name` | `"my-translation-agent"` |
| `AgentCardRequest` | `name` | `"Translation Agent"` |
| `AgentCardRequest` | `description` | `"다국어 번역을 수행하는 AI 에이전트"` |
| `AgentCardRequest` | `version` | `"1.0.0"` |
| `AgentCardRequest` | `defaultInputModes` | `["text"]` |
| `AgentCardRequest` | `defaultOutputModes` | `["text"]` |
| `AgentCapabilitiesRequest` | `streaming` | `false` |
| `AgentCapabilitiesRequest` | `pushNotifications` | `false` |
| `AgentSkillRequest` | `id` | `"translate"` |
| `AgentSkillRequest` | `name` | `"번역"` |
| `AgentSkillRequest` | `description` | `"입력 텍스트를 지정된 언어로 번역합니다"` |
| `AgentSkillRequest` | `tags` | `["translation", "multilingual"]` |
| `AgentSkillRequest` | `examples` | `["영어를 한국어로 번역해줘"]` |
| `AgentResponse` | `id` | `"6615f1a2b3c4d5e6f7890abc"` |
| `AgentResponse` | `name` | `"my-translation-agent"` |
| `AgentResponse` | `providerId` | `"6615e1a2b3c4d5e6f7890abc"` |
| `AgentResponse` | `createdAt` | `"2026-04-08T12:00:00Z"` |
| `AgentDetailResponse` | `agentCard` | (AgentCard 객체) |
| `AgentCard` | `name` | `"Translation Agent"` |
| `AgentCard` | `iconUrl` | `"https://example.com/icon.png"` |
| `AgentCard.AgentCapabilities` | `streaming` | `false` |
| `AgentCard.AgentSkill` | `id` | `"translate"` |
| `ErrorResponse` | `error` | `"agent_not_found"` |
| `ErrorResponse` | `message` | `"Agent를 찾을 수 없습니다"` |

## 수정 대상 파일

### Auth Service
- `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt`
- `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt`
- `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt`
- `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt`

### API Service
- `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- `apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt`

### Auth DTO (컨트롤러 파일 내 인라인 정의)
- `RefreshRequest`, `RefreshResponse` — RefreshController.kt 내
- `RegisterProviderRequest`, `ProviderResponse` — ProviderController.kt 내
- `IssueApiKeyRequest`, `UpdateApiKeyNameRequest`, `ApiKeyResponse`, `IssuedApiKeyResponse`, `ApiKeyListResponse` — ApiKeyController.kt 내

### 공통 에러 응답
- Auth: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt` 내 `ErrorResponse`
- API: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt` 내 `ErrorResponse`
