# smart-farm 现场设备接入与验收

本轮重点是把现场报文、地块监测和处理流程连接起来。核心业务支持 H2 与 MySQL 8，RDS 直连及连续模拟见 [RDS 接入与模拟数据验收](RDS接入与模拟数据验收.md)。云端已按明确选择的未加密连接完成入库验证。本轮验证使用虚构报文，没有连接现有现场 Broker、修改旧采集器或下发实体设备指令。

## 农场使用入口

1. 在自己的租户下建立农场和地块。在“设备台账”新增设备，选择对应类型和 HTTP 数据上报，应用指标模板，关联所属地块。
2. 设备详情中生成接入凭据。在“现场设备接入 → 配置协议映射”中填写外部设备编号、协议和字段映射。MQTT 上行主题是**设备发布、平台订阅**的主题；下行主题是设备订阅、平台发布的主题。仅监测时可不填下行主题。
3. 在“农场概览 → 地块现场监测”按地块查看读数。演示农场默认展示模拟设备；其他农场默认隐藏模拟设备，可手动切换。模拟来源始终标注。三层墒情分别显示，不把不同深度混成平均数。公共区域设备单独展示。
4. 设备详情显示新鲜指标数、从未上报的指标数和历史曲线，并每 15 秒刷新。“最近接收记录”显示协议报文的有效读数、缺测项、采样时间和映射版本，可手动刷新。
5. 对关联地块的未关闭告警，点击“转为田间问题”并填写现场说明。相同告警只创建一个问题；进入“今日农场”派工、填写作业回执、由农场主复核。传感器读数恢复会关闭阈值告警，**不会自动关闭已经创建的田间问题**。

历史补报仍进入趋势，但过期读数不会触发或解除当前阈值告警。缺测、`null`、空串和 `--` 不转成零；非法数值、重复字段及越界值拒绝整批。上报内容以设备凭据确定租户，不能通过请求指定租户。

## 与原项目对应的协议

依据原项目的 `PumpPoint`、`PumpCommandBuilder`、闸门 `CommandBuilder` / `UpMessage`、`LanRealtimeDataRepository` 和采集器节点结构实现。

| 协议 | 输入 | 映射原则 |
|---|---|---|
| `PUMP_MQTT` | `params.r_data` 或 `rw_prot.r_data` 的 name/value 数组 | `mainPumpRunning` 是主泵运行反馈；`mainPumpManual` 是控制设置，不能当反馈 |
| `GATE_MQTT` | 同上 | `opening` 是实际开度；`setOpening` 是目标，不能代替实际开度 |
| `LAN_DTU` | `data` 节点数组 | 通过节点编号和数值字段映射，如 `1.temValue`；保留每个节点的 `timeStamp` |

泵房模板映射运行反馈、备用泵反馈、主泵设定频率、水位、电压、电流、远程标志、急停和远程参数设置许可。不会推测原协议没有证实的瞬时流量、累计用电、累计用水或通用故障字段。未接入这些指标时，可在建档时只保留现场实际具备的指标；已有历史的指标不能删除。

土壤模板提供三层温度、水分、电导率和 pH。默认节点示例沿用旧项目修复后的土壤结构：1/3/5 为三层温湿度，2/4/6 为电导率，7 为 pH。**不同设备节点安排可能不同，必须按其节点元数据核对**。气象和水情没有强套用土壤编号，需要逐项配置。

平台统一单位：温度 ℃、水分 %、水位 cm、气压 hPa、电导率 µS/cm、流量 m³/h。支持显式换算 m/mm 水位、kPa 气压、mS/cm 电导率、L/min 流量以及 `%RH`、`μS/cm` 等协议写法。不会通过数值大小猜单位。土壤层号不代表已知厘米深度；可在设备安装说明中记录探头实际深度。

## HTTP 协议适配接口

配置：`GET /api/assets/{id}/integration`，管理员以 `PUT` 保存。返回 `adapters` 中包含适用模板，`config.revision` 用于防止覆盖其他人的修改。修改配置会使未完成指令失效。

采集：`POST /api/ingest/smart-farm`，请求头 `X-Device-Key`。不需要成员登录令牌。例子中的编号与主题是虚构占位值，需与设备接入配置一致：

```json
{
  "messageId": "collector-message-001",
  "measuredAt": "2026-10-06T08:00:00Z",
  "externalId": "example-pump",
  "topic": "/example/pump/up",
  "payload": {
    "params": {
      "dir": "up",
      "r_data": [
        {"name": "mainPumpRunning", "value": "0"},
        {"name": "remoteFlag", "value": "1"},
        {"name": "emergencyStop", "value": "0"},
        {"name": "remoteParamSet", "value": "1"},
        {"name": "mainPumpFreqSet", "value": "35"}
      ]
    }
  }
}
```

`measuredAt` 是实际采样时间，不要把历史数据补报的时间改成现在。相同消息编号和相同内容可重试；内容不同返回 409。外部编号或主题不符返回 403。空批或无已映射有效读数返回 400，不会更新在线状态。

局域网请求的 `topic` 使用空字符串，payload 形如：

```json
{"deviceAddr":"example-soil","data":[
  {"nodeId":1,"temValue":22.3,"humValue":35.6,"timeStamp":1791273600000},
  {"nodeId":3,"temValue":21.7,"humValue":38.2,"timeStamp":1791273540000}
]}
```

