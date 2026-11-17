import path from "node:path";
import { Config } from "@remotion/cli/config";
Config.setVideoImageFormat("jpeg");
Config.setJpegQuality(95);
Config.setOverwriteOutput(true);
Config.setChromiumOpenGlRenderer("angle");
// 三维农场渲染代码直接引用 ../task-film/src（不复制）；其依赖统一从本工程的 node_modules 解析。
Config.overrideWebpackConfig((c) => ({
  ...c,
  resolve: {
    ...c.resolve,
    alias: { ...(c.resolve?.alias ?? {}), "@task": path.resolve(process.cwd(), "../task-film/src") },
    modules: [path.resolve(process.cwd(), "node_modules"), "node_modules"],
  },
}));
