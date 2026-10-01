import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider'
import { HomeRoute, RedirectIfAuthed, RequireAuth, SignedInOrPublic } from './auth/RouteGuards'
import { PageBoundary } from './components/PageBoundary'
import { AuthLayout } from './components/layout/AuthLayout'
import {
  AppLayout,
  BillingPage,
  DocArticlePage,
  DocsPage,
  EmailLinkPage,
  ForgotPasswordPage,
  IncidentDetailPage,
  IncidentsPage,
  LandingPage,
  LoginPage,
  MonitorDetailPage,
  MonitorFormPage,
  MonitorsPage,
  NotFoundPage,
  PublicDocsLayout,
  PublicStatusPage,
  ResetPasswordPage,
  SettingsPage,
  SignupPage,
  StatusPageEditor,
} from './routes/pages'

export default function App() {
  // No transitions: while a page's code loads, show the spinner in its place rather than
  // keeping the previous page on screen, where a click or keystroke would land on it.
  return (
    <BrowserRouter useTransitions={false}>
      <AuthProvider>
        {/* The outer boundary covers pages without a layout (landing, public status). */}
        <PageBoundary>
          <Routes>
            <Route index element={<HomeRoute landing={<LandingPage />} />} />

            {/* Public: signed-in users are sent on to the app. */}
            <Route element={<RedirectIfAuthed />}>
              <Route element={<AuthLayout />}>
                <Route path="login" element={<LoginPage />} />
                <Route path="signup" element={<SignupPage />} />
                <Route path="forgot-password" element={<ForgotPasswordPage />} />
              </Route>
            </Route>

            {/* Reachable while signed in too: the emailed link often opens in a browser with a session. */}
            <Route element={<AuthLayout />}>
              <Route path="reset-password" element={<ResetPasswordPage />} />
              <Route path="verify-email" element={<EmailLinkPage key="verify" mode="verify" />} />
              <Route path="confirm-email" element={<EmailLinkPage key="change" mode="change" />} />
              <Route path="confirm-channel" element={<EmailLinkPage key="channel" mode="channel" />} />
            </Route>

            {/* Public status pages: no session, no app chrome. */}
            <Route path="status/:slug" element={<PublicStatusPage />} />

            {/* The docs: public, with the app around them when signed in. */}
            <Route element={<SignedInOrPublic app={<AppLayout />} visitor={<PublicDocsLayout />} />}>
              <Route path="docs" element={<DocsPage />} />
              <Route path="docs/:slug" element={<DocArticlePage />} />
            </Route>

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
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Route>
          </Routes>
        </PageBoundary>
      </AuthProvider>
    </BrowserRouter>
  )
}
