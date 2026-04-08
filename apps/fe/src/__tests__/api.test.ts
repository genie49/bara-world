// apps/fe/src/__tests__/api.test.ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveTokens, getAccessToken, getRefreshToken } from '../lib/auth'

let apiFetch: typeof import('../lib/api').apiFetch

describe('apiFetch', () => {
  beforeEach(async () => {
    localStorage.clear()
    vi.restoreAllMocks()
    const mod = await import('../lib/api')
    apiFetch = mod.apiFetch
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('요청에 Authorization 헤더를 자동 첨부한다', async () => {
    saveTokens('access-1', 'refresh-1')
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 })
    )

    await apiFetch('/api/test')

    expect(spy).toHaveBeenCalledWith('/api/test', expect.objectContaining({
      headers: expect.any(Headers),
    }))
    const headers = spy.mock.calls[0][1]?.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer access-1')
  })

  it('401 응답 시 refresh 후 재시도한다', async () => {
    saveTokens('expired-access', 'valid-refresh')

    let callCount = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, _init) => {
      const url = typeof input === 'string' ? input : (input as Request).url
      if (url.includes('/auth/refresh')) {
        return new Response(
          JSON.stringify({ accessToken: 'new-access', refreshToken: 'new-refresh', expiresIn: 3600 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }
      callCount++
      if (callCount === 1) {
        return new Response(null, { status: 401 })
      }
      return new Response(JSON.stringify({ data: 'ok' }), { status: 200 })
    })

    const res = await apiFetch('/api/test')

    expect(res.status).toBe(200)
    expect(getAccessToken()).toBe('new-access')
    expect(getRefreshToken()).toBe('new-refresh')
  })

  it('refresh 실패 시 토큰을 삭제한다', async () => {
    saveTokens('expired-access', 'invalid-refresh')

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url
      if (url.includes('/auth/refresh')) {
        return new Response(null, { status: 401 })
      }
      return new Response(null, { status: 401 })
    })

    await apiFetch('/api/test').catch(() => {})

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })
})
