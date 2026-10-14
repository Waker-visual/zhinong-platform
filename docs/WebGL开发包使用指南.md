# 无需 Unity 的三维农场开发包

下载 [2026-10-14 WebGL 开发包](https://github.com/Waker-visual/zhinong-platform/releases/tag/farm-scene-webgl-20261014) 中的 `zhinong-farm-scene-webgl.zip` 和同名 `.sha256` 文件。该包提供已编译的三维场景，仍需本项目源码及 Node.js 22、JDK 21、Maven；无需安装 Unity 或重新提取模型。

先检出包含卫星三维场景的主分支（`ee79a96` 或其后兼容版本），在仓库根目录执行以下 PowerShell 命令，将下载路径改为实际位置：

```powershell
$archive = 'C:\Downloads\zhinong-farm-scene-webgl.zip'
$expected = ((Get-Content -LiteralPath ($archive + '.sha256') -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw '下载包校验失败' }
Expand-Archive -LiteralPath $archive -DestinationPath . -Force
$manifest = Get-Content farm-scene-manifest.json -Raw | ConvertFrom-Json
foreach ($file in $manifest.files) {
    if ((Get-FileHash -LiteralPath $file.path -Algorithm SHA256).Hash -ne $file.sha256) { throw "文件校验失败：$($file.path)" }
}
./scripts/start-demo.ps1
```

解压会覆盖同名场景构建、加载页及本包的两份说明；本地修改过这些文件时先备份。默认演示启动脚本会安装前端依赖、构建前后端并启动服务；首次运行生成本机随机口令和虚构演示数据库，不使用打包人的数据库或账号。已有本地数据库和配置按原启动脚本处理。若 9175 端口被占用，应先停止旧实例。

打开 `http://127.0.0.1:9175/`，用终端提示的演示口令登录（租户 `demo-a`、用户名 `admin`），进入展示大屏的“三维农场”。浏览器需要支持 WebGL 2。不要直接双击场景 HTML，场景通过主应用同源加载，田块和设备由当前登录账号的接口传入。

包内 `frontend/public/farm-scene/Build/` 包含以下四个相互配套的文件，更新时应整体替换：

- `farm-scene.loader.js`：加载器。
- `farm-scene.framework.js`：运行时桥接代码。
- `farm-scene.wasm`：编译后的程序。
- `farm-scene.data`：场景模型、材质和纹理。

同时包含应用专用 `index.html`、来源说明及逐文件 SHA256 清单。无需额外的 TemplateData 或 StreamingAssets。这里是 WebGL 场景开发依赖包，并非包含 Java/Node 的免安装整站软件；依赖安装、在线地图及外部服务仍可能需要联网。

维护者更新包时，先完成 Unity 构建与浏览器验收，再运行 `./scripts/package-farm-scene.ps1 -OutputDirectory '.cache/webgl-release-new'`。脚本仅收集明确列出的运行文件和说明，不递归收集工作区、数据库、会话、密钥、参考捕获包或 Unity 缓存。上传 ZIP 和校验文件到对应 Release，并在 PR 中记录下载地址；二进制继续排除在 Git 历史之外。
