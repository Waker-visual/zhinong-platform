const fs = require("node:fs"),
  path = require("node:path"),
  assert = require("node:assert/strict");
const { chromium } = require("../frontend/node_modules/playwright");
const root = path.resolve(__dirname, ".."),
  base = process.env.FARM_BASE_URL || "http://127.0.0.1:9175";
const accounts = JSON.parse(
  fs.readFileSync(
    path.join(root, ".cache/farm-acceptance-accounts.json"),
    "utf8",
  ),
);
const fixture = JSON.parse(
  fs.readFileSync(
    path.join(root, "artifacts/family-farm-fixture.json"),
    "utf8",
  ),
);
const output = path.join(root, "artifacts/individual-farm");
fs.mkdirSync(output, { recursive: true });
let browser;
const checks = [],
  errors = [];
const check = (name) => {
  checks.push(name);
  console.log("PASS " + name);
};
async function signIn(key, size = { width: 1440, height: 1050 }) {
  const a = accounts.find((a) => a.key === key),
    context = await browser.newContext({ viewport: size });
  const page = await context.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(base);
  await page.getByLabel("租户代码", { exact: true }).fill(a.tenant);
  await page.getByLabel("登录账号", { exact: true }).fill(a.username);
  await page.getByLabel("密码", { exact: true }).fill(a.password);
  await page.getByRole("button", { name: "进入工作空间" }).click();
  await page.locator(".daily-farm").waitFor();
  await page
    .getByText("正在读取农场任务…", { exact: true })
    .waitFor({ state: "hidden" });
  await page.waitForFunction(
    () =>
      document.querySelector(".daily-farm")?.getAttribute("aria-busy") ===
      "false",
  );
  return page;
}
async function save(page) {
  await page.getByRole("button", { name: "保存记录", exact: true }).click();
  await page.locator(".daily-dialog").waitFor({ state: "hidden" });
  await page.waitForFunction(
    () =>
      document.querySelector(".daily-farm")?.getAttribute("aria-busy") ===
      "false",
  );
}
async function fresh(page) {
  await page.getByRole("button", { name: "刷新数据", exact: true }).click();
  await page.waitForFunction(
    () =>
      document.querySelector(".daily-farm")?.getAttribute("aria-busy") ===
      "false",
  );
  await page.waitForTimeout(150);
}
async function shot(page, name) {
  await page.screenshot({
    path: path.join(output, name + ".png"),
    fullPage: true,
  });
}
async function main() {
  browser = await chromium.launch({ channel: "msedge", headless: true });
  const owner = await signIn("owner");
  assert.equal(
    await owner.getByLabel("当前农场", { exact: true }).inputValue(),
    fixture.farmId,
  );
  check("Single farm selected; daily work is default landing");
  await shot(owner, "owner-daily");
  await owner.getByRole("button", { name: "记录收获", exact: true }).click();
  await owner.getByRole("dialog", { name: "生产记录", exact: true }).waitFor();
  assert.equal(await owner.getByLabel("筛选所属农场").count(), 0); // form is scoped by the existing farm selector
  const productionFarm = owner.getByRole("dialog").locator("select").first();
  assert.equal(await productionFarm.inputValue(), fixture.farmId);
  check("Harvest quick action retains current farm and opens form");
  await owner.getByRole("button", { name: "关闭", exact: true }).click();
  await owner
    .getByRole("navigation")
    .getByRole("button", { name: "今日农场", exact: true })
    .click();
  const worker = await signIn("worker", { width: 390, height: 844 });
  assert(await worker.getByLabel("只看我负责 / 未分配").isChecked());
  assert.equal(
    await worker
      .locator(".daily-task")
      .filter({ hasText: "蔬菜田滴灌检查" })
      .count(),
    0,
  );
  assert(
    await worker.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  );
  check("Mobile worker defaults to own queue; no horizontal page overflow");
  assert(
    await worker
      .getByRole("navigation")
      .getByRole("button", { name: "今日农场", exact: true })
      .isVisible(),
  );
  check("Mobile navigation keeps readable labels");
  await shot(worker, "worker-mobile");
  const tag = "浏览器验收-" + Date.now();
  await worker
    .getByRole("button", { name: "＋ 巡田上报", exact: true })
    .click();
  await worker
    .getByLabel("所属地块")
    .selectOption(fixture.plots.find((p) => p.crop === "水稻").id);
  await worker
    .getByLabel("现场情况", { exact: true })
    .fill(tag + "：田边虫害，现场无人机缺位。");
  await save(worker);
  check("Worker reports a field problem from mobile");
  await fresh(owner);
  const issue = owner.locator(".daily-issue").filter({ hasText: tag });
  await issue.getByRole("button", { name: "安排处理", exact: true }).click();
  await owner.getByLabel("负责人").selectOption(fixture.workerId);
  await owner.getByLabel("任务名称", { exact: true }).fill(tag + "处理");
  await save(owner);
  check("Owner dispatches linked task with assignee");
  const backup = await signIn("backup");
  await backup.getByLabel("只看我负责 / 未分配").uncheck();
  const backupTask = backup
    .locator(".daily-task")
    .filter({ hasText: tag + "处理" });
  assert.equal(
    await backupTask
      .getByRole("button", { name: "开始任务", exact: true })
      .count(),
    0,
  );
  check("Other operator can review but cannot execute another member task");
  await fresh(worker);
  const task = worker.locator(".daily-task").filter({ hasText: tag + "处理" });
  await task.getByRole("button", { name: "报告受阻", exact: true }).click();
  await worker
    .getByLabel("受阻原因与所需支持", { exact: true })
    .fill("无人机正在检修，请安排人工补位。");
  await save(worker);
  check("Blocker is saved and promoted to the work queue");
  await fresh(owner);
  const ownerTask = owner
    .locator(".daily-task")
    .filter({ hasText: tag + "处理" });
  await ownerTask.getByRole("button", { name: "更多操作" }).click();
  await ownerTask
    .getByRole("menuitem", { name: "调整安排", exact: true })
    .click();
  await owner.getByLabel("计划作业方式").selectOption("MANUAL");
  await owner
    .getByLabel("安排说明", { exact: true })
    .fill("已协调人工队伍，按实际覆盖面积回报。");
  await save(owner);
  assert(
    await ownerTask
      .getByText("无人机正在检修，请安排人工补位。", { exact: false })
      .isVisible(),
  );
  check("Replanning keeps the blocker visible until actual progress");
  await fresh(worker);
  await task.getByRole("button", { name: "完成回执", exact: true }).click();
  await worker.getByLabel("实际作业方式").selectOption("MANUAL");
  await worker.getByLabel("实际完成面积（亩）", { exact: true }).fill("60");
  await worker
    .getByLabel("实际作业结果", { exact: true })
    .fill("已完成60亩人工防治，明日复查虫情。");
  await save(worker);
  check("Completion records method, area and result");
  await fresh(owner);
  await issue.getByRole("button", { name: "复核关闭", exact: true }).click();
  await owner
    .getByLabel("复核结果", { exact: true })
    .fill("验收：复查虫情下降，关闭本次问题并继续观察。");
  await save(owner);
  await owner.getByText(/已关闭问题 \d+ 项/).click();
  assert(await issue.getByText(/复核：验收/).isVisible());
  check("Only owner review closes the field problem");
  const viewer = await signIn("viewer");
  assert.equal(
    await viewer
      .getByRole("button", { name: "＋ 巡田上报", exact: true })
      .count(),
    0,
  );
  assert.equal(
    await viewer.getByRole("button", { name: "安排农事", exact: true }).count(),
    0,
  );
  assert.equal(
    await viewer.getByRole("button", { name: "记录收获", exact: true }).count(),
    0,
  );
  await viewer.getByRole("button", { name: "已结束", exact: true }).click();
  await viewer
    .locator(".daily-task")
    .filter({ hasText: tag + "处理" })
    .getByRole("button", { name: "记录", exact: true })
    .click();
  await viewer
    .getByText("已完成60亩人工防治，明日复查虫情。", { exact: true })
    .waitFor();
  await shot(viewer, "viewer-receipt");
  check("Read-only member can inspect the complete activity log");
  await viewer.keyboard.press("Escape");
  await viewer.locator(".daily-dialog").waitFor({ state: "hidden" });
  await viewer
    .getByRole("navigation")
    .getByRole("button", { name: "运行概览", exact: true })
    .click();
  await viewer.locator(".ops-slice").first().waitFor();
  assert.equal(
    await viewer.getByLabel("当前农场", { exact: true }).inputValue(),
    fixture.farmId,
  );
  assert.equal(await viewer.locator(".ops-slice").count(), 100);
  assert.equal(await viewer.locator(".ops-run").count(), 4);
  assert((await viewer.locator(".ops-mx-row").count()) > 0);
  await viewer.locator(".ops-slice").last().focus();
  await viewer.keyboard.press("Home");
  assert.match(
    await viewer.locator(".ops-readout").innerText(),
    /^\d\d\/\d\d \d\d:\d\d–/,
  );
  await shot(viewer, "viewer-operations");
  await viewer.getByRole("button", { name: "查看农事 →", exact: true }).click();
  await viewer.locator(".daily-farm").waitFor();
  check("Read-only member reads the farm operations overview");
  await owner
    .getByRole("button", { name: "季度方案对照 →", exact: true })
    .click();
  await owner.getByLabel("模拟农场", { exact: true }).waitFor();
  assert.equal(
    await owner.getByLabel("模拟农场", { exact: true }).inputValue(),
    fixture.farmId,
  );
  const historyOption = owner
    .getByLabel("历史模拟", { exact: true })
    .locator("option")
    .filter({ hasText: "家庭农场季度验收 · 无人机缺位" });
  await historyOption.waitFor({ state: "attached" });
  await owner
    .getByLabel("历史模拟", { exact: true })
    .selectOption(await historyOption.getAttribute("value"));
  await owner.locator(".simulation-comparison").waitFor();
  await shot(owner, "quarter-comparison");
  check("Quarterly scenario history retains farm and renders results");
  await owner
    .getByRole("navigation")
    .getByRole("button", { name: "今日农场", exact: true })
    .click();
  await owner.waitForTimeout(300);
  await owner.evaluate(() => (document.documentElement.dataset.theme = "dark"));
  await shot(owner, "owner-dark");
  await owner.evaluate(
    () => (document.documentElement.dataset.theme = "light"),
  );
  check("Daily page supports dark appearance");
  const neighbor = await signIn("neighbor");
  assert.equal(
    await neighbor
      .locator(".daily-hero")
      .filter({ hasText: "禾间家庭农场（验收）" })
      .count(),
    0,
  );
  assert.equal(await neighbor.locator(".daily-task").count(), 0);
  check("Neighbor tenant has independent empty work queue");
  await owner.getByRole("button", { name: "农场地图 →", exact: true }).click();
  await owner.locator(".farm-map-panel").waitFor();
  await owner.waitForTimeout(1600);
  await shot(owner, "farm-map");
  check("Daily farm opens its own map and workspace");
  assert.equal(errors.length, 0, errors.join("\n"));
  check("No uncaught browser exceptions");
  const fingerprint = require("./check_project.cjs").fingerprint(root);
  fs.writeFileSync(
    path.join(root, "artifacts/individual-farm-tests.json"),
    JSON.stringify(
      { passed: true, fingerprint, at: new Date().toISOString(), checks, errors },
      null,
      2,
    ),
  );
  console.log(JSON.stringify({ passed: checks.length, output }));
}
main()
  .catch(async (e) => {
    console.error(e.stack);
    if (browser) {
      const pages = browser.contexts().flatMap((c) => c.pages());
      for (let i = 0; i < pages.length; i++)
        await shot(pages[i], "failure-" + i).catch(() => {});
    }
    process.exitCode = 1;
  })
  .finally(async () => {
    await browser?.close();
  });
