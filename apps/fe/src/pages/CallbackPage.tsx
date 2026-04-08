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
