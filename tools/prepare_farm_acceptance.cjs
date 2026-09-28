/* Opt-in local fixtures. Never resets passwords or writes to an existing business tenant. */
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const root = path.resolve(__dirname, "..");
const base = process.env.FARM_BASE_URL || "http://127.0.0.1:9175";
if (!["127.0.0.1", "localhost"].includes(new URL(base).hostname))
  throw Error("Acceptance fixtures are restricted to localhost");
const normalize = (x) =>
  Array.isArray(x)
    ? x.map(normalize)
    : x && typeof x === "object"
      ? Object.fromEntries(
          Object.entries(x).map(([k, v]) => [
            /^[A-Z_]+$/.test(k)
              ? k.toLowerCase().replace(/_([a-z])/g, (_, c) => c.toUpperCase())
              : k,
            normalize(v),
          ]),
        )
      : x;
async function api(token, route, method = "GET", body) {
  const r = await fetch(base + "/api" + route, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60000),
  });
  const data = await r.json();
  if (!r.ok)
    throw Error(`${method} ${route}: ${r.status} ${data.message || ""}`);
  return normalize(data);
}
const login = (a) =>
  api(null, "/auth/login", "POST", {
    tenantCode: a.tenant,
    username: a.username,
    password: a.password,
  });
const date = (offset) => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return new Date(d - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
};
async function main() {
  fs.mkdirSync(path.join(root, ".cache"), { recursive: true });
  fs.mkdirSync(path.join(root, "artifacts"), { recursive: true });
  const file = path.join(root, ".cache", "farm-acceptance-accounts.json");
  let accounts;
  if (fs.existsSync(file)) accounts = JSON.parse(fs.readFileSync(file, "utf8"));
  else {
    accounts = [
      ["owner", "family-trial", "admin", "家庭农场主"],
      ["worker", "family-trial", "worker", "田间操作员小禾"],
      ["backup", "family-trial", "backup", "协作操作员小林"],
      ["viewer", "family-trial", "viewer", "经营查看者"],
      ["neighbor", "neighbor-trial", "admin", "邻场隔离验证"],
    ].map(([key, tenant, username, label]) => ({
      key,
      tenant,
      username,
      label,
      password: crypto.randomBytes(18).toString("base64url"),
    }));
    // Persist recovery information before the first external mutation.
    fs.writeFileSync(file, JSON.stringify(accounts, null, 2));
  }
  const platformPassword =
    process.env.FARM_PLATFORM_PASSWORD ||
    fs
      .readFileSync(path.join(root, ".cache", "demo-password.txt"), "utf8")
      .trim();
  const platformAccount = {
    key: "platform",
    tenant: "platform",
    username: "platform",
    label: "平台管理员（原账号）",
    password: platformPassword,
  };
  const platform = (await login(platformAccount)).token;
  let tenants = await api(platform, "/platform/tenants");
  for (const key of ["owner", "neighbor"]) {
    const a = accounts.find((x) => x.key === key);
    if (!tenants.some((t) => t.code === a.tenant))
      await api(platform, "/platform/tenants", "POST", {
        code: a.tenant,
        name: key === "owner" ? "禾间家庭农场 · 独立验收" : "邻场 · 隔离验收",
        adminPassword: a.password,
      });
  }
  const owner = accounts.find((x) => x.key === "owner"),
    admin = (await login(owner)).token;
  const existing = await api(admin, "/members");
  for (const a of accounts.filter((x) =>
    ["worker", "backup", "viewer"].includes(x.key),
  )) {
    if (!existing.some((m) => m.username === a.username))
      await api(admin, "/members", "POST", {
        username: a.username,
        displayName: a.label,
        password: a.password,
        role: a.key === "viewer" ? "VIEWER" : "OPERATOR",
      });
  }
  const crew = await api(admin, "/members");
  const workerId = crew.find((m) => m.username === "worker").id,
    backupId = crew.find((m) => m.username === "backup").id;
  const workers = {
    worker: (await login(accounts.find((x) => x.key === "worker"))).token,
    backup: (await login(accounts.find((x) => x.key === "backup"))).token,
  };
  let farms = await api(admin, "/farms");
  let farm = farms.find((f) => f.name === "禾间家庭农场（验收）");
  if (!farm)
    farm = await api(admin, "/farm-workspaces", "POST", {
      name: "禾间家庭农场（验收）",
      description:
        "虚构验收场景：112亩混合作物，三人协作，无人机检修，人工植保补位。所有记录仅用于本机流程演练。",
      region: "公开农业区域示例 · 非实际经营地址",
      farmType: "MIXED",
    });
  let plots = await api(admin, "/plots?farmId=" + farm.id);
  for (const [name, crop, areaMu] of [
    ["东区水稻田", "水稻", 60],
    ["西区玉米田", "玉米", 40],
    ["近棚蔬菜田", "蔬菜", 12],
  ]) {
    if (!plots.some((p) => p.name === name))
      await api(admin, "/plots", "POST", {
        farmId: farm.id,
        name,
        crop,
        areaMu,
      });
  }
  plots = await api(admin, "/plots?farmId=" + farm.id);
  const rice = plots.find((p) => p.crop === "水稻"),
    corn = plots.find((p) => p.crop === "玉米"),
    veg = plots.find((p) => p.crop === "蔬菜");
  let plantings = await api(admin, "/plantings?farmId=" + farm.id);
  for (const p of plots) {
    if (!plantings.some((x) => x.plotId === p.id)) {
      let planting = await api(admin, "/plantings", "POST", {
        plotId: p.id,
        crop: p.crop,
        variety: "验收示例品种",
        areaMu: p.areaMu,
        startDate: date(-60),
        endDate: date(30),
      });
      await api(admin, "/plantings/" + planting.id + "/status", "PATCH", {
        status: "ACTIVE",
      });
    }
  }
  let assets = await api(admin, "/assets?farmId=" + farm.id);
  for (const [index, p] of [rice, corn, veg].entries()) {
    const code = "trial-soil-" + index;
    if (!assets.some((a) => a.code === code)) {
      const asset = await api(admin, "/assets", "POST", {
        farmId: farm.id,
        name: p.name + "墒情点（模拟）",
        code,
        deviceType: "SOIL",
        protocol: "SIMULATED",
        lifecycle: "ACTIVE",
        plotId: p.id,
        planX: [230, 680, 680][index],
        planY: [320, 220, 510][index],
        model: "本地模拟传感器",
        notes: "虚构设备，未连接实体硬件",
        intervalSeconds: 3600,
        channels: [{ metric: "SOIL_MOISTURE", lowerLimit: 25, upperLimit: 70 }],
        revision: 0,
      });
      await api(admin, "/assets/" + asset.id + "/collect", "POST");
    }
  }
  const workspace = await api(admin, "/farms/" + farm.id + "/workspace");
  // Only establish a new layout; preserve any manual edits on subsequent runs.
  if (workspace.farm.layoutRevision === 0) {
    await api(admin, "/farms/" + farm.id + "/layout", "PUT", {
      revision: 0,
      shapes: [
        {
          plotId: rice.id,
          boundary: [
            [70, 100],
            [450, 100],
            [450, 610],
            [70, 610],
          ],
        },
        {
          plotId: corn.id,
          boundary: [
            [500, 100],
            [930, 100],
            [930, 360],
            [500, 360],
          ],
        },
        {
          plotId: veg.id,
          boundary: [
            [500, 410],
            [930, 410],
            [930, 610],
            [500, 610],
          ],
        },
      ],
      positions: [],
    });
  }
  let state = await api(admin, "/field-work?farmId=" + farm.id);
  async function issue(plot, category, description) {
    let i = state.issues.find((i) => i.description === description);
    if (!i) {
      i = await api(workers.worker, "/field-work/issues", "POST", {
        plotId: plot.id,
        category,
        severity: "HIGH",
        description,
      });
      state = await api(admin, "/field-work?farmId=" + farm.id);
      i = state.issues.find((x) => x.id === i.id);
    }
    return i;
  }
  await issue(
    rice,
    "PEST",
    "【验收】东区田边发现虫害，需安排巡查和植保，当前没有可用无人机。",
  );
  const blockedIssue = await issue(
    corn,
    "PEST",
    "【验收】西区虫害防治已安排，但原定无人机正在检修。",
  );
  if (
    blockedIssue.status === "OPEN" &&
    !state.tasks.some((t) => t.title === "【验收】西区植保人工补位")
  ) {
    const task = await api(
      admin,
      "/field-work/issues/" + blockedIssue.id + "/task",
      "POST",
      {
        plotId: corn.id,
        title: "【验收】西区植保人工补位",
        taskType: "PROTECTION",
        dueDate: date(-1),
        assigneeId: workerId,
        method: "UNCONFIRMED",
        note: "请农场主确认可用的人工队伍或外包服务。",
      },
    );
    await api(
      workers.worker,
      "/field-work/tasks/" + task.id + "/progress",
      "PATCH",
      {
        status: "BLOCKED",
        note: "【验收】无人机检修，缺少替代设备；请求30亩/日人工队伍补位。",
        method: "UNCONFIRMED",
        actualAreaMu: 0,
      },
    );
  }
  for (const [title, p, type, who, offset] of [
    ["【验收】东区虫情复查", rice, "INSPECTION", workerId, 0],
    ["【验收】蔬菜田滴灌检查", veg, "IRRIGATION", backupId, 0],
    ["【验收】玉米成熟度巡检", corn, "INSPECTION", workerId, 2],
  ]) {
    if (!state.tasks.some((t) => t.title === title))
      await api(admin, "/field-work/tasks", "POST", {
        plotId: p.id,
        title,
        taskType: type,
        dueDate: date(offset),
        assigneeId: who,
        method: "MANUAL",
        note: "虚构验收任务，执行时填写现场结果。",
      });
  }
  let production = await api(admin, "/production?farmId=" + farm.id);
  if (!production.some((p) => p.note === "【验收】蔬菜分批采收样例"))
    await api(workers.backup, "/production", "POST", {
      plotId: veg.id,
      recordDate: date(-2),
      yieldKg: 860,
      note: "【验收】蔬菜分批采收样例",
    });
  const runs = await api(admin, "/simulations?farmId=" + farm.id);
  let results = [];
  for (const preset of ["无人机缺位", "巡检延迟", "灌溉不足"]) {
    const label = "家庭农场季度验收 · " + preset;
    let old = runs.find((r) => r.label === label);
    let r = old
      ? await api(admin, "/simulations/" + old.id)
      : await api(admin, "/simulations", "POST", {
          farmId: farm.id,
          label,
          startDate: "2025-06-01",
          days: 90,
          initialAge: 40,
          drones: 1,
          droneCapacity: 120,
          manualCapacity: 30,
          inspectionInterval: preset === "巡检延迟" ? 14 : 3,
          responseDays: 3,
          outbreakDay: 20,
          outageDays: 45,
          severity: 0.75,
          irrigationM3: preset === "灌溉不足" ? 30 : 500,
          fertilizerCoverage: 0.9,
          cropModels: Object.fromEntries(
            plots.map((p) => [
              p.id,
              p.crop === "水稻"
                ? "RICE"
                : p.crop === "玉米"
                  ? "MAIZE"
                  : "VEGETABLE",
            ]),
          ),
        });
    results.push({ id: r.id, label, result: r.result });
  }
  fs.writeFileSync(
    path.join(root, "artifacts", "family-farm-simulations.json"),
    JSON.stringify(results, null, 2),
  );
  const neighbor = (await login(accounts.find((x) => x.key === "neighbor")))
    .token;
  const nfarms = await api(neighbor, "/farms");
  if (!nfarms.length)
    await api(neighbor, "/farms", "POST", {
      name: "邻场隔离检查（验收）",
      description: "此租户不应看到禾间家庭农场的任何记录。",
    });
  const all = [...accounts, platformAccount];
  fs.writeFileSync(
    path.join(root, ".cache", "farm-acceptance-accounts.md"),
    "# 智禾农场本机验收账号\n\n仅用于 http://127.0.0.1:9175 。请勿提交到公共仓库。\n\n| 身份 | 租户代码 | 用户名 | 密码 |\n|---|---|---|---|\n" +
      all
        .map(
          (a) => `| ${a.label} | ${a.tenant} | ${a.username} | ${a.password} |`,
        )
        .join("\n") +
      "\n\n密码只在创建验收账号时随机生成，重跑此脚本不会重置既有密码。已有账号改密后请以新密码登录；本文件不会自动更新。\n",
  );
  fs.writeFileSync(
    path.join(root, "artifacts", "family-farm-fixture.json"),
    JSON.stringify(
      {
        farmId: farm.id,
        plots,
        workerId,
        backupId,
        createdAt: new Date().toISOString(),
      },
      null,
      2,
    ),
  );
  console.log(
    JSON.stringify({
      ready: true,
      tenant: "family-trial",
      farmId: farm.id,
      roles: all.map(({ label, tenant, username }) => ({
        label,
        tenant,
        username,
      })),
      simulationRuns: results.length,
      credentialsFile: ".cache/farm-acceptance-accounts.md",
    }),
  );
}
main().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
