// Browser regressions for the shared AI refresh control. Used by test_ai_workspace.cjs.
const assert = require('node:assert/strict');

async function settleUi(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function holdResponse(page, pattern) {
  let release, arrived, finished;
  const gate = new Promise(resolve => { release = resolve; });
  const ready = new Promise(resolve => { arrived = resolve; });
  const done = new Promise(resolve => { finished = resolve; });
  const handler = async route => {
    try {
      const response = await route.fetch();
      arrived();
      await gate;
      await route.fulfill({ response });
      finished();
    } catch (error) { arrived(); finished(error); }
  };
  // Keep interception installed until the held response is fulfilled. Expiring the last
  // one-shot route can disable interception while another route is still being held.
  await page.route(pattern, handler);
  return { ready, async release() {
    release();
    const error = await done;
    await page.unroute(pattern, handler);
    if (error) throw error;
  } };
}

async function waitRefreshIdle(page) {
  await page.waitForFunction(() => document.querySelector('.page-heading > button')?.disabled === false);
}

async function dismissSuccess(page) {
  await page.getByText('数据已刷新', { exact: true }).waitFor();
  await page.getByRole('button', { name: '关闭提示', exact: true }).click();
}

async function verifyRefresh(page) {
  await waitRefreshIdle(page);
  const button = page.locator('.page-heading > button');
  assert.equal(await page.getByRole('heading', { name: '农场 AI 助手', exact: true }).count(), 1);
  assert.equal(await page.getByRole('button', { name: '刷新数据', exact: true }).count(), 1);
  assert.equal(await page.getByText('让每一次农事，都有数据依据。', { exact: true }).count(), 0);
  assert.ok((await page.locator('.ai-context').boundingBox()).height < 90);

  // Both orders matter: the shell can finish first, or AI can finish first.
  for (const pattern of ['**/api/ai/analysis?*', '**/api/tasks']) {
    console.log('Checking delayed refresh:', pattern);
    const held = await holdResponse(page, pattern);
    let sends = 0;
    const track = request => { if (request.method() === 'POST' && request.url().endsWith('/stream')) sends++; };
    page.on('request', track);
    try {
      await button.click();
      await held.ready;
      await settleUi(page);
      assert.equal(await button.isDisabled(), true);
      assert.equal(await button.getAttribute('aria-busy'), 'true');
      assert.match(await button.innerText(), /正在加载/);
      assert.equal(await page.getByText('数据已刷新', { exact: true }).count(), 0);
      await page.getByLabel('农事问题', { exact: true }).fill('刷新期间保留的草稿');
      assert.equal(await page.getByRole('button', { name: '发送', exact: true }).isDisabled(), true);
      await page.getByLabel('农事问题', { exact: true }).press('Enter');
      await settleUi(page);
      assert.equal(sends, 0);
    } finally {
      await held.release();
      page.off('request', track);
    }
    await waitRefreshIdle(page);
    await dismissSuccess(page);
    assert.equal(await page.getByLabel('农事问题', { exact: true }).inputValue(), '刷新期间保留的草稿');
    assert.equal(await page.getByRole('button', { name: '发送', exact: true }).isEnabled(), true);
  }

  // An early failure in either group must wait for its other pending requests.
  for (const [failedPath, heldPattern, message] of [
    ['/api/ai/status', '**/api/ai/analysis?*', '模拟模型状态读取失败'],
    ['/api/tasks', '**/api/dashboard', '模拟任务读取失败'],
  ]) {
    console.log('Checking refresh failure:', failedPath);
    const held = await holdResponse(page, heldPattern);
    await page.route('**' + failedPath, route => route.fulfill({
      status: 500, contentType: 'application/json', body: JSON.stringify({ message }),
    }), { times: 1 });
    try {
      const failed = page.waitForResponse(response => response.url().endsWith(failedPath) && response.status() === 500);
      await button.click();
      await Promise.all([held.ready, failed]);
      await settleUi(page);
      assert.equal(await button.isDisabled(), true);
      assert.equal(await page.getByText('数据已刷新', { exact: true }).count(), 0);
    } finally { await held.release(); }
    await waitRefreshIdle(page);
    await page.getByRole('alert').filter({ hasText: message }).waitFor();
    assert.equal(await page.getByText('数据已刷新', { exact: true }).count(), 0);
    await button.click();
    await waitRefreshIdle(page);
    await dismissSuccess(page);
    assert.equal(await page.getByText(message, { exact: true }).count(), 0);
  }
}

async function verifyAnswerProtection(page) {
  const button = page.locator('.page-heading > button');
  await page.getByRole('button', { name: '停止生成', exact: true }).waitFor();
  assert.equal(await button.isDisabled(), true);
  assert.match(await button.getAttribute('title'), /回答生成中/);
  let refreshRequests = 0;
  const track = request => {
    if (/\/api\/(?:ai\/(?:analysis|status|irrigation)|farm-workspaces|tasks)(?:\?|$)/.test(request.url())) refreshRequests++;
  };
  page.on('request', track);
  try {
    // Bypass the disabled control to verify the handler guard as well as the visible state.
    await button.dispatchEvent('click');
    await page.locator('.status-capsule').click();
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await settleUi(page);
    assert.equal(refreshRequests, 0);
    assert.equal(await page.getByRole('button', { name: '停止生成', exact: true }).isVisible(), true);
    assert.equal(await page.getByText('已停止，本次回答未保存', { exact: true }).count(), 0);
  } finally { page.off('request', track); }
}

module.exports = { holdResponse, verifyRefresh, verifyAnswerProtection, waitRefreshIdle };
