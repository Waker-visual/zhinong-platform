// 浏览器验收共用：侧栏只列一级模块，页面在模块页签里切换。按页面标题进入页面。
// 与 frontend/src/catalog.js 的 modules 保持一致；窄屏下侧栏隐藏，改用底部标签栏或命令面板。
const MODULE_OF = {
  今日农场: '今日农场',
  农场概览: '农场与地块', 农场档案: '农场与地块', 地块管理: '农场与地块', 种植计划: '农场与地块',
  农事任务: '农事与生产', 生产记录: '农事与生产',
  运行概览: '设备与监测', 设备台账: '设备与监测', 田间实景: '设备与监测',
  '农场 AI 助手': '智能分析', 经营模拟: '智能分析',
  成员权限: '管理', 操作日志: '管理',
};

async function openPage(page, title) {
  const module = MODULE_OF[title];
  if (!module) throw new Error(`未知页面：${title}`);
  const sidebar = page.locator('#app-sidebar');
  if (await sidebar.isVisible()) {
    await sidebar.getByRole('button', { name: module, exact: true }).click();
    if (module !== title) await page.locator('.module-tabs').getByRole('button', { name: title, exact: true }).click();
    return;
  }
  const shortcut = page.locator('nav.tabbar').getByRole('button', { name: title, exact: true });
  if (await shortcut.count()) return shortcut.click();
  await page.locator('nav.tabbar').getByRole('button', { name: '更多', exact: true }).click();
  await page.getByRole('dialog').getByRole('option', { name: new RegExp(title) }).first().click();
}

module.exports = { MODULE_OF, openPage };
