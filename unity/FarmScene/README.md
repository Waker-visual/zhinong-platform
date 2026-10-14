# 三维农场

Unity 6000.6.5f1，Built-in Render Pipeline，WebGL 2。生成入口：`Assets/Editor/FarmBuilder.cs`。在仓库根目录运行 `scripts/build-farm-scene.ps1`，再构建前端。

## 本机安装与构建

Windows 可通过官方 Unity CLI 安装指定 Editor 和 WebGL 模块。在 PowerShell 中运行：

```powershell
winget install --id Unity.CLI --exact
unity install 6000.6.5f1 --changeset 3ff58d469c8a -m webgl
```

安装 CLI 后重新打开终端，让 PATH 生效。已有同版本 Editor 时可用 `unity install-modules -e 6000.6.5f1 -m webgl` 补齐模块。版本和 changeset 来自 `ProjectSettings/ProjectVersion.txt`，不要以 `latest` 替代。命令依据 [Unity CLI 官方参考](https://docs.unity.com/en-us/unity-cli/unity-cli-reference)；不同 CLI 版本的可用选项以 `unity --help` 为准。

首次构建前运行 `unity auth login` 完成浏览器登录，并通过 `unity license status` 核对本机 Editor 许可；尚未激活时按 Unity CLI 或 Unity Hub 提示激活符合账号条件的许可证。构建脚本以非交互模式执行，不代替登录或许可激活。关闭打开了同一工程的 Editor，再从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-farm-scene.ps1
cd frontend
npm ci
npm run build
cd ../backend
mvn -B package
cd ..
```

脚本优先使用 Windows 官方独立版安装位置 `%LOCALAPPDATA%/Unity/bin/unity.exe`，然后从 PATH 查找 `unity`，也可使用 `-UnityCli '<本机 Unity CLI 可执行文件路径>'`；此参数指向 Unity CLI，不是普通 Editor 的 `Unity.exe`。如果 MSIX 版报告 `LICENSING_CLIENT_UNAVAILABLE`，且日志提示 `tar could not chdir`，可按 [官方安装指南](https://docs.unity.com/en-us/unity-cli/use-unity-cli) 安装独立版，重新登录并激活符合条件的许可证。构建先生成 `.cache/farm-scene/Build`，确认本次日志没有脚本/着色器错误且 `farm-scene.loader.js`、`.data`、`.framework.js`、`.wasm` 四个文件均存在且非空后，才复制到 `frontend/public/farm-scene/Build`。本次构建前会清理缓存中的这四个旧文件，避免失败后误复制旧产物；日志位于 `.cache/farm-scene-build.log`。

必须按“Unity → 前端 → 后端”的顺序打包，后端 JAR 才会包含新三维资源。随后使用已配置的启动脚本；例如云数据库已配置时运行 `scripts/start-cloud.ps1 -SkipBuild`，或已有 RDS 配置时运行 `scripts/start-rds.ps1 -SkipBuild`。仅在上述前后端打包成功后使用 `-SkipBuild`。浏览器验收通过服务的 HTTP 地址进行，不直接打开本地 HTML 文件。

干净仓库无需私有参考模型和卫星 JPG 也可构建：缺少 `ReferenceFarm` 时使用程序化模型，缺少 `SatelliteImagery/region.jpg` 时显示三维示意地面，缺少 `context.jpg` 时跳过外围影像。不会自动下载私有模型或生成伪卫星纹理；若需卫星对齐，按下文准备完整的本机影像缓存后重新构建。

`FarmScene.cs` 创建裁剪到登记边界的作物行、田埂、起伏远景、灌渠、围栏和温室。场景使用米制比例（1 单位 = 1 米），由登记经纬度换算，无实测高程，不用于测绘。环境建筑不对应经营资产登记。

本机预览还从用户提供的 CleverFarm 捕获包恢复树木、拖拉机、仓库、粮仓和气象站网格及材质，以便对照原站质量。来源：https://virtual.cleverfarm.ag/ ，捕获日期 2026-10-13。原模型版权归原权利人，原始捕获包未附授权文本；2026-10-14 项目维护者确认已取得模型及卫星纹理的公开分发授权。恢复数据、材质和构建产物不提交 Git，当前已编译开发包通过 Release 附件提供；来源和授权依据见 [WebGL资产来源说明](../../docs/WebGL资产来源说明.md)。`ReferenceImporter` 仅在本机存在 `private-materials/_reference-models/manifest.json` 时导入；缺少参考模型时使用程序化示意资产。

无需 Unity 的协作者可下载完整 WebGL Build，校验后解压到仓库，再正常构建前后端。下载与启动步骤见 [WebGL开发包使用指南](../../docs/WebGL开发包使用指南.md)。

恢复步骤：使用安装了 UnityPy 的 Python 环境运行 `scripts/extract-farm-reference.py --source '<解压后的 UnityWebData 路径>'`，再运行场景构建。模型生成在被忽略的 `Assets/Resources/ReferenceFarm` 中，来源记录随模型保存。程序不会上传捕获材料，也不复用参考网站的商标与业务文本。

网页仅通过同源消息传入已授权田块轮廓、田块 ID、设备 ID、类型、位置和有效采样状态。Unity 不持有会话、不请求业务接口、不在构建中保存农场数据。切换农场或退出视图会卸载承载页面。

`LoadFarm` 接收米制几何；`SelectPlot` 定位并高亮；`ResetView` 复位；`SetSeason` 改变示意配色；`Zoom` 调整近景距离。左键旋转、右键平移，浏览器菜单在场景内部关闭；缩放按钮也可通过键盘操作。点选回调只返回田块 ID，网页核对 ID 属于当前农场后显示资料。模型仅响应用户操作，无自动摄像机巡航。

浏览器验收需核对实际绘制、控制响应、跨农场清空、加载失败回退、窄屏和退出资源释放。生成目录与 Unity 缓存不入 Git。静态载入页需允许同源内嵌，其余应用页面保持禁止内嵌。

地图对齐：`LoadFarm` 同时接收小田块和分区管线；`SelectParcel` 按平面图小田块定位，`SelectDevice` 按已登记设备定位。`UpdateDevices` 更新位置、状态及分区供水动画而不重置镜头。作物固定随机种子来自地块 ID，全局行列索引避免细节块边缘接缝。

## 固定卫星影像建模

使用本机已缓存的地图瓦片时，先运行 `python scripts/prepare-scene-imagery.py`（需要 Pillow），再构建 Unity。地表、道路和林带依照 `Assets/Resources/SatelliteContext.json` 的公开影像描绘坐标。纹理来源记录在被忽略的 `SatelliteImagery/SOURCE.txt`；不可把缺失影像区域伪装成实景。缓存纹理不提交 Git，界面保留 Esri 及影像提供方署名。

影像整理会跳过 Esri 灰色“Map data not yet available”占位瓦片，保留已缓存的较低层级真实影像；没有完整真实底图覆盖时仍中止构建准备，避免占位图形成白色地块。

映射通过当前已授权田块的 WGS84 原点计算，不写死租户或农场 ID。只在整个当前农场边界落于影像范围时启用；树高、屋高及遮挡部分仍为推定，非测绘成果。构建脚本会阻断着色器错误，避免 Unity 返回成功但运行时使用空白回退材质。

本机图片验收使用 Unity CLI 的 `com.unity.pipeline` 开发工具连接 Editor。进入 Play 模式前开启自动 tick，验收时同时记录推进帧数，避免把停帧截图当成运行证明。截图和带演示记录的临时验收输入放在被忽略的 `.cache` 中，不进入构建资源。

## 日间风格与演示交互

所有农场共享明亮日间光照、柔和阴影和清晰的苗期/生长期/成熟期配色；卫星地表适度提亮，保留原始影像来源标识。镜头移动时，田块标识按渲染帧回传，静止且投影不变时不重复传送。可见田块编号不再受总数上限截断，拥挤标记使用短连线指向原位置，完整名称仍可在田块导览中查看。

同点设备根据设备外形占地进行稳定的展示避让，用细线连接登记位置。避让只改变模型显示，不写回设备定位，也不改变灌溉管线。`SelectDevice` 传空字符串仅清除选择，关闭详情不会重新显示重叠模型。构建入口会先执行 `FarmDeviceChecks.Validate()` 检查间距、刷新顺序、选择关闭及状态隔离。

“演示动画”控制虫情灯、气象风杯、摄像机、传感器和农机的示意运动；系统偏好减少动态效果时默认关闭。`SetDeviceDemo` 接收 `on`/`off`。动画不代表远程设备正在执行指令，真实运行/供水反馈仍以有效采样和任务状态为准。

“舒缓音乐”位于公共大屏页头，由网页本地合成柔和钟琴和弦，没有外部音乐文件或下载。默认关闭，点击后播放，旁边的音量百分比可展开调节；切换农场或三维农场、农场总览、智能农情、农场导览视图时保持播放。切离浏览器标签页会暂停，返回后需手动继续，退出整个大屏会释放音频资源。

三维场景左下为作物、缩放、复位、动画与卫星入口工具栏，右下以紧凑数据摘要展示耕地、设备和农事，窄屏自动换行。底部仅保留旋转、平移、缩放和点选提示；各大屏视图原有页脚说明统一收进顶部“来源说明”图标，保留影像及模型署名。

本地演示启动：完成打包后运行 `scripts/start-demo.ps1 -SkipBuild`。默认不让 Java 使用系统代理，避免全局代理干扰本机模拟 MySQL；确需 Java 跟随系统代理时可显式添加 `-UseSystemProxy`，并确保代理放行本机数据库连接。