`timeStamp` 为毫秒时间戳，存在时覆盖该节点的外层采样时间。也支持直接整理采集表字段 `tem` / `hum` / `float_value` / `signed_val` / `unsigned_val`，例如把字段映射设为 `2.float_value`。现有 DTU 原始二进制协议仍由原采集器及厂家 SDK 解析；本平台不抢占旧采集端口，也不自动访问旧项目数据库。需由现场转发程序把采集结果整理成上述格式。

## 可选 MQTT 网关

后端内置网关默认不启动，支持多个 Broker。每个 Broker 使用独立、厂家允许且未被旧服务占用的客户端 ID；上行主题精确匹配，不支持通配符。配置保存到被 Git 忽略的 `.cache/mqtt-gateway.private.json`。以下内容只是格式示例：

```json
{"brokers":[{
  "url":"ssl://broker.example:8883",
  "clientId":"example-new-platform-client",
  "usernameEnv":"FARM_MQTT_USER",
  "passwordEnv":"FARM_MQTT_PASSWORD",
  "timeZone":"Asia/Shanghai",
  "devices":[{
    "externalId":"example-pump",
    "upstreamTopic":"/example/pump/up",
    "downstreamTopic":"/example/pump/down",
    "deviceKeyEnv":"FARM_DEVICE_PUMP_KEY",
    "commandsEnabled":false
  }]
}]}
```

凭据通过上述环境变量注入；配置中的 `deviceKeyEnv` 对应在本平台生成的设备凭据。完整路由仍须与页面中保存的配置一致；即使租户管理员修改页面中的下行主题，网关也只向私有配置明确授权的下行主题发布，避免借用共享 Broker 凭据控制其他设备。设置 `FARM_MQTT_ENABLED=true`、`FARM_MQTT_CONFIG=<私有配置绝对路径>`，再启动后端。建议同时将 `FARM_MQTT_INBOX` 指向项目 `.cache/mqtt-inbox` 的绝对路径。

网关收到上行后先写本地队列再确认 MQTT 接收；数据库暂不可用时保留队列重试，同一排队报文沿用原消息编号与采样时间。无效、失效凭据或已过期指令的报文保留为 `.rejected` 文件，检查映射/凭据后再决定是否重放。队列包含现场数据，不应提交、公开或复制到前端。请监控目录占用并按现场留存要求清理已处理的拒收和发送标记。

网关忽略 retained 历史消息。主动报文的 `params.sys_time` 支持秒/毫秒时间戳、ISO UTC 或由 `timeZone` 解释的 `yyyy-MM-dd HH:mm:ss`；无设备时间时使用首次接收时间。非法和未来时间不当作新鲜采样。没有设备侧消息序号的 MQTT 重投无法做到跨 Broker 重连的严格一次性采集；平台的幂等保证作用于已经持久化的消息编号。

## 控制下发和回执

- 本平台仍要求管理员为设备启用控制，且闸门/泵房必须有新鲜的运行与联锁反馈。`commandsEnabled=false` 的网关只收数据，不领取或发布控制指令。
- 泵房要求远程标志为 1、急停为 0、运行反馈新鲜；修改频率还要求 `remoteParamSet=1`。急停信号不等于所有故障均正常；已有未解除的通用故障也会阻止启动。
- 闸门模板仅能确认 `opening` 字段。远程模式和故障字段须按现场协议补充映射；缺少这些反馈时不允许提交开度控制，不制造“远程/无故障”状态。
- 停泵和急停允许在反馈过期时提交，但仍依赖通信链路，实际停止须由现场反馈确认。
- 外部网关可使用 `POST /api/ingest/smart-farm/commands/poll` 领取原生 `rw_prot.w_data` 指令，包括下行主题、指令编号及失效时间。设备回执以 `POST /api/ingest/smart-farm/commands/{id}/receipt` 提交 `{externalId, topic, payload}`。
- 原生成功回执要求 `dir=up`、指令编号匹配、写入字段和确认值匹配。未知/空回执不会直接成功；厂商失败回执需按其已核实的格式转换后调用既有通用失败回执接口。
- 内置网关启用某设备发送后，会在发布前持久化发送标记；发送结果不确定时不会盲目重新发布同一控制指令。未发出或未回执最终超时，显示“结果未知”。回执成功只说明设备确认写入，不会伪造实际运行或开度读数。

## 数据库预留与验证

默认 H2 启动方式不变。MySQL 使用 `mysql` profile、`FARM_DATABASE_URL/USER/PASSWORD`，连接 URL 需配置 UTC 时间转换（`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true`），连接池设置 UTC 会话及 ANSI_QUOTES。初始化默认关闭。仅对确认的独立空库设置 `FARM_DATABASE_INIT=always` 使用 `schema-mysql.sql`；后续启动恢复 `never`。脚本不创建云实例或数据库，也不迁移旧业务库。

`schema-mysql.sql` 由 `node tools/build_mysql_schema.cjs` 从 H2 模型生成，只用于全新 MySQL 8 库；已有 MySQL 库需要单独审阅增量迁移。RDS 凭据文件已被 Git 忽略。本次已在确认的独立空库建表，生成两租户的虚构业务、监测历史与季度模拟数据。

本地验证：

```powershell
.\scripts\verify.ps1
Set-Location backend
mvn.cmd -B package -DskipTests
Set-Location ..
node tools/test_smart_farm.cjs
```

后端测试覆盖协议归属、租户/角色隔离、幂等冲突、整批回滚、缺测、分层时间、历史补报、控制原生报文及回执、参数设置许可、告警转田间问题。浏览器验收使用独立内存数据库与随机端口，检查现场监测、接入配置、接收记录及小屏布局；不使用现场账号或设备。
