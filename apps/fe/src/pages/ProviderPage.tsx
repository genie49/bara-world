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
