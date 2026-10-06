const fs = require("node:fs"),
  path = require("node:path");
const root = path.resolve(__dirname, "..");
const { chromium } = require(
  require.resolve("playwright", { paths: [path.join(root, "frontend")] }),
);
const base = process.env.SCREENSHOT_BASE_URL || "http://127.0.0.1:9175";
if (!["127.0.0.1", "localhost"].includes(new URL(base).hostname))
  throw Error("Only local acceptance is allowed");
const password = process.env.FARM_BOOTSTRAP_PASSWORD;
if (!password) throw Error("Set FARM_BOOTSTRAP_PASSWORD");
const output = path.join(root, "artifacts/map-loading");
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1050 },
  });
  const errors = [],
    checks = [];
  let success = 0;
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("response", (r) => {
    if (r.url().includes("/api/map-tiles/") && r.ok()) success++;
  });
  const state = (s) =>
    page
      .locator('.farm-map-panel[data-load-state="' + s + '"]')
      .waitFor({ timeout: 25000 });
  const test = (ok, name) => {
    if (!ok) throw Error(name);
    checks.push(name);
    console.log("PASS " + name);
  };
  try {
    // A browser unable to reach either third-party tile domain must still display the local gateway's tiles.
    await page.route(/https:\/\/.*(arcgisonline|openstreetmap)\./, (r) =>
      r.abort(),
    );
    await page.goto(base);
    await page.getByLabel("密码", { exact: true }).fill(password);
    await page.getByRole("button", { name: /进入工作空间/ }).click();
    // 顶栏“当前农场”选定后，农场概览直接打开该农场的工作台
    await page
      .getByLabel("当前农场", { exact: true })
      .selectOption({ label: "青禾综合示范农场" });
    await page
      .locator("nav")
      .getByRole("button", { name: "农场概览", exact: true })
      .click();
    await state("ready");
    test(success > 0, "浏览器外域访问被阻断时，本机网关返回真实卫星瓦片");
    test(
      await page
        .locator(".leaflet-tile")
        .evaluateAll(
          (ts) =>
            ts.length > 0 && ts.every((t) => t.complete && t.naturalWidth > 0),
        ),
      "全部卫星瓦片成功解码",
    );
    test(
      (await page.locator("[data-plot-id]").count()) === 6 &&
        (await page.locator("[data-device-id]").count()) === 8,
      "六地块八设备保留",
    );
    await page
      .getByTestId("farm-map")
      .screenshot({ path: path.join(output, "satellite.png") });
    await page.locator("[data-device-id]").first().click();
    test(
      (await page.locator(".asset-marker.chosen").count()) === 1,
      "设备点位可点击且无异常",
    );
    await page.getByLabel("地图底图").selectOption("STREET");
    await state("ready");
    test(
      await page
        .getByRole("button", { name: "查看卫星实景", exact: true })
        .isVisible(),
      "街道图给出实景切换入口",
    );
    await page
      .getByRole("button", { name: "查看卫星实景", exact: true })
      .click();
    await state("ready");
    const pattern = "**/api/map-tiles/**";
    const fail = (r) =>
      r.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({ message: "验收模拟：上游不可用" }),
      });
    await page.route(pattern, fail);
    await page.getByLabel("地图底图").selectOption("STREET");
    await state("failed");
    test(
      await page
        .getByRole("button", { name: "重试底图", exact: true })
        .isVisible(),
      "上游全部失败显示原因与重试",
    );
    test(
      (await page.locator("[data-device-id]").count()) === 8,
      "底图失败时设备图层仍可见",
    );
    await page
      .locator(".farm-map-panel")
      .screenshot({ path: path.join(output, "failure.png") });
    await page.unroute(pattern, fail);
    await page.getByRole("button", { name: "重试底图", exact: true }).click();
    await state("ready");
    test(true, "网络恢复后重试成功");
    let failedOne = false;
    const partial = (r) => {
      if (!failedOne) {
        failedOne = true;
        return fail(r);
      }
      return r.continue();
    };
    await page.route(pattern, partial);
    await page.getByLabel("地图底图").selectOption("SATELLITE");
    await state("partial");
    test(true, "单瓦片失败显示部分加载状态");
    await page.unroute(pattern, partial);
    await page.getByRole("button", { name: "重试底图", exact: true }).click();
    await state("ready");
    const slow = async (r) => {
      await new Promise((resolve) => setTimeout(resolve, 17000));
      try {
        await fail(r);
      } catch {}
    };
    await page.route(pattern, slow);
    await page.getByLabel("地图底图").selectOption("STREET");
    await state("failed");
    test(
      (await page.locator(".map-network-status").innerText()).includes("超时"),
      "慢连接在限定时间内显示超时",
    );
    await page
      .getByRole("button", { name: "查看离线布局", exact: true })
      .click();
    await state("offline");
    test(
      (await page.locator("[data-device-id]").count()) === 8,
      "离线布局保留业务点位并明确标识",
    );
    await page.unroute(pattern, slow);
    for (const mode of ["STREET", "PLAN", "SATELLITE"])
      await page.getByLabel("地图底图").selectOption(mode);
    await state("ready");
    test(true, "快速切换后无旧请求干扰新图层");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(500);
    test(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      "手机地图无横向溢出",
    );
    await page.setViewportSize({ width: 1440, height: 1050 });
    await state("ready");
    test(errors.length === 0, "无未处理 JavaScript 异常");
    const fingerprint = require("./check_project.cjs").fingerprint(root);
    const report = {
      passed: true,
      fingerprint,
      checks,
      tileResponses: success,
      errors,
      createdAt: new Date().toISOString(),
    };
    fs.writeFileSync(
      path.join(root, "artifacts/map-loading-tests.json"),
      JSON.stringify(report, null, 2),
    );
  } catch (e) {
    await page.screenshot({
      path: path.join(output, "test-failure.png"),
      fullPage: true,
    });
    throw e;
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
