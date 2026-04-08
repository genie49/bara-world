import { useEffect, useState } from 'react'
import { apiFetch } from '../lib/api'

interface AgentSummary {
  id: string
  name: string
  providerId: string
  createdAt: string
}

interface AgentSkill {
  id: string
  name: string
  description: string
  tags: string[]
  examples: string[]
}

interface AgentCard {
  name: string
  description: string
  version: string
  defaultInputModes: string[]
  defaultOutputModes: string[]
  capabilities: { streaming: boolean; pushNotifications: boolean }
  skills: AgentSkill[]
  iconUrl: string | null
}

interface AgentDetail {
  id: string
  name: string
  providerId: string
  agentCard: AgentCard
  createdAt: string
}

export default function AgentsPage() {
  const [agents, setAgents] = useState<AgentSummary[]>([])
  const [selectedAgent, setSelectedAgent] = useState<AgentDetail | null>(null)
  const [showRegisterForm, setShowRegisterForm] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Register form state
  const [formName, setFormName] = useState('')
  const [formDescription, setFormDescription] = useState('')
  const [formVersion, setFormVersion] = useState('1.0.0')
  const [formSkillId, setFormSkillId] = useState('')
  const [formSkillName, setFormSkillName] = useState('')
  const [formSkillDesc, setFormSkillDesc] = useState('')

  useEffect(() => {
    loadAgents()
  }, [])

  async function loadAgents() {
    try {
      const res = await apiFetch('/api/core/agents')
      if (res.ok) {
        const data = await res.json()
        setAgents(data.agents)
      }
    } catch {
      setError('Agent 목록을 불러올 수 없습니다')
    }
  }

  async function selectAgent(id: string) {
    const res = await apiFetch(`/api/core/agents/${id}`)
    if (res.ok) {
      setSelectedAgent(await res.json())
    }
  }

  async function registerAgent() {
    if (!formName.trim()) return
    setError(null)

    const body = {
      name: formName,
      agentCard: {
        name: formName,
        description: formDescription || `${formName} agent`,
        version: formVersion,
        defaultInputModes: ['text/plain'],
        defaultOutputModes: ['text/plain'],
        capabilities: { streaming: false, pushNotifications: false },
        skills: formSkillId.trim()
          ? [{ id: formSkillId, name: formSkillName || formSkillId, description: formSkillDesc || 'A skill' }]
          : [],
      },
    }

    const res = await apiFetch('/api/core/agents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })

    if (res.ok) {
      setShowRegisterForm(false)
      setFormName('')
      setFormDescription('')
      setFormVersion('1.0.0')
      setFormSkillId('')
      setFormSkillName('')
      setFormSkillDesc('')
      loadAgents()
    } else {
      const data = await res.json().catch(() => null)
      setError(data?.message || `등록 실패 (${res.status})`)
    }
  }

  async function deleteAgent(id: string) {
    if (!confirm('이 Agent를 삭제하시겠습니까?')) return
    const res = await apiFetch(`/api/core/agents/${id}`, { method: 'DELETE' })
    if (res.ok) {
      if (selectedAgent?.id === id) setSelectedAgent(null)
      loadAgents()
    } else {
      const data = await res.json().catch(() => null)
      setError(data?.message || `삭제 실패 (${res.status})`)
    }
  }

  return (
    <div>
      <h1 className="text-3xl font-bold mb-6">Agents</h1>

      {error && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded text-red-700">
          {error}
          <button onClick={() => setError(null)} className="ml-2 text-sm underline">닫기</button>
        </div>
      )}

      <div className="flex items-center justify-between mb-4">
        <h2 className="text-xl font-semibold">등록된 Agent</h2>
        <button
          onClick={() => setShowRegisterForm(!showRegisterForm)}
          className="px-3 py-1.5 bg-blue-600 text-white text-sm rounded hover:bg-blue-700"
        >
          {showRegisterForm ? '취소' : '새 Agent 등록'}
        </button>
      </div>

      {showRegisterForm && (
        <div className="mb-6 p-4 bg-gray-50 border rounded">
          <h3 className="font-semibold mb-3">Agent 등록</h3>
          <div className="grid gap-3">
            <div>
              <label className="block text-sm font-medium mb-1">이름 *</label>
              <input
                type="text"
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder="예: my-translator"
                className="w-full px-3 py-2 border rounded"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">설명</label>
              <input
                type="text"
                value={formDescription}
                onChange={(e) => setFormDescription(e.target.value)}
                placeholder="예: 다국어 번역 Agent"
                className="w-full px-3 py-2 border rounded"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">버전</label>
              <input
                type="text"
                value={formVersion}
                onChange={(e) => setFormVersion(e.target.value)}
                className="w-full px-3 py-2 border rounded"
              />
            </div>
            <div className="border-t pt-3 mt-1">
              <label className="block text-sm font-medium mb-1">스킬 (선택)</label>
              <div className="grid grid-cols-3 gap-2">
                <input
                  type="text"
                  value={formSkillId}
                  onChange={(e) => setFormSkillId(e.target.value)}
                  placeholder="스킬 ID"
                  className="px-3 py-2 border rounded"
                />
                <input
                  type="text"
                  value={formSkillName}
                  onChange={(e) => setFormSkillName(e.target.value)}
                  placeholder="스킬 이름"
                  className="px-3 py-2 border rounded"
                />
                <input
                  type="text"
                  value={formSkillDesc}
                  onChange={(e) => setFormSkillDesc(e.target.value)}
                  placeholder="스킬 설명"
                  className="px-3 py-2 border rounded"
                />
              </div>
            </div>
            <button
              onClick={registerAgent}
              className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 justify-self-start"
            >
              등록
            </button>
          </div>
        </div>
      )}

      <table className="w-full border-collapse mb-8">
        <thead>
          <tr className="border-b text-left">
            <th className="py-2 pr-4">이름</th>
            <th className="py-2 pr-4">Provider</th>
            <th className="py-2 pr-4">생성일</th>
            <th className="py-2"></th>
          </tr>
        </thead>
        <tbody>
          {agents.map((agent) => (
            <tr key={agent.id} className="border-b">
              <td className="py-2 pr-4">
                <button
                  onClick={() => selectAgent(agent.id)}
                  className="text-blue-600 hover:underline"
                >
                  {agent.name}
                </button>
              </td>
              <td className="py-2 pr-4 text-sm text-gray-500 font-mono">{agent.providerId.slice(0, 8)}...</td>
              <td className="py-2 pr-4 text-sm text-gray-500">{new Date(agent.createdAt).toLocaleDateString()}</td>
              <td className="py-2">
                <button onClick={() => deleteAgent(agent.id)} className="text-sm text-red-600 hover:text-red-800">삭제</button>
              </td>
            </tr>
          ))}
          {agents.length === 0 && (
            <tr><td colSpan={4} className="py-4 text-center text-gray-500">등록된 Agent가 없습니다</td></tr>
          )}
        </tbody>
      </table>

      {selectedAgent && (
        <div className="border rounded-lg p-4">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-xl font-semibold">{selectedAgent.name}</h2>
            <button onClick={() => setSelectedAgent(null)} className="text-sm text-gray-500 hover:text-gray-700">닫기</button>
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 mb-4">
            <dt className="font-semibold">ID</dt>
            <dd className="font-mono text-sm">{selectedAgent.id}</dd>
            <dt className="font-semibold">Provider</dt>
            <dd className="font-mono text-sm">{selectedAgent.providerId}</dd>
            <dt className="font-semibold">버전</dt>
            <dd>{selectedAgent.agentCard.version}</dd>
            <dt className="font-semibold">설명</dt>
            <dd>{selectedAgent.agentCard.description}</dd>
            <dt className="font-semibold">입력</dt>
            <dd>{selectedAgent.agentCard.defaultInputModes.join(', ')}</dd>
            <dt className="font-semibold">출력</dt>
            <dd>{selectedAgent.agentCard.defaultOutputModes.join(', ')}</dd>
            <dt className="font-semibold">Streaming</dt>
            <dd>{selectedAgent.agentCard.capabilities.streaming ? '지원' : '미지원'}</dd>
          </dl>
          {selectedAgent.agentCard.skills.length > 0 && (
            <div>
              <h3 className="font-semibold mb-2">스킬</h3>
              <ul className="space-y-1">
                {selectedAgent.agentCard.skills.map((skill) => (
                  <li key={skill.id} className="text-sm bg-gray-50 rounded p-2">
                    <span className="font-medium">{skill.name}</span>
                    <span className="text-gray-500 ml-2">{skill.description}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
