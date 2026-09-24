import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider'
import { HomeRoute, RedirectIfAuthed, RequireAuth } from './auth/RouteGuards'
import { AppLayout } from './components/layout/AppLayout'
import { AuthLayout } from './components/layout/AuthLayout'
import { LoginPage } from './pages/auth/LoginPage'
import { SignupPage } from './pages/auth/SignupPage'
import { SettingsPage } from './pages/settings/SettingsPage'
import { BillingPage } from './pages/BillingPage'
import { ComingSoonPage } from './pages/ComingSoonPage'
import { IncidentDetailPage } from './pages/IncidentDetailPage'
import { IncidentsPage } from './pages/IncidentsPage'
import { MonitorDetailPage } from './pages/MonitorDetailPage'
import { MonitorFormPage } from './pages/MonitorFormPage'
import { MonitorsPage } from './pages/MonitorsPage'
import { LandingPage } from './pages/public/LandingPage'
import { PublicStatusPage } from './pages/public/PublicStatusPage'
import { StatusPageEditor } from './pages/StatusPageEditor'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route index element={<HomeRoute landing={<LandingPage />} />} />

          {/* Public: signed-in users are sent on to the app. */}
          <Route element={<RedirectIfAuthed />}>
            <Route element={<AuthLayout />}>
              <Route path="login" element={<LoginPage />} />
              <Route path="signup" element={<SignupPage />} />
            </Route>
          </Route>

          {/* Public status pages: no session, no app chrome. */}
          <Route path="status/:slug" element={<PublicStatusPage />} />

          {/* Everything else needs a session. */}
          <Route element={<RequireAuth />}>
            <Route element={<AppLayout />}>
              <Route path="monitors" element={<MonitorsPage />} />
              <Route path="monitors/new" element={<MonitorFormPage key="new" />} />
              <Route path="monitors/:id/edit" element={<MonitorFormPage key="edit" />} />
              <Route path="monitors/:id" element={<MonitorDetailPage />} />
              <Route path="incidents" element={<IncidentsPage />} />
              <Route path="incidents/:id" element={<IncidentDetailPage />} />
              <Route path="status-page" element={<StatusPageEditor />} />
              <Route path="billing" element={<BillingPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<ComingSoonPage title="Page not found" part="nowhere — check the URL" />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
