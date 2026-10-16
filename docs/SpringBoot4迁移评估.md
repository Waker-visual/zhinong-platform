# Spring Boot 4 迁移评估（试迁分支）

2026-10-16 在 `claude/clever-roentgen-e715fd` 分支（基于 PR10 的 Spring Boot 3.5.16，提交 `b668b72`）把 `backend/` 试迁到 Spring Boot 4.1.1。结论：**建议在 PR10 合入后立即迁移到 4.1.x**，理由与前提见文末。

## 目标版本与支持期

数据取自 spring.io 项目支持接口（`api.spring.io/projects/spring-boot/generations`）和 GitHub 发布记录，2026-10-16 查询。

| 版本线 | 首发 | 开源支持截止 | 商业支持截止 | 说明 |
| --- | --- | --- | --- | --- |
| 3.5.x | 2025-05-31 | **2026-06-30（已结束）** | 2032-06-30 | 3.5.16（2026-06-25）是最后一个开源补丁 |
| 4.0.x | 2025-11-30 | 2026-12-31 | 2027-12-31 | 最新 4.0.8，两个半月后停止开源支持，不选 |
| **4.1.x** | 2026-06-30 | **2027-07-31** | 2028-07-31 | **目标：4.1.1（2026-08-20），当前最新 GA** |
| 4.2.x | 计划 2026-11-30 | 2027-12-31 | 2028-12-31 | 当前为 4.2.0-M2，不用于生产 |

参考：[Spring Boot 4.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Spring Boot 4.1 发布说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)。4.1 相对 4.0 的破坏性变更（移除 4.0 已弃用 API、Derby 弃用、layertools 移除、`-DskipTests` 不再跳过测试 AOT、Tomcat 临时目录关闭时删除）均不涉及本项目。

## 验证结果

- `mvn test`：默认执行 234 项、0 失败（15 项 MySQL 集成测试按环境变量跳过，与 3.5.16 相同）；`server-utc` 执行（`-Duser.timezone=UTC`）5 项全部通过。surefire 两段执行配置无需改动。
- `scripts/verify.ps1` 全部通过（后端、前端 Node 测试与构建、项目检查工具、Android 地址、MySQL 表结构一致性）。
- 接口对照：3.5.16 与 4.1.1 两个构建分别以内存 H2 + 演示数据启动，抓取 44 个 GET 接口（租户管理员与平台账号视角，含 actuator 健康与指标、401/403/404 路径），逐字段比较状态码、字段路径与值格式。状态码与字段集合完全一致，唯一的格式差异见下文“时间戳后缀”。键顺序差异均来自 `Map.of`（每次 JVM 启动随机），不是升级引起。
- H2 2.3.232 → 2.4.240：用 2.3.232 写入的文件库可被 2.4.240 直接打开读取，2.4.240 打开后 2.3.232 仍可回读，无需导出导入。

## 本代码库遇到的破坏性变更及处理

