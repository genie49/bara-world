# FE Provider 관리 + 토큰 갱신 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #49/#50에서 구현한 백엔드 API(토큰 갱신, Provider/API Key)를 기존 FE에서 호출할 수 있도록 확장한다.

**Architecture:** 토큰 관리 인프라(auth 모듈 + fetch wrapper)를 먼저 깔고, 공통 Layout을 추가한 뒤, Provider 설정 페이지를 구현한다. 백엔드에는 `GET /auth/provider` 조회 API 1개를 추가한다.

**Tech Stack:** React 19, TypeScript, React Router DOM 7, Tailwind CSS 4, Vitest, Spring Boot (Kotlin)

---

## File Structure

### FE (apps/fe/src/)

| 파일                     | 역할                                            |
| ------------------------ | ----------------------------------------------- |
| `lib/auth.ts`            | 수정 — Access + Refresh Token 저장/조회/삭제    |
| `lib/api.ts`             | 신규 — fetch wrapper (인증 헤더, 401 자동 갱신) |
| `components/Layout.tsx`  | 신규 — 공통 헤더 네비게이션 + Outlet            |
| `pages/CallbackPage.tsx` | 수정 — refreshToken도 저장                      |
| `pages/MePage.tsx`       | 수정 — 새 auth API 사용 + Layout 내부에서 동작  |
| `pages/ProviderPage.tsx` | 신규 — Provider 등록 + API Key CRUD             |
| `App.tsx`                | 수정 — 라우팅 구조 변경 (Layout 적용)           |
| `__tests__/auth.test.ts` | 수정 — 새 auth API에 맞게 업데이트              |
| `__tests__/api.test.ts`  | 신규 — fetch wrapper 테스트                     |

### Backend (apps/auth/src/)

| 파일                                                 | 역할                        |
| ---------------------------------------------------- | --------------------------- |
| `application/port/in/query/GetProviderQuery.kt`      | 신규 — Provider 조회 포트   |
| `application/service/query/GetProviderService.kt`    | 신규 — Provider 조회 서비스 |
| `adapter/in/rest/ProviderController.kt`              | 수정 — GET 핸들러 추가      |
| `test/.../service/query/GetProviderServiceTest.kt`   | 신규                        |
| `test/.../adapter/in/rest/ProviderControllerTest.kt` | 수정 — GET 테스트 추가      |

---

## Task 1: 백엔드 — GET /auth/provider 엔드포인트

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/GetProviderQuery.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/service/query/GetProviderService.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/application/service/query/GetProviderServiceTest.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ProviderControllerTest.kt`

- [ ] **Step 1: GetProviderQuery 포트 작성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/GetProviderQuery.kt
package com.bara.auth.application.port.`in`.query

import com.bara.auth.domain.model.Provider

interface GetProviderQuery {
    fun getByUserId(userId: String): Provider?
}
```

- [ ] **Step 2: GetProviderServiceTest 작성 (실패 테스트)**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/application/service/query/GetProviderServiceTest.kt
package com.bara.auth.application.service.query

