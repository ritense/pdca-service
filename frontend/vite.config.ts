import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const bundles = [
  'plan-overview',
  'plan-goals',
  'plan-evaluations',
  'pdca-admin',
  'evaluation-panel',
  'create-plan',
];

const root = fileURLToPath(new URL('.', import.meta.url));
const outDir = resolve(root, '../src/main/resources/static/bundles/react');

export default defineConfig({
  plugins: [
    react(),
    {
      name: 'flatten-html',
      closeBundle() {
        for (const name of bundles) {
          const src = resolve(outDir, `src/${name}/index.html`);
          const dest = resolve(outDir, `${name}.html`);
          if (existsSync(src)) {
            let html = readFileSync(src, 'utf-8');
            html = html.replace(/\.\.\/\.\.\//g, './');
            writeFileSync(dest, html);
          }
        }
      },
    },
  ],
  base: './',
  build: {
    outDir,
    emptyOutDir: true,
    rollupOptions: {
      input: Object.fromEntries(
        bundles.map(name => [name, resolve(root, `src/${name}/index.html`)])
      ),
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern',
        quietDeps: true,
      },
    },
  },
});
