const { test } = require("node:test");
const assert = require("node:assert/strict");

test("diagnosticLabel maps every gateway diagnostic code to Chinese copy, with a safe fallback", async () => {
  const { diagnosticLabel } = await import("../../frontend/src/ai/diagnostics.js");
  for (const code of [
    "OK",
    "NOT_CONFIGURED",
    "AUTH_FAILED",
    "BALANCE_REQUIRED",
    "RATE_LIMITED",
    "TIMEOUT",
    "BUSY",
    "UNAVAILABLE",
    "SERVICE_ERROR",
    "EMPTY_RESPONSE",
  ]) {
    assert.equal(typeof diagnosticLabel(code), "string");
    assert.ok(diagnosticLabel(code).length > 0);
  }
  assert.equal(diagnosticLabel("SOME_UNKNOWN_CODE"), "模型暂不可用");
  assert.equal(diagnosticLabel(undefined), "模型暂不可用");
});
