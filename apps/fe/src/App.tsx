import { Route, Routes } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import CallbackPage from './pages/CallbackPage'
import Layout from './components/Layout'
import MePage from './pages/MePage'
import ProviderPage from './pages/ProviderPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/auth/callback" element={<CallbackPage />} />
      <Route element={<Layout />}>
        <Route path="/me" element={<MePage />} />
        <Route path="/provider" element={<ProviderPage />} />
      </Route>
    </Routes>
  )
}
