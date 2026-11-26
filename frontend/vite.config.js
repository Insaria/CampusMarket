import vue from '@vitejs/plugin-vue'
import { realpathSync } from 'node:fs'
import { join } from 'node:path'
import { defineConfig, searchForWorkspaceRoot } from 'vite'

export default defineConfig(() => {
  const routerDirectory = process.env.CAMPUS_MARKET_VUE_ROUTER_DIR
  const localRouter = routerDirectory ? realpathSync(routerDirectory) : null

  return {
    plugins: [vue()],
    resolve: {
      dedupe: ['vue'],
      alias: localRouter ? { 'vue-router': join(localRouter, 'dist/vue-router.mjs') } : {},
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: { '/api': 'http://127.0.0.1:8080' },
      fs: localRouter ? { allow: [searchForWorkspaceRoot(process.cwd()), localRouter] } : undefined,
    },
  }
})
