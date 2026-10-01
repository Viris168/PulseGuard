import { createElement, lazy, useState, type ComponentType } from 'react'

/**
 * Every routed page, loaded on demand so a first visit downloads only what it shows. Each
 * can also be fetched ahead of time (prefetchWhenIdle), so by the time someone clicks, the
 * page is already here: no gap where the previous page stays on screen and takes input.
 */
function page<P extends object>(load: () => Promise<ComponentType<P>>) {
  let loaded: ComponentType<P> | undefined
  let loading: Promise<ComponentType<P>> | undefined
  const fetchOnce = () =>
    (loading ??= load().then((component) => {
      loaded = component
      return component
    }))
  const Lazy = lazy(() => fetchOnce().then((component) => ({ default: component })))
  /**
   * Once the code is here (prefetched or visited before), render it directly: going through
   * lazy() would still suspend for a moment and flash the loading spinner. The choice is fixed
   * per mount, so a page never remounts (and loses its state) when its code arrives.
   */
  function Page(props: P) {
    const [Component] = useState<ComponentType<P>>(() => loaded ?? (Lazy as unknown as ComponentType<P>))
    return createElement(Component, props)
  }
  return Object.assign(Page, { preload: fetchOnce })
}

export const LandingPage = page(() => import('../pages/public/LandingPage').then((m) => m.LandingPage))
export const PublicStatusPage = page(() => import('../pages/public/PublicStatusPage').then((m) => m.PublicStatusPage))
export const LoginPage = page(() => import('../pages/auth/LoginPage').then((m) => m.LoginPage))
export const SignupPage = page(() => import('../pages/auth/SignupPage').then((m) => m.SignupPage))
export const ForgotPasswordPage = page(() => import('../pages/auth/ForgotPasswordPage').then((m) => m.ForgotPasswordPage))
export const ResetPasswordPage = page(() => import('../pages/auth/ResetPasswordPage').then((m) => m.ResetPasswordPage))
export const EmailLinkPage = page(() => import('../pages/auth/EmailLinkPage').then((m) => m.EmailLinkPage))
// The dashboard shell too: visitors to the landing or a status page never need it.
export const AppLayout = page(() => import('../components/layout/AppLayout').then((m) => m.AppLayout))
export const MonitorsPage = page(() => import('../pages/MonitorsPage').then((m) => m.MonitorsPage))
export const MonitorFormPage = page(() => import('../pages/MonitorFormPage').then((m) => m.MonitorFormPage))
export const MonitorDetailPage = page(() => import('../pages/MonitorDetailPage').then((m) => m.MonitorDetailPage))
export const IncidentsPage = page(() => import('../pages/IncidentsPage').then((m) => m.IncidentsPage))
export const IncidentDetailPage = page(() => import('../pages/IncidentDetailPage').then((m) => m.IncidentDetailPage))
export const StatusPageEditor = page(() => import('../pages/StatusPageEditor').then((m) => m.StatusPageEditor))
export const BillingPage = page(() => import('../pages/BillingPage').then((m) => m.BillingPage))
export const SettingsPage = page(() => import('../pages/settings/SettingsPage').then((m) => m.SettingsPage))
export const PublicDocsLayout = page(() => import('../components/layout/PublicDocsLayout').then((m) => m.PublicDocsLayout))
export const DocsPage = page(() => import('../pages/docs/DocsPage').then((m) => m.DocsPage))
export const DocArticlePage = page(() => import('../pages/docs/DocArticlePage').then((m) => m.DocArticlePage))
export const NotFoundPage = page(() => import('../pages/NotFoundPage').then((m) => m.NotFoundPage))

/** Where someone can go next from each area; fetched once the browser has nothing better to do. */
const NEXT = {
  landing: [LoginPage, SignupPage],
  auth: [LoginPage, SignupPage, ForgotPasswordPage, ResetPasswordPage, EmailLinkPage, AppLayout, MonitorsPage],
  dashboard: [MonitorsPage, MonitorFormPage, MonitorDetailPage, IncidentsPage, IncidentDetailPage, StatusPageEditor, BillingPage, SettingsPage, DocsPage, DocArticlePage, NotFoundPage],
} as const

export type Area = keyof typeof NEXT

/**
 * Fetches the pages reachable from {@code area} in idle time. Idle, so it never competes with
 * what the user is looking at; failures are ignored, since the page still loads on click.
 */
export function prefetchWhenIdle(area: Area): () => void {
  const run = () => NEXT[area].forEach((p) => void p.preload().catch(() => undefined))
  if ('requestIdleCallback' in window) {
    const id = window.requestIdleCallback(run, { timeout: 3000 })
    return () => window.cancelIdleCallback(id)
  }
  const id = setTimeout(run, 1500) // Safari has no requestIdleCallback
  return () => clearTimeout(id)
}
