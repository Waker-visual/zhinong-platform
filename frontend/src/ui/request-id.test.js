import test from "node:test";
import assert from "node:assert/strict";
import { requestId } from "./request-id.js";

test("request identifiers work with LAN HTTP crypto that has no randomUUID", () => {
  let calls = 0;
  const cryptoProvider = {
    getRandomValues(bytes) {
      assert.equal(bytes.length, 16);
      calls += 1;
      bytes.set(Array.from({ length: 16 }, (_, index) => index));
      return bytes;
    },
  };
  assert.equal(
    requestId(cryptoProvider),
    "farm-000102030405060708090a0b0c0d0e0f",
  );
  assert.equal(calls, 1);
});

test("new requests receive separate server-compatible identifiers", () => {
  const first = requestId();
  const second = requestId();
  assert.match(first, /^[A-Za-z0-9._:-]{1,80}$/);
  assert.notEqual(first, second);
});
