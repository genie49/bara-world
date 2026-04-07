# FE Provider 관리 + 토큰 갱신 설계

## 개요

PR #49(Access/Refresh Token)과 PR #50(Provider/API Key CRUD)에서 구현한 백엔드 API를 기존 `apps/fe/`에서 사용할 수 있도록 FE를 확장한다.

## 범위

1. 토큰 관리 모듈 (Access + Refresh Token 저장, 자동 갱신)
2. 공통 레이아웃 (헤더 네비게이션)
3. Provider 설정 페이지 (등록 + API Key CRUD)
4. 백엔드 `GET /auth/provider` 엔드포인트 추가

## 1. 토큰 관리 모듈

### `lib/auth.ts` 변경

기존 accessToken만 저장하는 구조에서 refreshToken도 함께 관리:

- `saveTokens(access, refresh)` — 두 토큰 모두 localStorage에 저장
- `getAccessToken()` — `bara.auth.token` 반환
- `getRefreshToken()` — `bara.auth.refresh` 반환
- `clearTokens()` — 두 키 모두 삭제

기존 `saveToken()`, `getToken()`, `clearToken()`은 제거하고 새 함수로 대체.

### `CallbackPage` 변경

백엔드 콜백이 `?token=...&refreshToken=...` 두 파라미터를 내려주므로, 둘 다 저장:

```
const token = params.get('token')
const refreshToken = params.get('refreshToken')
if (token && refreshToken) {
  saveTokens(token, refreshToken)
  navigate('/me', { replace: true })
}
```

### `lib/api.ts` 신규 — fetch wrapper

```ts
apiFetch(url: string, options?: RequestInit): Promise<Response>
```

동작:

- 모든 요청에 `Authorization: Bearer {accessToken}` 자동 첨부
- 401 응답 시:
  1. `POST /auth/refresh` (body: `{ refreshToken }`) 호출
  2. 새 토큰쌍 수신 → `saveTokens()` → 원래 요청 재시도
  3. refresh도 실패 → `clearTokens()` → `window.location.href = '/'`
- 동시 다발 401 대응: refresh Promise를 공유하여 1번만 실행

## 2. 공통 레이아웃

### `components/Layout.tsx` 신규

- 상단 헤더 바: 앱명(좌), 네비게이션 링크(중), 로그아웃 버튼(우)
- 네비게이션 항목: **내 정보** (`/me`), **Provider 설정** (`/provider`)
- `<Outlet />`으로 하위 페이지 렌더링
- 마운트 시 accessToken 존재 여부 체크 → 없으면 `/`로 리다이렉트

### 라우팅 변경 (`App.tsx`)

```
/                    → LoginPage (레이아웃 없음)
/auth/callback       → CallbackPage (레이아웃 없음)

Layout 적용:
  /me                → MePage
  /provider          → ProviderPage
```

## 3. Provider 설정 페이지

### `/provider` → `pages/ProviderPage.tsx`

페이지 진입 시 `GET /auth/provider`로 Provider 상태 조회. 응답에 따라 3가지 상태 분기:

#### Provider 미등록 (404)

- "Provider로 등록하기" 버튼 표시
- 클릭 시 `POST /auth/provider/register` → 성공 시 PENDING 상태로 전환

#### Provider PENDING

- "승인 대기 중" 상태 표시
- API Key 섹션 비활성

#### Provider ACTIVE

- Provider 상태 표시
- API Key 관리 섹션 노출

### API Key 관리 섹션

#### 목록

- `GET /auth/provider/api-key` → 테이블(이름, prefix, 생성일)

#### 발급

- "새 API Key 발급" 버튼 → 이름 입력 모달
- `POST /auth/provider/api-key` → 원본 키 1회 표시
- 복사 버튼 + "이 키는 다시 볼 수 없습니다" 경고
- 최대 5개 도달 시 발급 버튼 비활성 + 안내 문구

#### 이름 수정

- 목록에서 이름 클릭 → 인라인 편집
- `PATCH /auth/provider/api-key/{keyId}`

#### 삭제

- 삭제 버튼 → 확인 대화상자
- `DELETE /auth/provider/api-key/{keyId}`

## 4. 백엔드: `GET /auth/provider` 추가

현재 Provider 상태를 조회하는 API가 없으므로 추가 필요:

- **엔드포인트:** `GET /auth/provider`
- **인증:** User Access Token 필수
- **응답 200:**
  ```json
  {
    "id": "...",
    "userId": "...",
    "status": "PENDING" | "ACTIVE",
    "createdAt": "..."
  }
  ```
- **응답 404:** Provider 미등록

헥사고날 구조를 따라 구현:

- `GetProviderQuery` 포트 (in)
- `GetProviderService` 서비스
- `ProviderController`에 `GET` 핸들러 추가
- 기존 `ProviderRepository.findByUserId()` 활용

## 사용하는 백엔드 API 요약

| Method | Path                           | 용도                      |
| ------ | ------------------------------ | ------------------------- |
| POST   | /auth/refresh                  | 토큰 갱신                 |
| GET    | /auth/provider                 | Provider 상태 조회 (신규) |
| POST   | /auth/provider/register        | Provider 등록             |
| GET    | /auth/provider/api-key         | API Key 목록              |
| POST   | /auth/provider/api-key         | API Key 발급              |
| PATCH  | /auth/provider/api-key/{keyId} | API Key 이름 수정         |
| DELETE | /auth/provider/api-key/{keyId} | API Key 삭제              |
