// apps/fe/src/lib/api.ts
import { clearTokens, getAccessToken, getRefreshToken, saveTokens } from './auth'

let refreshPromise: Promise<boolean> | null = null

async function refreshTokens(): Promise<boolean> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false

  try {
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!res.ok) return false

    const data = await res.json()
    saveTokens(data.accessToken, data.refreshToken)
    return true
  } catch {
    return false
  }
}

export async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const headers = new Headers(options?.headers)
  const accessToken = getAccessToken()
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const res = await fetch(url, { ...options, headers })

  if (res.status !== 401) return res

  if (!refreshPromise) {
    refreshPromise = refreshTokens().finally(() => { refreshPromise = null })
  }
  const refreshed = await refreshPromise

  if (!refreshed) {
    clearTokens()
    window.location.href = '/'
    throw new Error('Session expired')
  }

  const retryHeaders = new Headers(options?.headers)
  retryHeaders.set('Authorization', `Bearer ${getAccessToken()}`)
  return fetch(url, { ...options, headers: retryHeaders })
}
