import { useEffect } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { clearTokens, getAccessToken } from '../lib/auth'

export default function Layout() {
  const navigate = useNavigate()

  useEffect(() => {
    if (!getAccessToken()) {
      navigate('/', { replace: true })
    }
  }, [navigate])

  function logout() {
    clearTokens()
    navigate('/', { replace: true })
  }

  return (
    <div className="min-h-screen">
      <header className="border-b bg-white">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center justify-between">
          <span className="font-bold text-lg">Bara World</span>
          <nav className="flex gap-4">
            <NavLink
              to="/me"
              className={({ isActive }) =>
                isActive ? 'text-blue-600 font-semibold' : 'text-gray-600 hover:text-gray-900'
              }
            >
              내 정보
            </NavLink>
            <NavLink
              to="/provider"
              className={({ isActive }) =>
                isActive ? 'text-blue-600 font-semibold' : 'text-gray-600 hover:text-gray-900'
              }
            >
              Provider 설정
            </NavLink>
          </nav>
          <button
            onClick={logout}
            className="px-3 py-1.5 text-sm bg-red-600 text-white rounded hover:bg-red-700"
          >
            로그아웃
          </button>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