import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GetProviderServiceTest {

    private val providerRepository = mockk<ProviderRepository>()
    private val sut = GetProviderService(providerRepository)

    @Test
    fun `등록된 Provider가 있으면 반환한다`() {
        val provider = Provider(
            id = "p1",
            userId = "u1",
            name = "test",
            status = Provider.ProviderStatus.ACTIVE,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        every { providerRepository.findByUserId("u1") } returns provider

        val result = sut.getByUserId("u1")

        assertThat(result).isEqualTo(provider)
    }

    @Test
    fun `등록된 Provider가 없으면 null을 반환한다`() {
        every { providerRepository.findByUserId("u1") } returns null

        val result = sut.getByUserId("u1")

        assertThat(result).isNull()
    }
}
```

- [ ] **Step 3: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.application.service.query.GetProviderServiceTest"`
Expected: FAIL — `GetProviderService` 클래스 없음

- [ ] **Step 4: GetProviderService 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/service/query/GetProviderService.kt
package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.GetProviderQuery
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.model.Provider
import org.springframework.stereotype.Service

@Service
class GetProviderService(
    private val providerRepository: ProviderRepository,
) : GetProviderQuery {
    override fun getByUserId(userId: String): Provider? =
        providerRepository.findByUserId(userId)
}
```

- [ ] **Step 5: 테스트 실행 — 통과 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.application.service.query.GetProviderServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: ProviderControllerTest에 GET 테스트 추가**

기존 `ProviderControllerTest.kt`에 2개 테스트 추가:

```kotlin
// 기존 import에 추가
import com.bara.auth.application.port.`in`.query.GetProviderQuery

// 기존 클래스 필드에 추가
private val getProviderQuery = mockk<GetProviderQuery>()

// 기존 MockMvc 설정의 ProviderController 생성자에 getProviderQuery 추가
// ProviderController(registerUseCase, jwtVerifier) → ProviderController(registerUseCase, getProviderQuery, jwtVerifier)

// 테스트 추가:
@Test
fun `GET provider - 등록된 Provider가 있으면 200 반환`() {
    val claims = JwtClaims(userId = "u1", email = "a@b.com", role = "USER")
    every { jwtVerifier.verify("valid-token") } returns claims
    every { getProviderQuery.getByUserId("u1") } returns Provider(
        id = "p1",
        userId = "u1",
        name = "test",
        status = Provider.ProviderStatus.ACTIVE,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    mockMvc.perform(
        get("/auth/provider")
            .header("Authorization", "Bearer valid-token")
    )
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.id").value("p1"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
}

@Test
fun `GET provider - 미등록이면 404 반환`() {
    val claims = JwtClaims(userId = "u1", email = "a@b.com", role = "USER")
    every { jwtVerifier.verify("valid-token") } returns claims
    every { getProviderQuery.getByUserId("u1") } returns null

    mockMvc.perform(
        get("/auth/provider")
            .header("Authorization", "Bearer valid-token")
    )
        .andExpect(status().isNotFound)
}
```

- [ ] **Step 7: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.adapter.in.rest.ProviderControllerTest"`
Expected: FAIL — GET 핸들러 없음

- [ ] **Step 8: ProviderController에 GET 핸들러 추가**

`ProviderController.kt`에 생성자 파라미터와 엔드포인트 추가:

```kotlin
@RestController
@RequestMapping("/auth/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val getProviderQuery: GetProviderQuery,
    private val jwtVerifier: JwtVerifier,
) {
    @GetMapping
    fun get(
        @RequestHeader("Authorization") authorization: String,
    ): ResponseEntity<ProviderResponse> {
        val userId = extractUserId(authorization)
        val provider = getProviderQuery.getByUserId(userId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ProviderResponse(
                id = provider.id,
                name = provider.name,
                status = provider.status.name,
                createdAt = provider.createdAt.toString(),
            )
        )
    }

    // ... 기존 register(), extractUserId() 그대로 유지
}
```

- [ ] **Step 9: 전체 auth 테스트 통과 확인**

Run: `./gradlew :apps:auth:test`
Expected: ALL PASS

- [ ] **Step 10: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/GetProviderQuery.kt \
       apps/auth/src/main/kotlin/com/bara/auth/application/service/query/GetProviderService.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt \
       apps/auth/src/test/kotlin/com/bara/auth/application/service/query/GetProviderServiceTest.kt \
       apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ProviderControllerTest.kt
git commit -m "feat(auth): add GET /auth/provider endpoint for provider status lookup"
```

---

## Task 2: FE — 토큰 관리 모듈 리팩터링

**Files:**

- Modify: `apps/fe/src/lib/auth.ts`
- Modify: `apps/fe/src/__tests__/auth.test.ts`

- [ ] **Step 1: auth.test.ts 업데이트 (실패 테스트)**

```typescript
// apps/fe/src/__tests__/auth.test.ts
import { afterEach, describe, expect, it } from 'vitest';
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from '../lib/auth';

describe('auth storage', () => {
  afterEach(() => {
    localStorage.clear();
  });

  it('saveTokens 후 각각 조회 가능', () => {
    saveTokens('access-123', 'refresh-456');
    expect(getAccessToken()).toBe('access-123');
    expect(getRefreshToken()).toBe('refresh-456');
  });

  it('토큰이 없으면 null 반환', () => {
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it('clearTokens 후 모두 null', () => {
    saveTokens('a', 'r');
    clearTokens();
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd apps/fe && pnpm test`
Expected: FAIL — `saveTokens`, `getAccessToken`, `getRefreshToken`, `clearTokens` 없음

- [ ] **Step 3: auth.ts 구현**

```typescript
// apps/fe/src/lib/auth.ts
const ACCESS_KEY = 'bara.auth.token';
const REFRESH_KEY = 'bara.auth.refresh';

export function saveTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_KEY, accessToken);
  localStorage.setItem(REFRESH_KEY, refreshToken);
}

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd apps/fe && pnpm test`
Expected: PASS (auth 3 tests, jwt 2 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/fe/src/lib/auth.ts apps/fe/src/__tests__/auth.test.ts
git commit -m "refactor(fe): support access + refresh token storage"
```

---

## Task 3: FE — fetch wrapper (자동 토큰 갱신)

**Files:**

- Create: `apps/fe/src/lib/api.ts`
- Create: `apps/fe/src/__tests__/api.test.ts`

- [ ] **Step 1: api.test.ts 작성 (실패 테스트)**

```typescript
// apps/fe/src/__tests__/api.test.ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { saveTokens, getAccessToken, getRefreshToken, clearTokens } from '../lib/auth';

// apiFetch를 동적으로 import하기 위해 테스트 내에서 처리
let apiFetch: typeof import('../lib/api').apiFetch;

describe('apiFetch', () => {
  beforeEach(async () => {
    localStorage.clear();
    vi.restoreAllMocks();
    // 매 테스트마다 fresh import
    const mod = await import('../lib/api');
    apiFetch = mod.apiFetch;
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('요청에 Authorization 헤더를 자동 첨부한다', async () => {
    saveTokens('access-1', 'refresh-1');
    const spy = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(JSON.stringify({ ok: true }), { status: 200 }));

    await apiFetch('/api/test');

    expect(spy).toHaveBeenCalledWith(
      '/api/test',
      expect.objectContaining({
        headers: expect.any(Headers),
      }),
    );
    const headers = spy.mock.calls[0][1]?.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('401 응답 시 refresh 후 재시도한다', async () => {
    saveTokens('expired-access', 'valid-refresh');

    let callCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      // refresh 요청
      if (url.includes('/auth/refresh')) {
        return new Response(
          JSON.stringify({
            accessToken: 'new-access',
            refreshToken: 'new-refresh',
            expiresIn: 3600,
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        );
      }
      // 원래 요청: 첫 번째는 401, 두 번째는 200
      callCount++;
      if (callCount === 1) {
        return new Response(null, { status: 401 });
      }
      return new Response(JSON.stringify({ data: 'ok' }), { status: 200 });
    });

    const res = await apiFetch('/api/test');

    expect(res.status).toBe(200);
    expect(getAccessToken()).toBe('new-access');
    expect(getRefreshToken()).toBe('new-refresh');
  });

  it('refresh 실패 시 토큰 삭제하고 로그인 페이지로 이동한다', async () => {
    saveTokens('expired-access', 'invalid-refresh');

    // location.href 모킹
    const locationSpy = vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      href: '',
    } as Location);
    // href setter 추적을 위해 별도 변수 사용
    let redirectedTo = '';
    Object.defineProperty(window, 'location', {
      value: {
        ...window.location,
        set href(url: string) {
          redirectedTo = url;
        },
        get href() {
          return '';
        },
      },
      writable: true,
      configurable: true,
    });

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.includes('/auth/refresh')) {
        return new Response(null, { status: 401 });
      }
      return new Response(null, { status: 401 });
    });

    await apiFetch('/api/test').catch(() => {});

    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });
});
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd apps/fe && pnpm test`
Expected: FAIL — `apiFetch` 없음

- [ ] **Step 3: api.ts 구현**

```typescript
// apps/fe/src/lib/api.ts
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from './auth';

let refreshPromise: Promise<boolean> | null = null;

async function refreshTokens(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;

  try {
    const res = await fetch('/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!res.ok) return false;

    const data = await res.json();
    saveTokens(data.accessToken, data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

export async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const headers = new Headers(options?.headers);
  const accessToken = getAccessToken();
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }

  const res = await fetch(url, { ...options, headers });

  if (res.status !== 401) return res;

  // 401 — refresh 시도 (동시 요청은 Promise 공유)
  if (!refreshPromise) {
    refreshPromise = refreshTokens().finally(() => {
      refreshPromise = null;
    });
  }
  const refreshed = await refreshPromise;

  if (!refreshed) {
    clearTokens();
    window.location.href = '/';
    throw new Error('Session expired');
  }

  // 새 토큰으로 재시도
  const retryHeaders = new Headers(options?.headers);
  retryHeaders.set('Authorization', `Bearer ${getAccessToken()}`);
  return fetch(url, { ...options, headers: retryHeaders });
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd apps/fe && pnpm test`
Expected: ALL PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/fe/src/lib/api.ts apps/fe/src/__tests__/api.test.ts
git commit -m "feat(fe): add fetch wrapper with automatic token refresh"
```

---

## Task 4: FE — CallbackPage에서 refreshToken 저장

**Files:**

- Modify: `apps/fe/src/pages/CallbackPage.tsx`

- [ ] **Step 1: CallbackPage 수정**

```typescript
// apps/fe/src/pages/CallbackPage.tsx
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { saveTokens } from '../lib/auth'

export default function CallbackPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const token = params.get('token')
    const refreshToken = params.get('refreshToken')
    const err = params.get('error')
    if (err) {
      setError(err)
      return
    }
    if (token && refreshToken) {
      saveTokens(token, refreshToken)
      navigate('/me', { replace: true })
    }
  }, [params, navigate])

  if (error) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-4">
        <h1 className="text-2xl font-bold text-red-600">로그인 실패</h1>
        <p className="text-gray-700">{error}</p>
        <a href="/" className="text-blue-600 underline">
          돌아가기
        </a>
      </div>
    )
  }

  return (
    <div className="min-h-screen flex items-center justify-center">
      <p>로그인 처리 중...</p>
    </div>
  )
}
```

- [ ] **Step 2: FE 테스트 통과 확인**

Run: `cd apps/fe && pnpm test`
Expected: ALL PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/fe/src/pages/CallbackPage.tsx
git commit -m "feat(fe): save refresh token on OAuth callback"
```

---

## Task 5: FE — 공통 Layout + 라우팅 변경

**Files:**

- Create: `apps/fe/src/components/Layout.tsx`
- Modify: `apps/fe/src/App.tsx`
- Modify: `apps/fe/src/pages/MePage.tsx`

- [ ] **Step 1: Layout.tsx 작성**

```typescript
// apps/fe/src/components/Layout.tsx
import { useEffect } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { clearTokens, getAccessToken } from '../lib/auth'

export default function Layout() {
  const navigate = useNavigate()

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/', { replace: true })
    }
  }, [navigate])

  function logout() {
    clearTokens()
    navigate('/', { replace: true })
  }

  return (
    <div className="min-h-screen">
      <header className="border-b bg-white">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center justify-between">
          <span className="font-bold text-lg">Bara World</span>
          <nav className="flex gap-4">
            <NavLink
              to="/me"
              className={({ isActive }) =>
                isActive ? 'text-blue-600 font-semibold' : 'text-gray-600 hover:text-gray-900'
              }
            >
              내 정보
            </NavLink>
            <NavLink
              to="/provider"
              className={({ isActive }) =>
                isActive ? 'text-blue-600 font-semibold' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Provider 설정
            </NavLink>
          </nav>
          <button
            onClick={logout}
            className="px-3 py-1.5 text-sm bg-red-600 text-white rounded hover:bg-red-700"
          >
            로그아웃
          </button>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
```

- [ ] **Step 2: App.tsx 라우팅 변경**

```typescript
// apps/fe/src/App.tsx
import { Route, Routes } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import CallbackPage from './pages/CallbackPage'
import Layout from './components/Layout'
import MePage from './pages/MePage'
import ProviderPage from './pages/ProviderPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/auth/callback" element={<CallbackPage />} />
      <Route element={<Layout />}>
        <Route path="/me" element={<MePage />} />
        <Route path="/provider" element={<ProviderPage />} />
      </Route>
    </Routes>
  )
}
```

- [ ] **Step 3: MePage에서 Layout과 중복되는 로직 제거**

MePage에서 토큰 체크/리다이렉트(Layout이 담당)와 로그아웃 버튼을 제거한다:

```typescript
// apps/fe/src/pages/MePage.tsx
import { useEffect, useState } from 'react'
import { getAccessToken } from '../lib/auth'
import { decodeJwtPayload } from '../lib/jwt'

export default function MePage() {
  const [token, setToken] = useState<string | null>(null)
  const [payload, setPayload] = useState<Record<string, unknown> | null>(null)

  useEffect(() => {
    const t = getAccessToken()
    if (!t) return
    setToken(t)
    try {
      setPayload(decodeJwtPayload(t))
    } catch {
      setPayload(null)
    }
  }, [])

  function copyToken() {
    if (token) navigator.clipboard.writeText(token)
  }

  if (!token) return null

  return (
    <div>
      <h1 className="text-3xl font-bold mb-6">My Info</h1>
      {payload && (
        <div className="bg-gray-100 rounded-lg p-4 mb-6">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
            <dt className="font-semibold">Email</dt>
            <dd>{String(payload.email ?? '-')}</dd>
            <dt className="font-semibold">Role</dt>
            <dd>{String(payload.role ?? '-')}</dd>
            <dt className="font-semibold">User ID</dt>
            <dd className="font-mono text-sm">{String(payload.sub ?? '-')}</dd>
            <dt className="font-semibold">Expires</dt>
            <dd>
              {payload.exp ? new Date(Number(payload.exp) * 1000).toLocaleString() : '-'}
            </dd>
          </dl>
        </div>
      )}
      <div className="mb-6">
        <label className="block font-semibold mb-2">Raw JWT</label>
        <textarea
          readOnly
          value={token}
          className="w-full h-32 p-2 border rounded font-mono text-xs"
        />
        <button
          onClick={copyToken}
          className="mt-2 px-4 py-2 bg-gray-200 rounded hover:bg-gray-300"
        >
          Copy token
        </button>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: ProviderPage 스텁 생성 (빌드 통과용)**

```typescript
// apps/fe/src/pages/ProviderPage.tsx
export default function ProviderPage() {
  return <div>Provider 설정 (준비 중)</div>
}
```

- [ ] **Step 5: FE 빌드 + 테스트 확인**

Run: `cd apps/fe && pnpm test && pnpm build`
Expected: ALL PASS, 빌드 성공

- [ ] **Step 6: 커밋**

```bash
git add apps/fe/src/components/Layout.tsx \
       apps/fe/src/pages/ProviderPage.tsx \
       apps/fe/src/pages/MePage.tsx \
       apps/fe/src/App.tsx
git commit -m "feat(fe): add shared layout with header navigation"
```

---

## Task 6: FE — Vite proxy 설정 추가

**Files:**

- Modify: `apps/fe/vite.config.ts`

현재 `/auth/google`만 프록시되어 있다. FE에서 호출하는 모든 auth API가 백엔드(8081)로 프록시되도록 설정한다.

- [ ] **Step 1: vite.config.ts 프록시 확장**

```typescript
// apps/fe/vite.config.ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/auth': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/__tests__/setup.ts'],
  },
});
```

기존 `/auth/google`을 `/auth`로 확장하면 refresh, provider, api-key 엔드포인트가 모두 프록시된다.

- [ ] **Step 2: 커밋**

```bash
git add apps/fe/vite.config.ts
git commit -m "chore(fe): proxy all /auth endpoints to backend"
```

---

## Task 7: FE — Provider 설정 페이지 구현

**Files:**

- Modify: `apps/fe/src/pages/ProviderPage.tsx`

- [ ] **Step 1: ProviderPage 전체 구현**

```typescript
// apps/fe/src/pages/ProviderPage.tsx
import { useEffect, useState } from 'react'
import { apiFetch } from '../lib/api'

