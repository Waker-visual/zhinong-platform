# 三维农场

Unity 6000.6.5f1，Built-in Render Pipeline，WebGL 2。生成入口：`Assets/Editor/FarmBuilder.cs`。在仓库根目录运行 `scripts/build-farm-scene.ps1`，再构建前端。

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

映射通过当前已授权田块的 WGS84 原点计算，不写死租户或农场 ID。只在整个当前农场边界落于影像范围时启用；树高、屋高及遮挡部分仍为推定，非测绘成果。构建脚本会阻断着色器错误，避免 Unity 返回成功但运行时使用空白回退材质。

本机图片验收使用 Unity CLI 的 `com.unity.pipeline` 开发工具连接 Editor。进入 Play 模式前开启自动 tick，验收时同时记录推进帧数，避免把停帧截图当成运行证明。截图和带演示记录的临时验收输入放在被忽略的 `.cache` 中，不进入构建资源。`SelectDevice` 传空字符串恢复同点设备，`SelectPlot` 和 `ResetView` 也会恢复。
