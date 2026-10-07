// Disposable browser acceptance: synthetic data, random localhost port, no MQTT or RDS connection.
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

(async () => {
  const listener = net.createServer();
  await new Promise((resolve) => listener.listen(0, "127.0.0.1", resolve));
  const port = listener.address().port;
  await new Promise((resolve) => listener.close(resolve));
  const base = `http://127.0.0.1:${port}`;
  const password = randomBytes(24).toString("base64url");
  const env = Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) => !/^(FARM_|SPRING_)/.test(key),
    ),
  );
  const server = spawn(
    "java",
    [
      "-jar",
      path.join(root, "backend/target/zhinong-platform-0.3.0.jar"),
      `--server.port=${port}`,
      "--server.address=127.0.0.1",
      "--spring.profiles.active=",
      "--spring.datasource.url=jdbc:h2:mem:smart-farm-browser;DB_CLOSE_DELAY=-1",
      "--spring.datasource.username=sa",
      "--spring.datasource.password=",
      "--farm.demo=true",
      "--farm.demo-rich=false",
      "--farm.mqtt.enabled=false",
    ],
    {
      cwd: path.join(root, "backend"),
      env: { ...env, FARM_BOOTSTRAP_PASSWORD: password },
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let logs = "",
    browser,
    spawnError;
  server.stdout.on("data", (data) => {
    logs = (logs + data).slice(-6000);
  });
  server.stderr.on("data", (data) => {
    logs = (logs + data).slice(-6000);
  });
  server.on("error", (error) => {
    spawnError = error;
  });
  const output = path.join(root, "artifacts/smart-farm");
  fs.mkdirSync(output, { recursive: true });
  const errors = [];
  async function api(token, method, endpoint, body, extra = {}) {
    const response = await fetch(base + "/api" + endpoint, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...extra,
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
  try {
    let admin;
    for (let i = 0; i < 120 && !admin; i++) {
      if (spawnError) throw spawnError;
      if (server.exitCode !== null)
        throw new Error(
          "Acceptance server exited: " +
            logs.replaceAll(password, "[redacted]"),
        );
      try {
        admin = (
          await api(null, "POST", "/auth/login", {
            tenantCode: "demo-a",
            username: "admin",
            password,
          })
        ).token;
      } catch {
        await delay(500);
      }
    }
    assert.ok(admin, "Acceptance server did not start");
    const farm = await api(admin, "POST", "/farm-workspaces", {
      name: "虚构现场联调农场",
      description: "浏览器合成验收",
      region: "虚构区域",
      farmType: "FIELD",
    });
    const plot = await api(admin, "POST", "/plots", {
      farmId: farm.id,
      name: "虚构三层监测田",
      areaMu: 10,
      crop: "水稻",
    });
    const catalog = await api(admin, "GET", "/assets/catalog");
    const device = await api(admin, "POST", "/assets", {
      farmId: farm.id,
      plotId: plot.ID,
      name: "虚构三层墒情设备",
      code: "BROWSER-SOIL",
      deviceType: "SOIL",
      protocol: "HTTP_PUSH",
      lifecycle: "ACTIVE",
      model: "合成节点",
      notes: "非真实设备",
      intervalSeconds: 60,
      revision: 0,
      channels: catalog.presets
        .find((p) => p.deviceType === "SOIL")
        .metrics.map((metric) => ({
          metric,
          ...(metric === "SOIL_MOISTURE"
            ? { lowerLimit: 20, upperLimit: 60 }
            : {}),
        })),
    });
    const key = (await api(admin, "POST", `/assets/${device.id}/credentials`))
      .key;
    browser = await chromium.launch({ channel: "msedge", headless: true });
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1000 },
      reducedMotion: "reduce",
    });
    await context.addInitScript(
      (value) => sessionStorage.setItem("zhinong-session", value),
      admin,
    );
    const page = await context.newPage();
    page.on("pageerror", (error) => errors.push(error.message));
    await page.route("**/api/map-tiles/**", (route) => route.abort());
    await page.goto(base);
    await page.getByRole("button", { name: "当前农场", exact: true }).click();
    await page
      .getByRole("option", { name: "虚构现场联调农场", exact: true })
      .click();
    await page
      .locator("nav")
      .getByRole("button", { name: "农场概览", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "地块现场监测", exact: true })
      .waitFor();
    await page
      .locator(".field-monitor-device")
      .filter({ hasText: device.name })
      .click();
    await page
      .getByRole("button", { name: "配置协议映射", exact: true })
      .click();
    await page
      .getByLabel("外部设备编号", { exact: true })
      .fill("example-browser-soil");
    assert.equal(await page.locator(".integration-binding").count(), 10);
    await page
      .getByRole("button", { name: "保存接入配置", exact: true })
      .click();
    await page
      .getByText("局域网四情采集 · example-browser-soil · 映射版本 1", {
        exact: true,
      })
      .waitFor();
    const measuredAt = new Date(Date.now() - 2000).toISOString();
    await api(
      null,
      "POST",
      "/ingest/smart-farm",
      {
        messageId: "browser-soil-001",
        measuredAt,
        externalId: "example-browser-soil",
        topic: "",
        payload: {
          deviceAddr: "example-browser-soil",
          data: [
            {
              nodeId: 1,
              temValue: 22.5,
              humValue: 8,
              timeStamp: Date.parse(measuredAt),
            },
            {
              nodeId: 3,
              temValue: 21.5,
              humValue: 38,
              timeStamp: Date.parse(measuredAt) - 7200000,
            },
          ],
        },
      },
      { "X-Device-Key": key },
    );
    await page
      .getByRole("button", { name: "刷新接收记录", exact: true })
      .click();
    await page
      .getByText("4 项有效读数 · 部分指标缺测", { exact: true })
      .waitFor();
    await page
      .getByRole("button", { name: "关闭设备详情", exact: true })
      .click();
    // Reload to inspect telemetry and alert state without relying on the refresh timer.
    await page.reload();
    await page.getByRole("button", { name: "当前农场", exact: true }).click();
    await page
      .getByRole("option", { name: "虚构现场联调农场", exact: true })
      .click();
    await page
      .locator("nav")
      .getByRole("button", { name: "农场概览", exact: true })
      .click();
    await page
      .locator(".field-monitor-device")
      .filter({ hasText: device.name })
      .waitFor();
    const card = page
      .locator(".field-monitor-device")
      .filter({ hasText: device.name });
    assert.match(await card.innerText(), /第二层土壤水分/);
    assert.match(await card.innerText(), /已过期/);
    assert.match(await card.innerText(), /第三层土壤水分/);
    assert.match(await card.innerText(), /等待上报/);
    await page.screenshot({
      path: path.join(output, "field-monitor-desktop.png"),
      fullPage: true,
    });
    await card.click();
    await page
      .getByRole("button", { name: "转为田间问题", exact: true })
      .click();
    const prompt = page.getByRole("dialog").last();
    await prompt
      .locator("textarea, input")
      .last()
      .fill("浏览器验收：请到田间核实探头接触和水分");
    await page
      .getByRole("button", { name: "创建田间问题", exact: true })
      .click();
    await page.getByText("已关联田间问题：待安排", { exact: true }).waitFor();
    await page
      .getByRole("button", { name: "配置协议映射", exact: true })
      .click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page
      .getByLabel("外部设备编号", { exact: true })
      .scrollIntoViewIfNeeded();
    assert.ok(
      await page.getByLabel("外部设备编号", { exact: true }).isVisible(),
    );
    await page.screenshot({
      path: path.join(output, "device-integration-mobile.png"),
      fullPage: false,
    });
    const overflow = await page
      .locator(".device-detail")
      .evaluate((el) => el.scrollWidth > el.clientWidth + 2);
    assert.equal(overflow, false, "Device detail must fit a narrow viewport");
    assert.deepEqual(errors, [], "Browser runtime errors");
    console.log(
      "PASS field monitoring, layer freshness, protocol mapping form, ingress diagnostics, alert-to-issue and mobile layout",
    );
  } catch (error) {
    for (const context of browser?.contexts() || [])
      await context
        .pages()[0]
        ?.screenshot({ path: path.join(output, "failure.png") })
        .catch(() => {});
    throw error;
  } finally {
    await browser?.close();
    if (server.pid && server.exitCode === null) {
      const ended = new Promise((resolve) => server.once("exit", resolve));
      if (process.platform === "win32")
        execFileSync("taskkill.exe", ["/PID", String(server.pid), "/T", "/F"], {
          windowsHide: true,
          stdio: "ignore",
        });
      else server.kill();
      await Promise.race([ended, delay(5000)]);
    }
  }
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
