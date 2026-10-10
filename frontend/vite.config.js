import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      input: { desktop: "index.html", mobile: "mobile/index.html" },
    },
  },
  // FARM_API 可指向另一个本机后端，例如验收用的预览实例
  server: {
    proxy: { "/api": process.env.FARM_API || "http://127.0.0.1:9175" },
  },
});
