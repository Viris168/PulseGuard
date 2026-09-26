// Mirrors com.viris.PulseGuard.enumeration.Plan / Role
export type Plan = 'FREE' | 'PRO' | 'BUSINESS'
export type Role = 'USER' | 'ADMIN'

// Mirrors auth/dto/UserResponse
export interface User {
  id: number
  name: string
  email: string
  plan: Plan
  role: Role
  createdAt: string
}

// Mirrors auth/dto/AuthResponse
export interface AuthResponse {
  token: string
  refreshToken: string
  tokenType: 'Bearer'
  /** Access token lifetime in seconds. */
  expiresIn: number
  user: User
}

// Mirrors auth/dto/LoginRequest and RegisterRequest
export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  name: string
  email: string
  password: string
}

export const PLAN_LABEL: Record<Plan, string> = { FREE: 'Free', PRO: 'Pro', BUSINESS: 'Business' }

// Mirrors auth/dto/ChangePasswordRequest
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}
