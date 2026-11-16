import { test } from "node:test";
import assert from "node:assert/strict";
import {
  solarTermAt,
  termNames,
  termCaptions,
  seasonNotes,
  seasons,
  seasonTerms,
  solarTermInYear,
} from "./solar-terms.js";
test("season browsing follows a spring-to-winter cycle, including next January", () => {
  const winter = seasonTerms(2026, "winter");
  assert.deepEqual(
    winter.map((t) => t.name),
    ["立冬", "小雪", "大雪", "冬至", "小寒", "大寒"],
  );
  assert.deepEqual(
    winter.map((t) => t.year),
    [2026, 2026, 2026, 2026, 2027, 2027],
  );
  for (const s of seasons) {
    const terms = seasonTerms(2026, s.id);
    assert.equal(terms.length, 6);
    assert.ok(terms.every((t) => t.season === s.id));
    for (let i = 1; i < terms.length; i++)
      assert.equal(terms[i - 1].next.at, terms[i].at);
  }
  const end = winter.at(-1);
  assert.equal(solarTermAt(end.next.at).name, "立春");
  assert.equal(solarTermAt(winter[0].at - 1).name, "霜降");
});
test("season browsing respects supported years and rejects invalid indices", () => {
  assert.equal(solarTermInYear(2026, 24), null);
  assert.equal(solarTermInYear(2026, -1), null);
  assert.equal(solarTermInYear(2026.5, 2), null);
  assert.deepEqual(seasonTerms(2100, "winter"), []);
  assert.deepEqual(seasonTerms(2026, "invalid"), []);
  assert.ok(seasonTerms(2051, "winter").every((t) => t.next?.at > t.at));
});
test("exact term transition uses an instant, independent of client timezone", () => {
  const at = Date.parse("2026-11-07T17:52:05+08:00");
  assert.equal(solarTermAt(at - 1).name, "霜降");
  assert.equal(solarTermAt(at).name, "立冬");
  assert.equal(solarTermAt(at).progress, 0);
  assert.equal(solarTermAt(at).season, "winter");
  assert.equal(solarTermAt(at).next.name, "小雪");
});
test("January carries the previous winter solstice and handles leap years", () => {
  const january = solarTermAt(Date.parse("2027-01-01T00:00:00Z"));
  assert.equal(january.name, "冬至");
  assert.equal(january.year, 2026);
  assert.equal(january.next.name, "小寒");
  assert.equal(solarTermAt(Date.parse("2028-02-29T12:00:00Z")).name, "雨水");
});
test("unsupported dates do not produce guessed terms; all terms have content", () => {
  assert.equal(solarTermAt(NaN), null);
  assert.equal(solarTermAt(Date.parse("2100-01-01T00:00:00Z")), null);
  assert.equal(termNames.length, 24);
  assert.equal(termCaptions.length, 24);
  for (const notes of Object.values(seasonNotes)) assert.equal(notes.length, 2);
});