interface ProviderInfo {
  id: string
  name: string
  status: string
  createdAt: string
}

interface ApiKeyInfo {
  id: string
  name: string
  prefix: string
  createdAt: string
}

type ProviderState =
  | { kind: 'loading' }
  | { kind: 'unregistered' }
  | { kind: 'registered'; provider: ProviderInfo }

export default function ProviderPage() {
  const [state, setState] = useState<ProviderState>({ kind: 'loading' })
  const [apiKeys, setApiKeys] = useState<ApiKeyInfo[]>([])
  const [newKeyName, setNewKeyName] = useState('')
  const [showNewKeyModal, setShowNewKeyModal] = useState(false)
  const [issuedKey, setIssuedKey] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    loadProvider()
  }, [])

  async function loadProvider() {
    const res = await apiFetch('/auth/provider')
    if (res.status === 404) {
      setState({ kind: 'unregistered' })
      return
    }
    if (!res.ok) {
      setError('Provider 정보를 불러올 수 없습니다')
      return
    }
    const provider: ProviderInfo = await res.json()
    setState({ kind: 'registered', provider })
    if (provider.status === 'ACTIVE') {
      loadApiKeys()
    }
  }

  async function loadApiKeys() {
    const res = await apiFetch('/auth/provider/api-key')
    if (res.ok) {
      const data = await res.json()
      setApiKeys(data.keys)
    }
  }

  async function register() {
    const res = await apiFetch('/auth/provider/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'default' }),
    })
    if (res.ok) {
      loadProvider()
    }
  }

  async function issueApiKey() {
    if (!newKeyName.trim()) return
    const res = await apiFetch('/auth/provider/api-key', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: newKeyName }),
    })
    if (res.ok) {
      const data = await res.json()
      setIssuedKey(data.apiKey)
      setNewKeyName('')
      setShowNewKeyModal(false)
      loadApiKeys()
    }
  }

  async function updateKeyName(keyId: string) {
    if (!editName.trim()) return
    const res = await apiFetch(`/auth/provider/api-key/${keyId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: editName }),
    })
    if (res.ok) {
      setEditingId(null)
      setEditName('')
      loadApiKeys()
    }
  }

  async function deleteKey(keyId: string) {
    if (!confirm('이 API Key를 삭제하시겠습니까? 즉시 사용이 차단됩니다.')) return
    const res = await apiFetch(`/auth/provider/api-key/${keyId}`, { method: 'DELETE' })
    if (res.ok) {
      loadApiKeys()
    }
  }

  if (error) {
    return <p className="text-red-600">{error}</p>
  }

  if (state.kind === 'loading') {
    return <p>로딩 중...</p>
  }

  if (state.kind === 'unregistered') {
    return (
      <div>
        <h1 className="text-3xl font-bold mb-6">Provider 설정</h1>
        <p className="mb-4 text-gray-600">Provider로 등록하면 API Key를 발급받아 Agent를 연동할 수 있습니다.</p>
        <button
          onClick={register}
          className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
        >
          Provider로 등록하기
        </button>
      </div>
    )
  }

  const { provider } = state

  return (
    <div>
      <h1 className="text-3xl font-bold mb-6">Provider 설정</h1>

      {/* Provider 상태 */}
      <div className="bg-gray-100 rounded-lg p-4 mb-6">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
          <dt className="font-semibold">상태</dt>
          <dd>
            <span className={provider.status === 'ACTIVE'
              ? 'text-green-600 font-semibold'
              : 'text-yellow-600 font-semibold'
            }>
              {provider.status === 'ACTIVE' ? '활성' : '승인 대기'}
            </span>
          </dd>
          <dt className="font-semibold">등록일</dt>
          <dd>{new Date(provider.createdAt).toLocaleDateString()}</dd>
        </dl>
      </div>

      {provider.status === 'PENDING' && (
        <p className="text-yellow-600">관리자 승인 후 API Key를 발급할 수 있습니다.</p>
      )}

      {provider.status === 'ACTIVE' && (
        <div>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-semibold">API Keys</h2>
            <button
              onClick={() => setShowNewKeyModal(true)}
              disabled={apiKeys.length >= 5}
              className="px-3 py-1.5 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 disabled:bg-gray-400 disabled:cursor-not-allowed"
            >
              새 API Key 발급
            </button>
          </div>

          {apiKeys.length >= 5 && (
            <p className="text-sm text-gray-500 mb-4">최대 5개까지 발급할 수 있습니다.</p>
          )}

          {/* 발급된 키 1회 표시 */}
          {issuedKey && (
            <div className="mb-4 p-4 bg-green-50 border border-green-200 rounded">
              <p className="font-semibold text-green-800 mb-2">API Key가 발급되었습니다. 이 키는 다시 볼 수 없습니다.</p>
              <div className="flex gap-2">
                <code className="flex-1 p-2 bg-white rounded font-mono text-sm break-all">{issuedKey}</code>
                <button
                  onClick={() => navigator.clipboard.writeText(issuedKey)}
                  className="px-3 py-1.5 bg-gray-200 rounded hover:bg-gray-300 text-sm shrink-0"
                >
                  복사
                </button>
              </div>
              <button
                onClick={() => setIssuedKey(null)}
                className="mt-2 text-sm text-gray-500 hover:text-gray-700"
              >
                닫기
              </button>
            </div>
          )}

          {/* 이름 입력 모달 */}
          {showNewKeyModal && (
            <div className="mb-4 p-4 bg-gray-50 border rounded">
              <label className="block font-semibold mb-2">API Key 이름</label>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={newKeyName}
                  onChange={(e) => setNewKeyName(e.target.value)}
                  placeholder="예: my-agent-key"
                  className="flex-1 px-3 py-2 border rounded"
                  onKeyDown={(e) => e.key === 'Enter' && issueApiKey()}
                />
                <button onClick={issueApiKey} className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700">발급</button>
                <button onClick={() => setShowNewKeyModal(false)} className="px-4 py-2 bg-gray-200 rounded hover:bg-gray-300">취소</button>
              </div>
            </div>
          )}

          {/* API Key 목록 */}
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2 pr-4">이름</th>
                <th className="py-2 pr-4">Prefix</th>
                <th className="py-2 pr-4">생성일</th>
                <th className="py-2"></th>
              </tr>
            </thead>
            <tbody>
              {apiKeys.map((key) => (
                <tr key={key.id} className="border-b">
                  <td className="py-2 pr-4">
                    {editingId === key.id ? (
                      <div className="flex gap-1">
                        <input
                          type="text"
                          value={editName}
                          onChange={(e) => setEditName(e.target.value)}
                          className="px-2 py-1 border rounded text-sm"
                          onKeyDown={(e) => e.key === 'Enter' && updateKeyName(key.id)}
                        />
                        <button onClick={() => updateKeyName(key.id)} className="text-sm text-blue-600">저장</button>
                        <button onClick={() => setEditingId(null)} className="text-sm text-gray-500">취소</button>
                      </div>
                    ) : (
                      <button
                        onClick={() => { setEditingId(key.id); setEditName(key.name) }}
                        className="hover:text-blue-600 hover:underline"
                      >
                        {key.name}
                      </button>
                    )}
                  </td>
                  <td className="py-2 pr-4 font-mono text-sm text-gray-500">{key.prefix}...</td>
                  <td className="py-2 pr-4 text-sm text-gray-500">{new Date(key.createdAt).toLocaleDateString()}</td>
                  <td className="py-2">
                    <button onClick={() => deleteKey(key.id)} className="text-sm text-red-600 hover:text-red-800">삭제</button>
                  </td>
                </tr>
              ))}
              {apiKeys.length === 0 && (
                <tr><td colSpan={4} className="py-4 text-center text-gray-500">발급된 API Key가 없습니다</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: FE 빌드 확인**

Run: `cd apps/fe && pnpm build`
Expected: 빌드 성공

- [ ] **Step 3: 커밋**

```bash
git add apps/fe/src/pages/ProviderPage.tsx
git commit -m "feat(fe): implement provider registration and API key management page"
```

---

## Task 8: E2E 수동 테스트 + 최종 확인

- [ ] **Step 1: 인프라 + 백엔드 + FE 실행**

```bash
./scripts/infra.sh up
./gradlew :apps:auth:bootRun &
cd apps/fe && pnpm dev
```

- [ ] **Step 2: 수동 테스트 체크리스트**

1. `http://localhost:5173/` → Google 로그인 → `/me`로 리다이렉트 (Access + Refresh Token 저장 확인)
2. 헤더 네비게이션 동작: "내 정보" ↔ "Provider 설정" 이동
3. Provider 미등록 상태 → "Provider로 등록하기" 클릭 → PENDING 상태 표시
4. (MongoDB에서 Provider status를 ACTIVE로 변경 후) API Key 발급/목록/이름수정/삭제
5. 로그아웃 → 토큰 삭제 확인

- [ ] **Step 3: 전체 테스트 통과 확인**

```bash
./gradlew :apps:auth:test
cd apps/fe && pnpm test
```

Expected: ALL PASS
