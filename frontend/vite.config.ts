import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Ready for when the mock layer is swapped for the Spring Boot API.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
