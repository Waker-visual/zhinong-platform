import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      input: { desktop: "index.html", mobile: "mobile/index.html" },
      output: {
        // 独立依赖单独缓存，业务更新无需重新下载地图、图表和框架运行时。
        // 只分配命中的模块，避免把业务代码或共享依赖隐式吸入某个 vendor 块。
        onlyExplicitManualChunks: true,
        manualChunks(id) {
          const path = id.replaceAll("\\", "/");
          if (!path.includes("/node_modules/")) return;
          if (/\/node_modules\/(?:vue|@vue\/[^/]+)\//.test(path)) return "vue";
          if (path.includes("/node_modules/leaflet/")) return "maps";
          if (path.includes("/node_modules/echarts/")) return "charts";
          if (path.includes("/node_modules/zrender/")) return "chart-renderer";
          // 3D 页签才触发加载；核心对象与 WebGL 渲染器分开缓存。
          if (path.endsWith("/three/build/three.core.js")) return "three-core";
          if (path.endsWith("/three/build/three.module.js"))
            return "three-webgl";
        },
      },
    },
  },
  // FARM_API 可指向另一个本机后端，例如验收用的预览实例
  server: {
    proxy: { "/api": process.env.FARM_API || "http://127.0.0.1:9175" },
  },
});
