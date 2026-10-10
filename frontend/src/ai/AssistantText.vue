<script>
// 渲染农场 AI 助手回答的 Markdown 子集：标题(h3-h5)、段落、有序/无序列表（含一层嵌套）、
// GFM 表格（横向可滚动容器，避免 375px 页面被撑开）、引用块、围栏代码块，内联加粗/行内代码。
// 不使用 v-html：parseMarkdown() 产出纯数据 AST（见 ./markdown.js），这里用渲染函数把它转换成
// Vue vnode，所有文本都走 Vue 自己的文本节点，天然转义——哪怕回答里真的出现字面的恶意脚本标签
// 文本，也只会被当成文本显示，绝不会被当成标签解析或执行（见 markdown.test.js 的对应用例）。
import { h } from "vue";
import { parseMarkdown } from "./markdown.js";

// 流式输出时把正文切成词段，每段一个 <span>：新到的词段挂载时淡入，已有词段由 Vue 按位置原地
// 复用（同类型节点只更新文本），动画不会重播。回答落库后走非流式分支，恢复成普通文本节点。
const segmenter = typeof Intl !== "undefined" && Intl.Segmenter ? new Intl.Segmenter("zh-CN", { granularity: "word" }) : null;
function streamSegments(text) {
  if (segmenter) return Array.from(segmenter.segment(text), (part) => part.segment);
  return text.match(/[\u4e00-\u9fff]{1,2}|\s+|[^\s\u4e00-\u9fff]+/g) || [text];
}

function renderInline(tokens, streaming) {
  return (tokens || []).flatMap((token) => {
    if (token.type === "bold") return h("strong", renderInline(token.value, streaming));
    if (token.type === "code") return h("code", { class: "ai-md-code-inline" }, token.value);
    if (!streaming) return token.value;
    return streamSegments(token.value).map((segment) => h("span", { class: "ai-stream-seg" }, segment));
  });
}

function renderListItems(items, ordered, streaming) {
  return items.map((item) =>
    h("li", [
      h("span", renderInline(item.inline, streaming)),
      item.children?.length ? h(ordered ? "ol" : "ul", { class: "ai-md-list ai-md-list-nested" }, renderListItems(item.children, ordered, streaming)) : null,
    ]),
  );
}

function renderBlock(block, key, streaming) {
  switch (block.type) {
    case "heading":
      return h(`h${block.level}`, { key, class: "ai-md-heading" }, renderInline(block.inline, streaming));
    case "paragraph":
      return h("p", { key, class: "ai-md-paragraph" }, renderInline(block.inline, streaming));
    case "hr":
      return h("hr", { key, class: "ai-md-hr" });
    case "blockquote":
      return h("blockquote", { key, class: "ai-md-blockquote" }, renderBlocks(block.blocks, streaming));
    case "code":
      return h("pre", { key, class: "ai-md-code-block" }, h("code", block.text));
    case "ul":
      return h("ul", { key, class: "ai-md-list" }, renderListItems(block.items, false, streaming));
    case "ol":
      return h("ol", { key, class: "ai-md-list" }, renderListItems(block.items, true, streaming));
    case "table":
      return h("div", { key, class: "ai-md-table-wrap" }, [
        h("table", { class: "ai-md-table" }, [
          h(
            "thead",
            h(
              "tr",
              block.header.map((cell, i) =>
                h("th", { key: i, style: block.align[i] ? { textAlign: block.align[i] } : null }, renderInline(cell, streaming)),
              ),
            ),
          ),
          h(
            "tbody",
            block.rows.map((row, r) =>
              h(
                "tr",
                { key: r },
                row.map((cell, i) => h("td", { key: i, style: block.align[i] ? { textAlign: block.align[i] } : null }, renderInline(cell, streaming))),
              ),
            ),
          ),
        ]),
      ]);
    default:
      return null;
  }
}

function renderBlocks(blocks, streaming) {
  return blocks.map((block, index) => renderBlock(block, index, streaming));
}

export default {
  name: "AssistantText",
  props: { text: { type: String, default: "" }, streaming: { type: Boolean, default: false } },
  render() {
    return h("div", { class: "ai-md" }, renderBlocks(parseMarkdown(this.text), this.streaming));
  },
};
</script>
