import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'chrome116',
    modulePreload: false,
    rollupOptions: {
      input: {
        popup: 'popup.html',
        sidepanel: 'sidepanel.html',
        app: 'app.html',
        background: 'src/background.ts',
      },
      output: {
        entryFileNames: (c: { name: string }) => (c.name === 'background' ? 'background.js' : 'assets/[name]-[hash].js'),
      },
    },
  },
  test: {
    environment: 'node',
    testTimeout: 60000,
    // vectors.gen rewrites shared/ fixtures; run it explicitly with `npm run vectors`.
    exclude: process.env.VECTORS ? ['node_modules/**'] : ['node_modules/**', 'test/vectors.gen.test.ts'],
  },
} as any);
