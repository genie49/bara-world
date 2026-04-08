// apps/fe/src/__tests__/auth.test.ts
import { afterEach, describe, expect, it } from 'vitest'
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from '../lib/auth'

describe('auth storage', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('saveTokens 후 각각 조회 가능', () => {
    saveTokens('access-123', 'refresh-456')
    expect(getAccessToken()).toBe('access-123')
    expect(getRefreshToken()).toBe('refresh-456')
  })

  it('토큰이 없으면 null 반환', () => {
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('clearTokens 후 모두 null', () => {
    saveTokens('a', 'r')
    clearTokens()
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })
})
