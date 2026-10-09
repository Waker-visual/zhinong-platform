<script>
// 渲染农场 AI 助手回答的 Markdown 子集：标题(h3-h5)、段落、有序/无序列表（含一层嵌套）、
// GFM 表格（横向可滚动容器，避免 375px 页面被撑开）、引用块、围栏代码块，内联加粗/行内代码。
// 不使用 v-html：parseMarkdown() 产出纯数据 AST（见 ./markdown.js），这里用渲染函数把它转换成
// Vue vnode，所有文本都走 Vue 自己的文本节点，天然转义——哪怕回答里真的出现字面的恶意脚本标签
// 文本，也只会被当成文本显示，绝不会被当成标签解析或执行（见 markdown.test.js 的对应用例）。
import { h } from "vue";
import { parseMarkdown } from "./markdown.js";

function renderInline(tokens) {
  return (tokens || []).map((token) => {
    if (token.type === "bold") return h("strong", renderInline(token.value));
    if (token.type === "code") return h("code", { class: "ai-md-code-inline" }, token.value);
    return token.value;
  });
}

function renderListItems(items, ordered) {
  return items.map((item) =>
    h("li", [
      h("span", renderInline(item.inline)),
      item.children?.length ? h(ordered ? "ol" : "ul", { class: "ai-md-list ai-md-list-nested" }, renderListItems(item.children, ordered)) : null,
    ]),
  );
}

function renderBlock(block, key) {
  switch (block.type) {
    case "heading":
      return h(`h${block.level}`, { key, class: "ai-md-heading" }, renderInline(block.inline));
    case "paragraph":
      return h("p", { key, class: "ai-md-paragraph" }, renderInline(block.inline));
    case "hr":
      return h("hr", { key, class: "ai-md-hr" });
    case "blockquote":
      return h("blockquote", { key, class: "ai-md-blockquote" }, renderBlocks(block.blocks));
    case "code":
      return h("pre", { key, class: "ai-md-code-block" }, h("code", block.text));
    case "ul":
      return h("ul", { key, class: "ai-md-list" }, renderListItems(block.items, false));
    case "ol":
      return h("ol", { key, class: "ai-md-list" }, renderListItems(block.items, true));
    case "table":
      return h("div", { key, class: "ai-md-table-wrap" }, [
        h("table", { class: "ai-md-table" }, [
          h(
            "thead",
            h(
              "tr",
              block.header.map((cell, i) =>
                h("th", { key: i, style: block.align[i] ? { textAlign: block.align[i] } : null }, renderInline(cell)),
              ),
            ),
          ),
          h(
            "tbody",
            block.rows.map((row, r) =>
              h(
                "tr",
                { key: r },
                row.map((cell, i) => h("td", { key: i, style: block.align[i] ? { textAlign: block.align[i] } : null }, renderInline(cell))),
              ),
            ),
          ),
        ]),
      ]);
    default:
      return null;
  }
}

function renderBlocks(blocks) {
  return blocks.map((block, index) => renderBlock(block, index));
}

export default {
  name: "AssistantText",
  props: { text: { type: String, default: "" } },
  render() {
    return h("div", { class: "ai-md" }, renderBlocks(parseMarkdown(this.text)));
  },
};
</script>
