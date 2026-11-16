import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MAP_STYLE_KEY,
  cropTexture,
  cropPatternMarkup,
  readMapMode,
  saveMapMode,
  readDiagramStyle,
  saveDiagramStyle,
} from "./pastoral-map.js";
test("ordinary schematic preference never changes the geographic base map", () => {
  const storage = memory();
  saveMapMode("SATELLITE", storage);
  saveDiagramStyle(true, storage);
  assert.equal(readMapMode(storage), "SATELLITE");
  assert.equal(readDiagramStyle(storage), true);
  saveDiagramStyle(false, storage);
  assert.equal(readMapMode(storage), "SATELLITE");
  assert.equal(readDiagramStyle(storage), false);
  saveMapMode("PASTORAL", storage);
  assert.equal(readDiagramStyle(storage), false);
  assert.equal(
    readDiagramStyle({
      getItem() {
        throw Error("blocked");
      },
    }),
    false,
  );
});
test("explicit farm map configuration is respected until a user chooses a preference", () => {
  const storage = memory();
  assert.equal(readMapMode(storage, "STREET"), "STREET");
  assert.equal(readMapMode(storage, "PLAN"), "PLAN");
  assert.equal(readMapMode(storage, "invalid"), "SATELLITE");
  saveMapMode("PASTORAL", storage);
  assert.equal(readMapMode(storage, "STREET"), "PASTORAL");
});
import { readGroups, saveGroups } from "../ui/navigation-groups.js";
import { sceneryLayout, sceneryMarkup } from "./pastoral-scenery.js";
test("scenery is deterministic and never overlaps the protected data rectangle", () => {
  for (const [width, height] of [
    [800, 400],
    [12, 590],
    [800, 2],
  ]) {
    const items = sceneryLayout(width, height);
    assert.deepEqual(items, sceneryLayout(width, height));
    assert.ok(items.some((item) => item.kind === "house"));
    assert.ok(items.some((item) => item.kind === "tree"));
    for (const item of items) {
      assert.ok(
        item.x + item.width < 0 ||
          item.x > width ||
          item.y + item.height < 0 ||
          item.y > height,
      );
    }
    assert.ok(!sceneryMarkup(width, height).includes("NaN"));
  }
  assert.deepEqual(sceneryLayout(NaN, 400), []);
  assert.equal(sceneryMarkup(0, 400), "");
});
const memory = () => {
  const values = new Map();
  return {
    getItem: (key) => values.get(key),
    setItem: (key, value) => values.set(key, value),
  };
};
test("satellite map defaults safely and preserves every ordinary-map choice", () => {
  const storage = memory();
  assert.equal(readMapMode(storage), "SATELLITE");
  storage.setItem("zhihe-map-style", "PASTORAL");
  assert.equal(readMapMode(storage), "SATELLITE");
  for (const mode of ["STREET", "SATELLITE", "PLAN", "PASTORAL"]) {
    saveMapMode(mode, storage);
    assert.equal(readMapMode(storage), mode);
  }
  storage.setItem(MAP_STYLE_KEY, "unknown");
  assert.equal(readMapMode(storage), "SATELLITE");
  assert.equal(
    readMapMode({
      getItem() {
        throw Error("blocked");
      },
    }),
    "SATELLITE",
  );
});
test("crop textures never interpolate crop names as SVG markup", () => {
  assert.equal(cropTexture("玉米"), "grain");
  assert.equal(cropTexture("苹果"), "orchard");
  assert.equal(cropTexture("番茄"), "fruit");
  assert.equal(cropTexture("生菜"), "leaf");
  assert.ok(
    !cropPatternMarkup("safe-id", "<script>alert(1)</script>").includes(
      "<script>",
    ),
  );
});
test("navigation restores only boolean collapse preferences, tolerating corrupt storage", () => {
  const storage = memory();
  const groups = readGroups(storage);
  groups["日常作业"] = false;
  saveGroups(groups, storage);
  assert.equal(readGroups(storage)["日常作业"], false);
  assert.equal(readGroups(storage)["农场与设备"], true);
  storage.setItem("zhihe-navigation-groups", "{broken");
  assert.equal(readGroups(storage)["日常作业"], true);
  storage.setItem("zhihe-navigation-groups", "null");
  assert.equal(readGroups(storage)["日常作业"], true);
});
