# 智禾农场综合宣传片（约 150 秒）

两个主角：**小禾**（AI 助手）与 **3D 农场**。Remotion 4 工程，1920×1080、60 帧/秒，全片一条以秒为单位的时间轴，段落切点都落在配乐的小节线上（`src/kit.tsx` 的 `S`）。

## 结构

| 段落 | 时间 | 画面来源 |
| --- | --- | --- |
| 序 | 0–10 s | 登录页像素田园与四季，应用实录 |
| 01 小禾 | 10–72 s | 按产品界面重绘；问答内容取自 2026-10-19 的一次真实问答记录（`src/content/xiaohe.json`） |
| 02 3D 农场 | 72–124 s | 产品三维场景逐帧实录，标注按每帧单应矩阵贴合；末尾为展示大屏页面实录 |
| 03 全貌与落版 | 124–150 s | 手机端截图与重绘图形 |

## 重新生成素材

素材（视频、截图、音频）不进 Git，按下面步骤重建。演示环境只用本工作区自己的后端与数据库副本（端口 9276/4374），口令从 `.cache/demo-password.txt` 读取，脚本不输出口令。

```
node scripts/assets.mjs          # 复用旧片的配乐、问答记录
python -I audio/music.py         # 配乐剪到小节线，生成 src/beats.json
node capture/farm.mjs            # 三维农场 8 个镜头（PREVIEW=1 只拍首/中/尾三帧）
node capture/screens.mjs login   # 登录页一日光景；另有 seasons、display 两组
node capture/mobile.mjs          # 手机端首页
FFMPEG=<ffmpeg> node scripts/encode.mjs farm
FFMPEG=<ffmpeg> node scripts/encode.mjs rec
FFMPEG=<ffmpeg> node scripts/stills.mjs 12 82 101   # 抽帧检查
```

三维场景的 WebGL 构建来自 `farm-scene-webgl-20261116-2` 开发包（见 `docs/WebGL开发包使用指南.md`）。

## 实录方式与如实标注

- 镜头运动全部通过产品自带的操作完成（左键环绕、右键平移、缩放档位、定位设备），虚拟时钟逐帧推进。
- 水泵运行、闸门开度、分区出水这些**设备状态由采集脚本经产品的状态通道设定**，不是后端执行了一次模拟灌溉；片中常驻“演示数据 · 模拟设备”，联动段另标“设备联动演示｜模拟运行”，管路水流与支管阀门标注为示意。
- 片中农场、设备、经营数据均为虚构演示数据；不出现效益百分比。

## 旁白

脚本在 `script/narration.json`。音色沿用 MiniMax speech-2.8-hd 的 keynoteB；带 `cache` 的句子复用旧片已合成音频，其余新句须在脚本审定后才合成。

## 署名

配乐《Documentary Nature》— alex-morgan（Pixabay Content License）。AI 回答由 deepseek-flash 于 2026-10-19 生成。
