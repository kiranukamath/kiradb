import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Dev convenience: /api/* is proxied to the KiraDB HTTP port so the dashboard
// works without CORS even if the API origin changes. In production builds the
// app calls the API origin directly (see src/api.ts).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': {
        target: process.env.KIRADB_API ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
