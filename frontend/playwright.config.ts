import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests against the real running stack. They do not start it: run
 * `docker compose up -d`, the backend and `npm run dev` first (see e2e/README.md).
 * Emails are read from Mailpit, which the dev backend sends to.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  // One account flows through the file in order (sign up … delete), so run serially.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
