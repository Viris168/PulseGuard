/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    // A simulated browser, so components render in Node. CSS is irrelevant to behaviour.
    environment: 'jsdom',
    // Only the unit and component tests; e2e/ is Playwright's, against the running stack.
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
  server: {
    // Let `cloudflared tunnel --url http://localhost:5173` expose the dev server publicly.
    allowedHosts: ['.trycloudflare.com'],
    // The Spring Boot API in development; production serves both from one origin.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