| # | 变更 | 影响 | 处理 |
| --- | --- | --- | --- |
| 1 | Jackson 3：`com.fasterxml.jackson.databind/core` → `tools.jackson.databind/core`（注解包 `com.fasterxml.jackson.annotation` 不变） | 41 个源文件无法编译 | 批量替换 import；`JsonProcessingException` → `tools.jackson.core.JacksonException` |
| 2 | Jackson 3 自定义序列化 API：`Module` → `JacksonModule`，`JsonSerializer` → `ValueSerializer`，`SerializerProvider` → `SerializationContext`，不再声明 `IOException` | `DatabaseJsonConfiguration` | 按新 API 重写，Spring Boot 4 照常自动注册 `JacksonModule` Bean |
| 3 | `JsonNode.fieldNames()` 移除（改为 `propertyNames()` 返回集合） | `SmartFarmIntegration.canonical` | `names.addAll(value.propertyNames())` |
| 4 | Jackson 3 异常改为非受检：`JacksonException extends RuntimeException`，写文件失败抛 `JacksonIOException` | `catch (IOException)` 不再捕获 Jackson 错误。逐个核对 10 处，只有 `MapTileService.write` 会因此让磁盘写满导致瓦片请求失败 | 该处改为 `catch (IOException \| JacksonException)`；其余处已捕获 `Exception`/`RuntimeException` 或不含 Jackson 调用 |
| 5 | Jackson 3 默认开启 `FAIL_ON_NULL_FOR_PRIMITIVES`：请求体省略 `boolean`/`int` 字段时返回 400 | 测试发现：`POST /api/ai/config` 不带 `clearApiKey` 失败；另有 `Enabled`、`PinRequest`、`WaterInput` 等请求记录含基本类型字段 | `application.yml` 设 `spring.jackson.deserialization.fail-on-null-for-primitives: false`，保持 3.x 接口契约 |
| 6 | Jackson 3 把 `java.sql.Date` 写成 UTC 时刻（`2024-10-15T16:00:00.000Z`），不再是 `2024-10-16`，且没有开关可恢复 | JdbcTemplate 直接返回的 DATE 列（研究数据、天气分桶、经营报表）**日期整体错前一天**，6 项集成测试失败 | `DatabaseJsonConfiguration` 新增 `java.sql.Date` 序列化器，按 `toString()` 输出 `yyyy-MM-dd`（与 Jackson 2 一致），H2 与 MySQL 均生效 |
| 7 | Jackson 3 `JsonNode.asText()/asInt()/asDouble()/asBoolean()` 遇到类型不符（对象、数组、非数字字符串）时抛 `JsonNodeException`，Jackson 2 返回 `""/0/false` | 设备报文中非标量字段：HTTP 接收返回 500（原为 400/403）；MQTT 回调里的 `sampleTime` 异常会逃出 Paho 回调；收件箱中该报文既不入库也不移入 `.rejected`，按文件名排序时会挡住其后所有报文 | 5 处设备入口改用 `asString("")`（`SmartFarmIntegration` 设备编号、回执编号/方向、回执值，`SmartFarmMqttGateway` 设备时间、回执编号），恢复原行为；新增 `SmartFarmMqttGatewayTest`、`SmartFarmIntegrationTest` 回归断言。其余 56 处无默认值调用：演示数据与内置天气数据（可信）、已先判断 `isTextual` 的大模型与追问解析、外层 `catch (Exception)` 的大模型响应解析 |
| 8 | `spring-boot-starter-web` 弃用，改为 `spring-boot-starter-webmvc` | pom | 已替换 |
| 9 | 测试改为按技术模块拆分；`@AutoConfigureObservability` 移除 | `RuntimeOperationsTest` 无法编译 | `spring-boot-starter-test` 换成 `spring-boot-starter-actuator-test`（传递带入 `spring-boot-starter-test` 与 `spring-boot-micrometer-metrics-test`），注解改为 `org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics` |
| 10 | `JsonNode.findValuesAsText` → `findValuesAsString` | `DeviceAlertIntegrationTest` | 已替换 |

### 已核对、无需改动

- **Jakarta EE 11 / Servlet 6.1**：只用到 `jakarta.servlet` 过滤器、`jakarta.validation`、`jakarta.annotation`，Tomcat 11.0.24 与 Hibernate Validator 9.1 下无编译或行为差异。
- **Actuator**：`application.yml` 中 `management.*` 属性在 4.1 均有效；探针本来已显式开启，`/api/ops/health/{readiness,liveness}`、`/api/ops/metrics`（含 `http.server.requests`、`jdbc.connections.active`）及权限行为与 3.5 一致。
- **测试切片与 Mockito**：未使用 `@MockBean`、`TestRestTemplate`、MockMvc；集成测试用 `java.net.http.HttpClient` 加 `@LocalServerPort`（包名未变）。JUnit 升到 Jupiter 6.0.3，Mockito 5.23.0，Byte Buddy 1.18.11，开发机 JDK 25 正常。
- **Spring Security Crypto 7.1.1**：只用 `BCryptPasswordEncoder(11)`，散列格式不变，已有账号可登录（登录、改密与会话测试通过）。
- **H2 2.4.240 / MySQL Connector/J 9.7.0**：Connector/J 版本与 3.5.16 相同；H2 兼容性见上。
- **Eclipse Paho MQTT 1.2.5**：纯 Java 客户端，不依赖 Spring 或 Jakarta，未变。
- **Jackson 3 其他默认值**：`spring.jackson.use-jackson2-defaults` 保持 `false`。`SORT_PROPERTIES_ALPHABETICALLY`、`FAIL_ON_TRAILING_TOKENS`（3.5 下尾随内容已返回 400）未改变接口行为；无重写 `toString()` 的枚举。

### 行为差异（已接受）

- **时间戳后缀**：`java.sql.Timestamp`/`java.util.Date` 由 `…000+00:00` 变为 `…000Z`，同一时刻。涉及 `/api/audit` 的 `OCCURRED_AT`（H2 下的 `TIMESTAMP` 列）和 Spring 默认错误体的 `timestamp`。前端只用 `new Date()` 解析或截取前 19 位显示，Android 端不解析该字段；不影响显示。
- **`asText()` 读 JSON null**：Jackson 3 返回 `""`，Jackson 2 返回字符串 `"null"`。现有代码均先判断 `isNull()` 或按空值处理，属于纠正。

