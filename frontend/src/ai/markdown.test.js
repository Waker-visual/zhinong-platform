import test from "node:test";
import assert from "node:assert/strict";
import { parseMarkdown, parseInline, markdownToPlainText } from "./markdown.js";

test("headings collapse into three display levels (h3/h4/h5)", () => {
  const blocks = parseMarkdown("# 一级\n\n## 二级\n\n### 三级\n\n#### 四级\n\n##### 五级\n\n###### 六级");
  assert.deepEqual(
    blocks.map((b) => b.level),
    [3, 3, 3, 4, 5, 5],
  );
  assert.equal(blocks[0].inline[0].value, "一级");
});

test("paragraphs are separated by blank lines and bold inline tokens parse", () => {
  const blocks = parseMarkdown("第一段 **重点** 内容。\n\n第二段。");
  assert.equal(blocks.length, 2);
  assert.equal(blocks[0].type, "paragraph");
  assert.deepEqual(blocks[0].inline, [
    { type: "text", value: "第一段 " },
    { type: "bold", value: [{ type: "text", value: "重点" }] },
    { type: "text", value: " 内容。" },
  ]);
  assert.equal(blocks[1].inline[0].value, "第二段。");
});

test("unordered and ordered lists, including one level of nesting", () => {
  const blocks = parseMarkdown("- 任务一\n  - 子任务 A\n  - 子任务 B\n- 任务二\n\n1. 第一步\n2. 第二步");
  assert.equal(blocks[0].type, "ul");
  assert.equal(blocks[0].items.length, 2);
  assert.equal(blocks[0].items[0].children.length, 2);
  assert.equal(blocks[0].items[0].children[0].inline[0].value, "子任务 A");
  assert.equal(blocks[0].items[1].inline[0].value, "任务二");
  assert.equal(blocks[1].type, "ol");
  assert.equal(blocks[1].items.length, 2);
  assert.equal(blocks[1].items[1].inline[0].value, "第二步");
});

test("GFM tables parse header, alignment and rows, with bold inside cells", () => {
  const text = ["| 任务 | 优先级 | 状态 |", "| --- | :---: | ---: |", "| 灌溉检查 | **高** | 待执行 |", "| 施肥 | 中 | 已完成 |"].join("\n");
  const blocks = parseMarkdown(text);
  assert.equal(blocks.length, 1);
  const table = blocks[0];
  assert.equal(table.type, "table");
  assert.deepEqual(table.header.map((c) => c[0].value), ["任务", "优先级", "状态"]);
  assert.deepEqual(table.align, [null, "center", "right"]);
  assert.equal(table.rows.length, 2);
  assert.deepEqual(table.rows[0][1], [{ type: "bold", value: [{ type: "text", value: "高" }] }]);
  assert.equal(table.rows[1][0][0].value, "施肥");
});

test("a line with pipes but no delimiter row is not mistaken for a table", () => {
  const blocks = parseMarkdown("| 这不是表格 | 只是一行文字 |");
  assert.equal(blocks.length, 1);
  assert.equal(blocks[0].type, "paragraph");
});

test("partial/streaming input never throws and degrades gracefully", () => {
  const partialInputs = [
    "| 任务 | 状态",
    "| 任务 | 状态 |\n| --- |",
    "**粗体没有闭合",
    "`代码没有闭合",
    "```js\nconst x = 1;",
    "- 列表项没有换行就结束",
  ];
  for (const input of partialInputs) {
    assert.doesNotThrow(() => parseMarkdown(input));
  }
});

test("horizontal rule and blockquote blocks", () => {
  const blocks = parseMarkdown("> 引用的第一行\n> 引用的第二行\n\n---\n\n正文段落");
  assert.equal(blocks[0].type, "blockquote");
  assert.equal(blocks[0].blocks.length, 1);
  assert.equal(blocks[0].blocks[0].inline.map((t) => t.value).join(""), "引用的第一行\n引用的第二行");
  assert.equal(blocks[1].type, "hr");
  assert.equal(blocks[2].type, "paragraph");
});

test("fenced code blocks are not inline-parsed (markdown inside stays literal)", () => {
  const blocks = parseMarkdown("```\n**not bold** `not code`\n```");
  assert.equal(blocks[0].type, "code");
  assert.equal(blocks[0].text, "**not bold** `not code`");
});

test("a malicious <script> tag never becomes markup — it stays a plain text token", () => {
  const blocks = parseMarkdown('这是危险内容：<script>alert(1)</script> 结束。');
  assert.equal(blocks[0].type, "paragraph");
  const joined = blocks[0].inline.map((t) => t.value).join("");
  assert.ok(joined.includes("<script>alert(1)</script>"));
  // 每个 token 都只是纯文本，没有任何 "html"/"raw" 类型的 token 能让渲染层误用 v-html。
  for (const token of blocks[0].inline) assert.ok(["text", "bold", "code"].includes(token.type));
});

test("parseInline handles unmatched markers as literal text", () => {
  assert.deepEqual(parseInline("无标记文本"), [{ type: "text", value: "无标记文本" }]);
  assert.deepEqual(parseInline("只有一个星号 * 不是加粗"), [{ type: "text", value: "只有一个星号 * 不是加粗" }]);
});

test("markdownToPlainText strips markdown syntax for copy/speech", () => {
  const text = "# 标题\n\n**重点**一段话。\n\n- 项目一\n- 项目二\n\n| a | b |\n| --- | --- |\n| 1 | 2 |";
  const plain = markdownToPlainText(text);
  assert.ok(!plain.includes("**"));
  assert.ok(!plain.includes("|"));
  assert.ok(!plain.includes("#"));
  assert.ok(plain.includes("标题"));
  assert.ok(plain.includes("重点一段话。"));
  assert.ok(plain.includes("项目一"));
  assert.ok(plain.includes("1 2"));
});
