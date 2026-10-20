# 智禾农场 · 灌溉任务设备联动演示（80 秒）

答辩 PPT 用展示视频：发现田块 → 灌溉建议 → 人工确认 → 指令下发 → 设备模拟运行 → 状态反馈 → 运行结束，
配套田间协作，最后回到农场全景。1920×1080，60fps，H.264 + AAC，−16 LUFS。

## 画面来源

| 段落 | 来源 |
|---|---|
| 三维农场、泵房、水塘 | 产品自带 Three.js 场景 `frontend/src/screen/scene/scene-world.js` 的分支 `src/three/farm-world.js`（去掉交互，按帧同步渲染）|
| 管线、阀门、出水、指示灯、水流 | `src/three/irrigation.js`，**模拟示意**，与产品“水塘、泵房与水流为场景演示”的说明一致 |
| 系统界面 | 真实截图（`capture/out/shots`，3200 px 宽，2× 密度）：灌溉管理长页、确认弹窗、运行中卡片、手机上报/回执、桌面安排/复核 |
| 数据 | C-01、37.7%、40%、120 秒、演示泵房控制器，均取自 2026-10-19 采集的接口与截图 |

阀门只作管路工作原理示意，片中标注“示意”，不表述为已实现的独立远程控制。运行结束画面只确认模拟运行结束，不展示未经验证的墒情结果。

## 声音

- 配乐：Documentary Nature — alex-morgan（Pixabay Content License），`audio/mix.py` 在小节线处剪接（0–63.0 s 接尾声 152.53 s 起）。
- 旁白：MiniMax speech-2.8-hd，keynoteB 原创音色（与主片一致），`audio/tts.py` 合成四句。
- 音效：`audio/mix.py` 程序化合成（点击、确认、指令抵达、水泵启停、流水、阀门、喷洒）。

## 重新生成

```bash
npm install
python audio/tts.py          # 需要 promo-video/music/voice.env 中的 MINIMAX_API_KEY
python -I audio/mix.py       # 生成 public/audio/mix.wav
npx remotion render TaskFilm out/task-film.mp4 --gl=angle --codec=h264 --crf=16 --audio-codec=aac --audio-bitrate=320k
node scripts/stills.mjs 5 30 55   # 抽帧审查（设置 FFMPEG 环境变量可拼联系表）
```

`public/shots`、`public/audio` 与渲染产物不入库。准备素材时从 `promo-video/capture/out/shots` 复制：

| public/shots | 来源 |
|---|---|
| page-proposed.png | irr_approve/04-proposed-full.png |
| confirm-dialog.png | irr_approve/06-confirm-dialog.png |
| running.png | irr_approve/08-running-2.png |
| m-report.png / m-receipt.png | phone_report/04-form-filled.png / phone_receipt/03-receipt-filled.png |
| d-assign.png / d-review.png | desk_assign/04-plan-filled.png / desk_review/03-review-filled.png |

另需 `public/brand/zhihe-logo.svg`（film/public/brand）与 `public/audio/music-src.mp3`（film/public/music/documentary-nature.mp3）。
