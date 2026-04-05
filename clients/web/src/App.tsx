import { Route, Routes } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import CallbackPage from './pages/CallbackPage'
import MePage from './pages/MePage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/auth/callback" element={<CallbackPage />} />
      <Route path="/me" element={<MePage />} />
    </Routes>
  )
}
