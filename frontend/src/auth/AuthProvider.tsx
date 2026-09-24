import { useEffect, useMemo, useState, type ReactNode } from 'react'
import * as authApi from '../api/auth'
import { getSession, onSessionChange, updateSessionUser } from '../api/session'
import type { LoginRequest, RegisterRequest, User } from '../types/auth'
import { AuthContext, type AuthState, type AuthStatus } from './authContext'

export function AuthProvider({ children }: { children: ReactNode }) {
  // A stored session still has to be confirmed with /me before we trust it.
  const [status, setStatus] = useState<AuthStatus>(() => (getSession() ? 'loading' : 'anonymous'))
  const [user, setUser] = useState<User | null>(() => getSession()?.user ?? null)

  useEffect(() => {
    // The session module is the source of truth: login, logout and any 401 all flow through here.
    const unsubscribe = onSessionChange((s) => {
      setUser(s?.user ?? null)
      setStatus(s ? 'authenticated' : 'anonymous')
    })

    if (getSession()) {
      authApi
        .me()
        .then(updateSessionUser)
        .catch(() => {
          // me() already cleared the session; the listener moves us to 'anonymous'.
        })
    }
    return unsubscribe
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      status,
      user,
      login: async (req: LoginRequest) => {
        await authApi.login(req)
      },
      register: async (req: RegisterRequest) => {
        await authApi.register(req)
      },
      logout: authApi.logout,
    }),
    [status, user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
