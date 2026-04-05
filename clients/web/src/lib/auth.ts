const KEY = 'bara.auth.token'

export function saveToken(token: string): void {
  localStorage.setItem(KEY, token)
}

export function getToken(): string | null {
  return localStorage.getItem(KEY)
}

export function clearToken(): void {
  localStorage.removeItem(KEY)
}
