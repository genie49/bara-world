import { describe, expect, it } from 'vitest'
import { decodeJwtPayload } from '../lib/jwt'

describe('decodeJwtPayload', () => {
  it('base64url 인코딩된 payload를 디코드한다', () => {
    // header.payload.signature — payload는 {"sub":"user-1","email":"a@b.com","role":"USER"}
    const payload = btoa(JSON.stringify({ sub: 'user-1', email: 'a@b.com', role: 'USER' }))
      .replace(/=/g, '')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
    const token = `header.${payload}.sig`

    const result = decodeJwtPayload(token)

    expect(result.sub).toBe('user-1')
    expect(result.email).toBe('a@b.com')
    expect(result.role).toBe('USER')
  })

  it('형식이 잘못된 토큰은 예외를 던진다', () => {
    expect(() => decodeJwtPayload('not-a-jwt')).toThrow()
  })
})
