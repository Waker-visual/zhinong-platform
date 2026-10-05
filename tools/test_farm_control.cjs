// A disposable, localhost-only browser acceptance run. No production database or account is used.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");
const { randomBytes } = require("node:crypto");
const { spawn, execFileSync } = require("node:child_process");
const { setTimeout: delay } = require("node:timers/promises");
const root = path.resolve(__dirname, "..");
const { chromium } = require(
  require.resolve("playwright", { paths: [path.join(root, "frontend")] }),
);

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

(async () => {
  const jar = path.join(root, "backend/target/zhinong-platform-0.3.0.jar");
  assert.ok(fs.existsSync(jar), "Build frontend and package backend first");
  const port = await freePort(),
    base = `http://127.0.0.1:${port}`;
  const password = randomBytes(24).toString("base64url");
  const childEnv = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !key.startsWith("FARM_")),
  );
  const server = spawn(
    "java",
    [
      "-jar",
      jar,
      `--server.port=${port}`,
      "--server.address=127.0.0.1",
      "--spring.datasource.url=jdbc:h2:mem:browser-calibration;DB_CLOSE_DELAY=-1",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
      "--farm.demo=true",
      "--farm.demo-rich=true",
    ],
    {
      cwd: path.join(root, "backend"),
      env: { ...childEnv, FARM_BOOTSTRAP_PASSWORD: password },
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let logs = "",
    spawnError;
  const log = (data) => {
    logs = (logs + data.toString()).slice(-10000);
  };
  server.stdout.on("data", log);
  server.stderr.on("data", log);
  server.on("error", (e) => {
    spawnError = e;
  });
  let browser;
  const output = path.join(root, "artifacts/farm-control");
  fs.mkdirSync(output, { recursive: true });
  const errors = [];
  async function api(token, method, endpoint, body) {
    const response = await fetch(base + "/api" + endpoint, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    assert.equal(
      response.status,
      200,
      `${method} ${endpoint}: ${response.status}`,
    );
    return response.json();
  }
  async function login(username) {
    return (
      await api(null, "POST", "/auth/login", {
        tenantCode: "demo-a",
        username,
        password,
      })
    ).token;
  }
  async function select(page, label, option) {
    await page.getByRole("button", { name: label, exact: true }).click();
    await page.getByRole("option", { name: option, exact: true }).click();
  }
  async function openFarm(token) {
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
      reducedMotion: "reduce",
    });
    await context.addInitScript(
      (value) => sessionStorage.setItem("zhinong-session", value),
      token,
    );
    const page = await context.newPage();
    page.on("pageerror", (e) => errors.push(e.message));
    // This acceptance verifies offline map behavior without relying on external tile providers.
    await page.route("**/api/map-tiles/**", (route) => route.abort());
    await page.goto(base);
    await select(page, "当前农场", "青禾设备联动演示场");
    await page
      .locator("nav")
      .getByRole("button", { name: "农场概览", exact: true })
      .click();
    await page.locator('.farm-map-panel[data-load-state="offline"]').waitFor();
    return { page, context };
  }
  async function openDevice(page, id) {
    await page.locator(`[data-device-id="${id}"]`).click();
    await page
      .getByRole("button", { name: "完整详情与历史", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "设备控制与回执", exact: true })
      .waitFor();
  }
  try {
    let ready = false;
    for (let i = 0; i < 180; i++) {
      if (spawnError) throw spawnError;
      if (server.exitCode !== null)
        throw Error(
          "Acceptance server exited during startup\n" +
            logs.replaceAll(password, "[redacted]"),
        );
      try {
        const response = await fetch(base + "/api/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            tenantCode: "demo-a",
            username: "admin",
            password,
          }),
          signal: AbortSignal.timeout(1500),
        });
        if (response.ok) {
          ready = true;
          break;
        }
      } catch {}
      await delay(500);
    }
    assert.ok(ready, "Acceptance server did not become ready");
    browser = await chromium.launch({ channel: "msedge", headless: true });
    const admin = await login("admin");
    let farm;
    // HTTP starts before ApplicationRunner finishes adding the demo scenarios.
    for (let i = 0; i < 60 && !farm; i++) {
      const farms = await api(admin, "GET", "/farm-workspaces");
      farm = farms.find((item) => item.name === "青禾设备联动演示场");
      if (!farm) await delay(500);
    }
    assert.ok(farm, "Calibration fixtures did not become ready");
    const devices = await api(admin, "GET", `/assets?farmId=${farm.id}`);
    const gate = devices.find((item) => item.deviceType === "GATE"),
      pump = devices.find((item) => item.deviceType === "PUMP");
    const { page, context } = await openFarm(admin);
    await page.waitForFunction(
      () => document.querySelectorAll("[data-device-id]").length === 9,
    );
    await page
      .getByTestId("farm-map")
      .screenshot({ path: path.join(output, "offline-map.png") });
    console.log(
      "PASS nine device types and WGS84 points appear in the offline map",
    );
    await openDevice(page, gate.id);
    await page
      .getByRole("button", { name: "立即模拟采集全部指标", exact: true })
      .click();
    await page.getByLabel("目标值（%）", { exact: true }).fill("45");
    await page
      .getByLabel("操作说明", { exact: true })
      .fill("虚构农场浏览器联动验收");
    await page
      .getByRole("button", { name: "提交模拟指令", exact: true })
      .click();
    await page.getByRole("button", { name: "确认提交", exact: true }).click();
    await page
      .locator(".command-history")
      .getByText("模拟 · 回执成功", { exact: true })
      .waitFor();
    let stored = await api(admin, "GET", `/assets/${gate.id}`);
    assert.equal(
      stored.channels.find((item) => item.metric === "GATE_OPENING").latest
        .value,
      45,
    );
    await page
      .locator(".device-control")
      .screenshot({ path: path.join(output, "gate-control.png") });
    console.log(
      "PASS gate simulation records a receipt and actual opening feedback",
    );
    await page
      .getByRole("button", { name: "关闭设备详情", exact: true })
      .click();

    await page.getByRole("button", { name: "编辑平面图", exact: true }).click();
    await select(page, "定位设备", gate.name);
    await page.getByRole("button", { name: "清除点位", exact: true }).click();
    const saved = page.waitForResponse(
      (r) =>
        r.url().endsWith(`/farms/${farm.id}/layout`) &&
        r.request().method() === "PUT",
    );
    await page.getByRole("button", { name: "保存平面图", exact: true }).click();
    assert.equal((await saved).status(), 200);
    await page
      .locator(`[data-device-id="${gate.id}"]`)
      .waitFor({ state: "detached" });
    stored = await api(admin, "GET", `/assets/${gate.id}`);
    assert.equal(stored.positioned, false);
    assert.equal(
      stored.channels.find((item) => item.metric === "GATE_OPENING").latest
        .value,
      45,
    );
    console.log(
      "PASS clearing a map position preserves the device and measurements",
    );

    await page
      .locator("nav")
      .getByRole("button", { name: "设备台账", exact: true })
      .click();
    await page.getByRole("button", { name: /新增设备/ }).click();
    await select(page, "设备类型", "泵房控制器");
    await page
      .getByRole("button", { name: "应用设备指标模板", exact: true })
      .click();
    assert.equal(await page.locator(".channel-row").count(), 11);
    await page
      .getByRole("button", { name: "关闭设备编辑", exact: true })
      .click();
    console.log(
      "PASS device presets populate the editor from the server catalog",
    );
    await context.close();

    const viewer = await openFarm(await login("viewer"));
    await openDevice(viewer.page, pump.id);
    assert.equal(
      await viewer.page
        .getByRole("button", { name: "提交模拟指令", exact: true })
        .count(),
      0,
    );
    assert.equal(
      await viewer.page
        .getByRole("button", { name: "编辑设备", exact: true })
        .count(),
      0,
    );
    await viewer.context.close();
    const operator = await openFarm(await login("operator"));
    await openDevice(operator.page, pump.id);
    await operator.page
      .getByRole("button", { name: "控制操作", exact: true })
      .click();
    assert.ok(
      await operator.page
        .getByRole("option", { name: "设置主泵频率（管理员）", exact: true })
        .isDisabled(),
    );
    await operator.page.setViewportSize({ width: 390, height: 844 });
    await operator.page.screenshot({
      path: path.join(output, "operator-mobile.png"),
    });
    await operator.context.close();
    console.log(
      "PASS viewer is read-only and pump parameter changes are disabled for operators",
    );
    assert.deepEqual(errors, [], "Browser runtime errors");
    console.log(
      "PASS no browser runtime errors; disposable acceptance complete",
    );
  } catch (error) {
    for (const [index, context] of (browser?.contexts() || []).entries()) {
      await context
        .pages()[0]
        ?.screenshot({ path: path.join(output, `failure-${index}.png`) })
        .catch(() => {});
    }
    throw error;
  } finally {
    await browser?.close();
    if (server.pid && server.exitCode === null) {
      const stopped = new Promise((resolve) => server.once("exit", resolve));
      // On Windows an Oracle java-path launcher can create a child JVM. Stop our whole
      // disposable process tree so it cannot keep the packaged jar locked after acceptance.
      if (process.platform === "win32") {
        execFileSync("taskkill.exe", ["/PID", String(server.pid), "/T", "/F"], {
          windowsHide: true,
          stdio: "ignore",
        });
      } else server.kill();
      await Promise.race([stopped, delay(5000)]);
    }
  }
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
