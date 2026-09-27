/// <reference types="vite/client" />

// Build-time variables from .env.local (see .env.example). Publishable values only.
interface ImportMetaEnv {
  /** Stripe publishable key, pk_test_... or pk_live_...; empty until Stripe.js is used. */
  readonly VITE_STRIPE_PUBLISHABLE_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
