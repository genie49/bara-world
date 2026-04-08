# Auth Provider / API Key 로그 흐름

## GET /auth/provider

### 성공 — Provider 존재

| 필드              | 값                             | 추가 시점          |
| ----------------- | ------------------------------ | ------------------ |
| `user_id`         | 유저 ID                        | GetProviderService |
| `provider_id`     | Provider ID                    | GetProviderService |
| `provider_status` | Provider 상태 (PENDING/ACTIVE) | GetProviderService |
| `outcome`         | `"provider_found"`             | GetProviderService |
| `message`         | `"Provider 조회 성공"`         | GetProviderService |

### 미등록 (404)

| 필드      | 값                              | 추가 시점          |
| --------- | ------------------------------- | ------------------ |
| `user_id` | 유저 ID                         | GetProviderService |
| `outcome` | `"provider_not_found"`          | GetProviderService |
| `message` | `"Provider 미등록 사용자 조회"` | GetProviderService |

---

## POST /auth/provider/register

### 성공

| 필드            | 값                               | 추가 시점               |
| --------------- | -------------------------------- | ----------------------- |
| `user_id`       | 유저 ID                          | RegisterProviderService |
| `provider_id`   | 생성된 Provider ID               | RegisterProviderService |
| `provider_name` | Provider 이름                    | RegisterProviderService |
| `outcome`       | `"provider_registered"`          | RegisterProviderService |
| `message`       | `"Provider 등록 완료 (PENDING)"` | RegisterProviderService |

### 실패 — 중복 등록

| 필드         | 값                                 | 추가 시점            |
| ------------ | ---------------------------------- | -------------------- |
| `error_type` | `"ProviderAlreadyExistsException"` | AuthExceptionHandler |
| `outcome`    | `"provider_already_exists"`        | AuthExceptionHandler |

---

## POST /auth/provider/api-key

### 성공

| 필드             | 값                           | 추가 시점          |
| ---------------- | ---------------------------- | ------------------ |
| `user_id`        | 유저 ID                      | IssueApiKeyService |
| `provider_id`    | Provider ID                  | IssueApiKeyService |
| `api_key_id`     | 생성된 API Key ID            | IssueApiKeyService |
| `api_key_prefix` | 키 접두사 (e.g. `bk_a3f2e1`) | IssueApiKeyService |
| `outcome`        | `"api_key_issued"`           | IssueApiKeyService |
| `message`        | `"API Key 발급 완료"`        | IssueApiKeyService |

### 실패 — Provider 미활성

| 필드         | 값                             | 추가 시점            |
| ------------ | ------------------------------ | -------------------- |
| `error_type` | `"ProviderNotActiveException"` | AuthExceptionHandler |
| `outcome`    | `"provider_not_active"`        | AuthExceptionHandler |

### 실패 — 키 한도 초과 (5개)

| 필드         | 값                               | 추가 시점            |
| ------------ | -------------------------------- | -------------------- |
| `error_type` | `"ApiKeyLimitExceededException"` | AuthExceptionHandler |
| `outcome`    | `"api_key_limit_exceeded"`       | AuthExceptionHandler |

---

## GET /auth/provider/api-key

별도 WideEvent 필드 없음. `RequestLoggingFilter`가 자동 출력하는 기본 필드(method, path, status_code, duration_ms)로 충분.

---

## PATCH /auth/provider/api-key/{keyId}

### 성공

| 필드         | 값                       | 추가 시점               |
| ------------ | ------------------------ | ----------------------- |
| `user_id`    | 유저 ID                  | UpdateApiKeyNameService |
| `api_key_id` | 대상 API Key ID          | UpdateApiKeyNameService |
| `outcome`    | `"api_key_name_updated"` | UpdateApiKeyNameService |
| `message`    | `"API Key 이름 수정"`    | UpdateApiKeyNameService |

### 실패 — 키 없음 / 소유권 불일치

| 필드         | 값                          | 추가 시점            |
| ------------ | --------------------------- | -------------------- |
| `error_type` | `"ApiKeyNotFoundException"` | AuthExceptionHandler |
| `outcome`    | `"api_key_not_found"`       | AuthExceptionHandler |

---

## DELETE /auth/provider/api-key/{keyId}

### 성공

| 필드             | 값                  | 추가 시점           |
| ---------------- | ------------------- | ------------------- |
| `user_id`        | 유저 ID             | DeleteApiKeyService |
| `api_key_id`     | 삭제된 API Key ID   | DeleteApiKeyService |
| `api_key_prefix` | 삭제된 키 접두사    | DeleteApiKeyService |
| `outcome`        | `"api_key_deleted"` | DeleteApiKeyService |
| `message`        | `"API Key 삭제"`    | DeleteApiKeyService |

### 실패 — 키 없음 / 소유권 불일치

| 필드         | 값                          | 추가 시점            |
| ------------ | --------------------------- | -------------------- |
| `error_type` | `"ApiKeyNotFoundException"` | AuthExceptionHandler |
| `outcome`    | `"api_key_not_found"`       | AuthExceptionHandler |
