const { test } = require("node:test");
const assert = require("node:assert/strict");

test("buildSaveConfigPayload keeps the existing key when apiKey is blank", async () => {
  const { buildSaveConfigPayload } = await import(
    "../../frontend/src/account/model-config.js"
  );
  const payload = buildSaveConfigPayload(
    { url: "https://api.example.com/v1", model: "deepseek-flash", apiKey: "" },
    false,
  );
  assert.equal(payload.url, "https://api.example.com/v1");
  assert.equal(payload.model, "deepseek-flash");
  assert.equal(payload.clearApiKey, false);
  assert.ok(!("apiKey" in payload), "blank apiKey must be omitted so the backend keeps the saved key");
});

test("buildSaveConfigPayload sends a typed key and trims url/model", async () => {
  const { buildSaveConfigPayload } = await import(
    "../../frontend/src/account/model-config.js"
  );
  const payload = buildSaveConfigPayload(
    { url: "  https://api.example.com/v1  ", model: " deepseek-flash ", apiKey: "sk-test-placeholder" },
    false,
  );
  assert.equal(payload.url, "https://api.example.com/v1");
  assert.equal(payload.model, "deepseek-flash");
  assert.equal(payload.apiKey, "sk-test-placeholder");
});

test("buildSaveConfigPayload sets clearApiKey and drops any typed key", async () => {
  const { buildSaveConfigPayload } = await import(
    "../../frontend/src/account/model-config.js"
  );
  const payload = buildSaveConfigPayload(
    { url: "", model: "", apiKey: "sk-should-be-ignored" },
    true,
  );
  assert.equal(payload.clearApiKey, true);
  assert.ok(!("apiKey" in payload), "clearApiKey must win over a stray typed key");
});

test("buildConfigOverridePayload omits blank fields so the backend falls back to saved config", async () => {
  const { buildConfigOverridePayload } = await import(
    "../../frontend/src/account/model-config.js"
  );
  assert.deepEqual(buildConfigOverridePayload({ url: "", model: "", apiKey: "" }), {});
  assert.deepEqual(
    buildConfigOverridePayload({ url: " https://x.invalid ", model: "", apiKey: "sk-test-placeholder" }),
    { url: "https://x.invalid", apiKey: "sk-test-placeholder" },
  );
});

test("mergeModelIds appends new ids once, preserving existing order", async () => {
  const { mergeModelIds } = await import(
    "../../frontend/src/account/model-config.js"
  );
  const merged = mergeModelIds(
    ["deepseek-flash"],
    ["deepseek-flash", "deepseek-v4-pro", "  ", "deepseek-v4-pro"],
  );
  assert.deepEqual(merged, ["deepseek-flash", "deepseek-v4-pro"]);
});
