// 模型服务配置表单 -> 请求体的转换：密钥留空表示保持不变，仅在显式清除时发送 clearApiKey。
// 抽成纯函数方便 node:test 直接验证，不依赖 Vue 组件渲染（见 tools/tests/model-config.test.cjs）。

function trimOrEmpty(value) {
  return typeof value === "string" ? value.trim() : "";
}

/** 保存配置请求体：url/model 留空表示保持不变；apiKey 留空且不清除时不随请求发送（后端据此保留原密钥）。 */
export function buildSaveConfigPayload({ url, model, apiKey } = {}, clearApiKey = false) {
  const payload = {
    url: trimOrEmpty(url),
    model: trimOrEmpty(model),
    clearApiKey: !!clearApiKey,
  };
  if (!clearApiKey && apiKey) payload.apiKey = apiKey;
  return payload;
}

/** 测试连接 / 拉取模型列表的请求体：字段留空则省略，交由后端使用已保存配置。 */
export function buildConfigOverridePayload({ url, model, apiKey } = {}) {
  const payload = {};
  const u = trimOrEmpty(url);
  const m = trimOrEmpty(model);
  if (u) payload.url = u;
  if (m) payload.model = m;
  if (apiKey) payload.apiKey = apiKey;
  return payload;
}

/** 合并拉取到的模型 id 与现有选项，保持顺序、去重；用于“从服务获取”后预览将新增的项。 */
export function mergeModelIds(existing = [], fetched = []) {
  const merged = [...existing];
  for (const raw of fetched) {
    const id = typeof raw === "string" ? raw.trim() : "";
    if (id && !merged.includes(id)) merged.push(id);
  }
  return merged;
}
