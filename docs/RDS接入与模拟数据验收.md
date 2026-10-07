# RDS 接入与模拟数据验收

代码支持本地 H2 和 MySQL 8 核心业务库，SQL 方言、布尔值、UTC 时间、设备多指标和经营模拟表采用同一套业务逻辑。`schema-mysql.sql` 用于独立空库，不能作为已有数据库的自动迁移工具。项目不导入或修改 smart-farm 的生产数据库。

## 私有连接信息

项目根目录的 `rds.txt` 由本机读取，支持中文或英文键名、英文或中文冒号。填写以下五个字段，每行一个，值使用实际配置：

```text
host: database.example.com
port: 3306
user: replace_with_database_user
password: replace_with_private_password
database: zhinong_platform
```

中文键名依次为「主机」「端口」「用户」「密码」「数据库」。脚本不会打印凭据；密码通过进程环境传递，退出后恢复原环境。Git 忽略此文件、`.cache/` 和 `*.private.json`，公开范围检查也会阻止误提交这些文件。GitHub 只保存配置模板、代码、建表脚本和文档。

## VPN 下的直连

部分 VPN 使用虚拟 DNS 地址，单纯绑定本机网卡仍然无法访问数据库。`-Direct` 通过阿里公共 DNS 的 HTTPS 查询获得真实 IPv4，并绑定有网关的物理网卡；不修改系统路由、不关闭 VPN。DNS 查询只发送域名，不发送数据库账号或密码。可用 `-DirectAddress` 指定数据库真实 IPv4，`-BindAddress` 指定本机物理网卡 IPv4。云数据库 IP 可能变化，不应把临时解析结果写死在源码中。

先安装 MySQL 8 命令行客户端并放入 PATH，或使用 `-MySqlClient` 指定 `mysql.exe`。只读检查：

```powershell
.\scripts\start-rds.ps1 -Direct -Probe
```

检查包含 TLS、数据库版本、表数量和项目表标识。连接失败只输出错误编号。`ERROR 2026` 且服务端未提供 SSL 时，需要先在云数据库控制台启用 TLS，再重试；脚本强制 `sslMode=REQUIRED`，不会降级到未加密连接。需要服务器证书身份校验的部署，应使用正确的主机名解析与 `VERIFY_IDENTITY`、可信 CA 配置，不能直接把域名替换成证书未包含的 IP。

## 初始化与启动

确认是本项目独立空库后，首次启动：

```powershell
.\scripts\start-rds.ps1 -Direct -InitializeEmptyDatabase -Demo
```

脚本会先检查库是否为空，构建前后端后初始化。`-Demo` 创建 `demo-a` / `demo-b` 的虚构业务数据，启用连续模拟。平台账号为 `platform / platform`，演示账号为 `admin / operator / viewer`。新生成的初始化密码只保存在 `.cache/rds-bootstrap-password.txt`，不公开输出。已存在的账号密码不会被重置。

后续启动不再传初始化参数：

```powershell
.\scripts\start-rds.ps1 -Direct -Demo -SkipBuild
```

已有程序数据但结构不完整时，脚本拒绝启动并要求审阅迁移；不会删库、清空表或覆盖已有业务记录。默认不加 `-Demo` 时不生成模拟数据，真实 MQTT 网关仍关闭。经营模拟使用当前业务库中的独立 `sim_*` 表，不写入真实产量台账。启动脚本清除可能残留的独立模拟库环境变量，退出时恢复。

## 连续模拟数据的范围

`start-demo.ps1` 和 `start-rds.ps1 -Demo` 默认维护最近 **30 天**的监测历史。模拟服务每分钟检查一次，到达设备配置的采样时间才生成新记录。联动场每 15 分钟一批，综合场每小时一批；启动和每天的检查修复窗口内缺少的采样点，新增指标补齐自身历史。旧记录保留，重复启动不重复插入相同采样点。

自动生成限定于 `demo-a` / `demo-b` 中有演示标志、使用保留示例编码的 `SIMULATED` 活跃设备。停用租户、维护设备、人工录入和 HTTP 接入不参与。切换到真实协议后立即停止补数。新创建的连续演示场设备全部使用模拟协议；旧版本已有的人工、离线和未接入设备保留原有配置，仍可能显示缺测或过期，不能用构造数据掩盖这些状态。

模型具有以下约束：

- 按东八区日照周期生成光照，夜间为零；气温与湿度反向变化。
- 三层土温有不同振幅与相位，水分和电导率相关；pH 缓慢变化，不随机大幅跳跃。
- 主泵、备用泵、闸门等反馈保留操作后的状态，采样不会自动重置；停泵时流量和电流为零。
- 累计用水、用电、降雨由上次值增加，累计量不使用上下振荡的正弦曲线。
- 所有记录标注 `SIMULATED`，离散状态为整数，数值遵循指标单位与范围。

这些是可重复的虚构演示曲线，未经现场标定，不代表某地真实气象、产量或设备精度。真实报文缺测仍保持缺测；设备下发成功也不会伪造实际位置或运行反馈。经营台账保留业务发生频率，没有收获的日期不会人为插入产量。

## 验证

```powershell
.\scripts\verify.ps1
# 独立的本机 MySQL 实例；不会访问 RDS
.\scripts\setup-simulation-db.ps1 -MySqlHome 'C:\tools\mysql'
# 先重新构建 jar，再验证真实 MySQL SQL、完整历史、权限和控制
mvn.cmd -f backend/pom.xml -B -DskipTests package
node tools/test_mysql.cjs
node tools/test_smart_farm.cjs
```

MySQL 验收创建随机命名的本机数据库，使用 TLS 连接，验证完成后清理该临时库；失败时保留库供排查。检查覆盖 30 天、逐通道采样间隔、重复点、夜间光照、累计量回退、跨租户访问和模拟泵闸控制。日志留在忽略的 `.cache/`。浏览器验收另建临时内存数据库，不接触实际设备。

2026-10-07 使用 MySQL 8.4.9 验证：两演示租户共 46 台设备、293,898 条监测记录，30 天逐通道覆盖检查中缺口和重复点均为 0。原生泵房报文、重复上报、外部设备编号冲突、UTC 指令有效期、写入回执与实际反馈分离、告警转田间问题也通过本机 MySQL 接口验收。

2026-10-07 的云端检查已定位到 VPN 虚拟 DNS，并通过真实 IP 与物理网卡到达数据库；服务端未提供 SSL，因此尚未完成云端登录、建表或数据导入。云端验收不能由本机 MySQL 验收替代。
