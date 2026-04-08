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