## 依赖变化（运行时）

| 依赖 | 3.5.16 | 4.1.1 |
| --- | --- | --- |
| Spring Framework | 6.2.19 | 7.0.9 |
| Tomcat embed | 10.1.55 | 11.0.24 |
| Jackson | 2.21（`com.fasterxml.jackson`） | 3.1.5（`tools.jackson`；注解仍为 2.21） |
| Hibernate Validator / Jakarta Validation | 8.0.3 / 3.0.2 | 9.1.3 / 3.1.1 |
| HikariCP | 6.3.3 | 7.0.2 |
| H2 | 2.3.232 | 2.4.240 |
| Micrometer | 1.15.12 | 1.17.1（不再带 LatencyUtils） |
| Spring Security Crypto | 6.5.11 | 7.1.1 |
| MySQL Connector/J / Paho | 9.7.0 / 1.2.5 | 不变 |
| 新增 | — | JSpecify 1.0.1、commons-logging 1.3.6（替代 spring-jcl）、Boot 4 拆分模块，均为 Apache-2.0 |

**尚未兼容的依赖：无。** 本项目直接依赖均有 Boot 4.1 管理的兼容版本；Paho 与 Spring 无关。

## 主分支整合验证（2026-10-16）

在主分支 `f780d22`（已包含 PR #9–#12）上，以 Windows / JDK 25 补充验证：

- `scripts/verify.ps1` 全部通过：后端默认执行 238 项（15 项 MySQL 用例在此阶段跳过），UTC 独立执行 5 项；前端测试与生产构建、字体与包体检查、Android 地址校验、MySQL 表结构一致性及敏感信息扫描均通过。
- `scripts/verify-mysql.ps1` 在本机独立临时 MySQL 库执行，15 项接口与数据回归全部通过，包含日期与时间序列化、研究数据和租户隔离；测试结束后清理临时库，不使用业务库。
- 干净打包后运行 `tools/test_floating_assistant.cjs`，小窗与完整 AI 页共享生成、失败重试、切换农场隔离及手机布局全部通过。该验收使用一次性 H2 库并禁用外部模型配置。
- 先备份原有演示库，再验证 H2 2.2.224 文件可由 2.4.240 只读打开；新版服务使用原库启动、健康检查和原账号登录均成功。这是启动与基础读取验证，不代表全库逐行审计或长时间运行验证。
- 模型网关继续读取本机私有配置，整合前后配置文件校验值一致；通过已保存配置执行连接测试返回 `OK`，当前模型为 `deepseek-flash`。连接地址和密钥不写入仓库。
- 五个演示农场逐一切换验证：WebGL 场景加载、每场 9 个田块标识和顶部音乐控件正常，浏览器未捕获未处理异常。

## 仍未覆盖的验证

1. **真实 MQTT broker 与现场设备**：只有单元与 HTTP 集成测试，未连 broker 实测（3.5 也一样）。
2. **长时间运行的演示库**：已补充原有文件库启动和网页访问验证，尚未完成连续长时间运行及遥测保留任务的长期容量观察。
3. **弃用 API**：保留 `JsonNode.asText()`/`isTextual()`/`textValue()`，它们在 Jackson 3 中已弃用、行为与 `asString()`/`isString()`/`stringValue()` 相同（main 47 处、测试 529 处）。可在后续单独提交中机械替换，避免与其他进行中分支冲突。

## 建议

**迁移，在 PR10 合入后进行，目标 4.1.x。**

- 3.5.x 的开源支持已于 2026-06-30 结束，留在 3.5.16 意味着此后的 Spring、Tomcat、Jackson 安全修复都只在商业版本中提供。4.0.x 也将在 2026-12-31 停止开源支持，因此直接上 4.1（开源支持至 2027-07-31）。
- 实际改动量小：main 代码 19 个 Java 文件，其中 15 个只改 Jackson 包名，实质改动在 `DatabaseJsonConfiguration`、`MapTileService` 和两个设备接入类；另有 pom 两个 starter、`application.yml` 一项配置。测试与接口对照显示行为与 3.5.16 一致，差异只有时间戳后缀。
- 真正的风险都在 Jackson 3 的默认行为（变更 5、6、7），都不会产生编译错误；本分支已修复并加了回归测试。其他分支合入时如新增 `java.sql.Date` 直出、基本类型请求字段或对不可信 JSON 无默认值的 `asText()/asInt()` 调用，需按上述方式处理。
- MySQL 集成测试已在主分支整合时补齐，结果见上文；真实 MQTT 与长时间运行验证仍需另行安排。4.2.0 计划 2026-11-30 发布，届时从 4.1 升级属于小版本，不必等待。
