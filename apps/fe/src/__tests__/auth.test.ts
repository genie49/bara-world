import { afterEach, describe, expect, it } from 'vitest'
import { clearToken, getToken, saveToken } from '../lib/auth'

describe('auth storage', () => {
  afterEach(() => {
    localStorage.clear()
  })

  it('saveToken 후 getToken으로 같은 값이 반환된다', () => {
    saveToken('abc.def.ghi')
    expect(getToken()).toBe('abc.def.ghi')
  })

  it('토큰이 없으면 getToken은 null을 반환한다', () => {
    expect(getToken()).toBeNull()
  })

  it('clearToken 후 getToken은 null', () => {
    saveToken('t')
    clearToken()
    expect(getToken()).toBeNull()
  })
})
