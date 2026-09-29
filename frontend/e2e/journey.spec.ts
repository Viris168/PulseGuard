import { expect, test, type Page } from '@playwright/test'
import { linkFromEmail } from './mailpit'

/**
 * One account's life against the real stack: sign up and verify, monitors of both kinds, a
 * public status page, a forgotten password, and deletion (which also cleans up after the run).
 */
const email = `e2e-${Date.now()}@example.test`
const password = 'E2e-Passw0rd!'
const newPassword = 'E2e-Changed-Passw0rd!'
const statusSlug = `e2e-${Date.now().toString(36)}`

test.describe.configure({ mode: 'serial' })

let page: Page

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage()
})

test.afterAll(async () => {
  await page.close()
})

async function signIn(pwd: string) {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(pwd)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/monitors$/)
}

test('sign up, then verify the email from the link', async () => {
  await page.goto('/signup')
  await page.getByLabel('Name', { exact: true }).fill('E2E Tester')
  await page.getByLabel('Work email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account' }).click()

  await expect(page).toHaveURL(/\/monitors$/)
  await expect(page.getByText(/to get alert emails/)).toBeVisible()

  const link = await linkFromEmail(page.request, email, 'Verify your email for PulseGuard')
  await page.goto(link)
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible()

  await page.goto('/monitors')
  await expect(page.getByText(/to get alert emails/)).toHaveCount(0)
})

test('add an HTTP monitor with several status codes and a secret header', async () => {
  await page.goto('/monitors/new')
  await page.getByLabel('Name', { exact: true }).fill('Example site')
  await page.getByLabel('URL', { exact: true }).fill('https://example.com/')
  await page.getByLabel('Expected status', { exact: true }).fill('200, 204')
  await page.getByRole('button', { name: /Add header/ }).click()
  await page.getByLabel('Header 1 name', { exact: true }).fill('Authorization')
  await page.getByLabel('Header 1 value', { exact: true }).fill('Bearer e2e-secret')
  await page.getByRole('button', { name: 'Create monitor' }).click()

  await expect(page).toHaveURL(/\/monitors$/)
  await page.getByRole('link', { name: /Example site/ }).click()
  await expect(page.getByText(/Expects\s+200 or 204/)).toBeVisible()

  // The secret never comes back: editing shows the header, not its value.
  await page.getByRole('link', { name: /Edit/ }).click()
  await expect(page.getByLabel('Header 1 value', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('Header 1 value', { exact: true })).toHaveAttribute('placeholder', 'Unchanged')
})

test('a heartbeat receives a real ping', async () => {
  await page.goto('/monitors/new')
  await page.getByRole('radio', { name: /Heartbeat/ }).click()
  await page.getByLabel('Name', { exact: true }).fill('E2E cron')
  await page.getByRole('button', { name: 'Create heartbeat' }).click()

  await expect(page.getByRole('heading', { name: 'Ping URL' })).toBeVisible()
  const pingUrl = (await page.getByText(/\/api\/ping\/[A-Za-z0-9]{24}/).first().innerText()).match(/https?:\/\/\S+\/api\/ping\/[A-Za-z0-9]{24}/)![0]

  const res = await page.request.get(pingUrl)
  expect(res.status()).toBe(200)
  expect(await res.text()).toBe('OK\n')

  await page.reload()
  await expect(page.getByText('Last ping').first()).toBeVisible()
  await expect(page.getByRole('table').getByText(/just now|ago/).first()).toBeVisible()
})

test('publish a status page and view it logged out', async ({ browser }) => {
  await page.goto('/status-page')
  await page.getByRole('button', { name: 'Create status page' }).click()
  await page.getByLabel('Address', { exact: true }).fill(statusSlug)
  await page.getByRole('switch', { name: 'Publish status page' }).click()
  await page.getByRole('button', { name: 'Create page' }).click()
  await expect(page.getByText('Unsaved changes')).toHaveCount(0)

  const visitor = await browser.newPage() // a separate context: no session at all
  await visitor.goto(`/status/${statusSlug}`)
  await expect(visitor.getByRole('heading', { name: /Status/ })).toBeVisible()
  await expect(visitor.getByText('Example site')).toBeVisible()
  await visitor.close()
})

test('an unknown page says so', async () => {
  await page.goto('/no-such-page')
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible()
})

test('forgot password: reset from the emailed link, then sign in with the new one', async () => {
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('menuitem', { name: /Sign out/ }).click()
  await expect(page).toHaveURL(/\/login$/)

  await page.getByRole('link', { name: 'Forgot password?' }).click()
  // Pages load on demand: wait until this one is showing, or the fill lands on the login form.
  await expect(page.getByRole('heading', { name: 'Reset your password' })).toBeVisible()
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByRole('button', { name: 'Send reset link' }).click()
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible()

  const link = await linkFromEmail(page.request, email, 'Reset your PulseGuard password')
  await page.goto(link)
  await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible()
  await page.getByLabel('New password', { exact: true }).fill(newPassword)
  await page.getByRole('button', { name: 'Set new password' }).click()
  await expect(page.getByText('Password changed. Sign in with your new password.')).toBeVisible()

  await signIn(newPassword)
})

test('delete the account', async () => {
  await page.goto('/settings')
  await page.getByRole('button', { name: 'Security' }).click()
  await page.getByRole('button', { name: /Delete account/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Current password', { exact: true }).fill(newPassword)
  await dialog.getByRole('button', { name: 'Delete everything' }).click()

  await expect(page).not.toHaveURL(/\/settings/)
  const res = await page.request.post('/api/auth/login', { data: { email, password: newPassword } })
  expect(res.status()).toBe(401)
})
