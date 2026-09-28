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
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
  server: {
    // Ready for when the mock layer is swapped for the Spring Boot API.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
