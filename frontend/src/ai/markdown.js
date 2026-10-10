// 极简、安全的 Markdown 解析器：只服务于农场 AI 助手的回答渲染（AssistantText.vue），不是通用
// Markdown 实现。设计目标：
//   1. 不引入新依赖，不使用 v-html——这里只产出一棵纯数据的块级 AST，真正的渲染（生成 Vue
//      vnode）在 AssistantText.vue 里完成，所有文本内容始终走 Vue 的文本节点/属性绑定，
//      天然转义，任何输入（包括字面的 "<script>...</script>"）都只会被当成文本显示。
//   2. 流式友好：助手回答是边生成边追加的，每次新增字符后都会用当前已累积的全部文本重新调用
//      parseMarkdown()。还没闭合的表格分隔行、还没打完的粗体标记等，只会被当成普通段落文本，
//      不会抛异常、也不会把后续已经完整的内容渲染错位。
//
// 支持的块：heading（显示为 h3/h4/h5 三档，不保留 h1/h2/h6 的原始字号）、paragraph、
// ul/ol（支持一层缩进嵌套）、table（表头/对齐/数据行）、hr、blockquote（递归块）、
// code（围栏代码块，不做内联解析）。
// 支持的内联：bold（**text**）、inline code（`code`）、纯文本。

const HEADING_RE = /^(#{1,6})\s+(.*)$/;
const HR_RE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/;
const UL_RE = /^(\s*)[-*+]\s+(.*)$/;
const OL_RE = /^(\s*)(\d+)[.)]\s+(.*)$/;
const BLOCKQUOTE_RE = /^ {0,3}>\s?(.*)$/;
const FENCE_RE = /^ {0,3}(`{3,}|~{3,})\s*(\S*)\s*$/;
const TABLE_DELIMITER_RE = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/;

function isBlank(line) {
  return /^\s*$/.test(line);
}

// 把一行按表格分隔符 "|" 拆成单元格文本，去掉首尾的空单元格（由行首/行尾的 "|" 产生）。
function splitTableRow(line) {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|") && !s.endsWith("\\|")) s = s.slice(0, -1);
  const cells = [];
  let current = "";
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === "\\" && s[i + 1] === "|") {
      current += "|";
      i++;
      continue;
    }
    if (ch === "|") {
      cells.push(current.trim());
      current = "";
      continue;
    }
    current += ch;
  }
  cells.push(current.trim());
  return cells;
}

function parseAlignRow(line) {
  return splitTableRow(line).map((cell) => {
    const left = cell.startsWith(":");
    const right = cell.endsWith(":");
    if (left && right) return "center";
    if (right) return "right";
    if (left) return "left";
    return null;
  });
}

// 内联解析：**bold**、`code`、纯文本。两种标记都要求闭合在同一段文本内（不跨行），
// 没闭合的标记（流式输出中间状态）原样当作文本，不报错也不吞字符。
export function parseInline(text) {
  const tokens = [];
  let rest = String(text || "");
  const pattern = /(\*\*([^*\n]+)\*\*|`([^`\n]+)`)/;
  while (rest.length) {
    const m = pattern.exec(rest);
    if (!m) {
      tokens.push({ type: "text", value: rest });
      break;
    }
    if (m.index > 0) tokens.push({ type: "text", value: rest.slice(0, m.index) });
    if (m[2] !== undefined) tokens.push({ type: "bold", value: [{ type: "text", value: m[2] }] });
    else tokens.push({ type: "code", value: m[3] });
    rest = rest.slice(m.index + m[0].length);
  }
  return tokens;
}

function headingDisplayLevel(rawLevel) {
  // markdown 的 1-6 级标题折叠进聊天气泡里只保留三档视觉权重：1-3 级都显示成 h3（聊天里不需要
  // 页面级大标题），4 级是 h4，5-6 级是 h5。
  if (rawLevel <= 3) return 3;
  if (rawLevel === 4) return 4;
  return 5;
}

// 从 index 开始尝试把若干行解析成一个表格块：要求当前行像表头（含 "|"），下一行是分隔行
// （由 "-"/":" 和 "|" 组成）。不满足就返回 null，调用方把当前行当普通段落处理。
function tryParseTable(lines, index) {
  const header = lines[index];
  const delimiter = lines[index + 1];
  if (header === undefined || delimiter === undefined) return null;
  if (!header.includes("|") || !TABLE_DELIMITER_RE.test(delimiter) || !delimiter.includes("-")) return null;
  const headerCells = splitTableRow(header).map(parseInline);
  const align = parseAlignRow(delimiter);
  const rows = [];
  let next = index + 2;
  while (next < lines.length && lines[next].includes("|") && !isBlank(lines[next])) {
    rows.push(splitTableRow(lines[next]).map(parseInline));
    next++;
  }
  return { block: { type: "table", header: headerCells, align, rows }, next };
}

// 列表项的缩进层级：ul/ol 正则捕获的前导空白长度，>=2 个空格视为嵌套进上一项。
function listItemDepth(indent) {
  return Math.floor((indent || "").length / 2);
}

function tryParseList(lines, index, re, ordered) {
  const items = []; // 扁平收集 {depth, inline, children:[]}，最后再折叠成树
  let next = index;
  const flat = [];
  while (next < lines.length) {
    const m = re.exec(lines[next]);
    if (!m) break;
    const indent = m[1];
    const content = ordered ? m[3] : m[2];
    flat.push({ depth: listItemDepth(indent), inline: parseInline(content), children: [] });
    next++;
  }
  if (!flat.length) return null;
  // 把扁平数组折叠成树：depth 比上一项大的挂到上一项的 children 下面（只支持这种最常见的
  // “逐级递增”嵌套写法，markdown 聊天回答里不会出现更复杂的乱序缩进）。
  const stack = [{ depth: -1, children: items }];
  for (const item of flat) {
    while (stack.length > 1 && stack[stack.length - 1].depth >= item.depth) stack.pop();
    stack[stack.length - 1].children.push(item);
    stack.push(item);
  }
  return { block: { type: ordered ? "ol" : "ul", items }, next };
}

function tryParseBlockquote(lines, index) {
  const collected = [];
  let next = index;
  while (next < lines.length) {
    const m = BLOCKQUOTE_RE.exec(lines[next]);
    if (!m) break;
    collected.push(m[1]);
    next++;
  }
  if (!collected.length) return null;
  return { block: { type: "blockquote", blocks: parseMarkdown(collected.join("\n")) }, next };
}

function tryParseFence(lines, index) {
  const open = FENCE_RE.exec(lines[index]);
  if (!open) return null;
  const fence = open[1][0];
  const fenceLen = open[1].length;
  const lang = open[2] || "";
  const body = [];
  let next = index + 1;
  let closed = false;
  while (next < lines.length) {
    const closeMatch = new RegExp(`^ {0,3}${fence}{${fenceLen},}\\s*$`).test(lines[next]);
    if (closeMatch) {
      closed = true;
      next++;
      break;
    }
    body.push(lines[next]);
    next++;
  }
  // 还没等到闭合围栏（流式输出中间状态）：把已经收到的内容原样当代码块显示，不等闭合——
  // 代码块本来就应该原样展示，提前渲染不会造成视觉上的错位。
  void closed;
  return { block: { type: "code", lang, text: body.join("\n") }, next };
}

/** 把助手回答文本解析成块级 AST（数组）。纯函数、无副作用，可以安全地在每次流式增量后重新调用。 */
export function parseMarkdown(text) {
  const lines = String(text || "").replace(/\r\n/g, "\n").split("\n");
  const blocks = [];
  let i = 0;
  let paragraphBuffer = [];
  const flushParagraph = () => {
    if (!paragraphBuffer.length) return;
    const joined = paragraphBuffer.join("\n");
    if (joined.trim()) blocks.push({ type: "paragraph", inline: parseInline(joined) });
    paragraphBuffer = [];
  };
  while (i < lines.length) {
    const line = lines[i];
    if (isBlank(line)) {
      flushParagraph();
      i++;
      continue;
    }
    const fence = tryParseFence(lines, i);
    if (fence) {
      flushParagraph();
      blocks.push(fence.block);
      i = fence.next;
      continue;
    }
    if (HR_RE.test(line)) {
      flushParagraph();
      blocks.push({ type: "hr" });
      i++;
      continue;
    }
    const heading = HEADING_RE.exec(line);
    if (heading) {
      flushParagraph();
      blocks.push({ type: "heading", level: headingDisplayLevel(heading[1].length), inline: parseInline(heading[2]) });
      i++;
      continue;
    }
    const table = tryParseTable(lines, i);
    if (table) {
      flushParagraph();
      blocks.push(table.block);
      i = table.next;
      continue;
    }
    const quote = tryParseBlockquote(lines, i);
    if (quote) {
      flushParagraph();
      blocks.push(quote.block);
      i = quote.next;
      continue;
    }
    const ul = UL_RE.test(line) ? tryParseList(lines, i, UL_RE, false) : null;
    if (ul) {
      flushParagraph();
      blocks.push(ul.block);
      i = ul.next;
      continue;
    }
    const ol = OL_RE.test(line) ? tryParseList(lines, i, OL_RE, true) : null;
    if (ol) {
      flushParagraph();
      blocks.push(ol.block);
      i = ol.next;
      continue;
    }
    paragraphBuffer.push(line);
    i++;
  }
  flushParagraph();
  return blocks;
}

// 朗读/复制用的纯文本：去掉所有 Markdown 语法标记，只保留可读内容，表格用空格分隔单元格、
// 换行分隔行，列表项前缀用中文顿号/序号代替符号标记。供 AiMessageActions 使用。
export function markdownToPlainText(text) {
  const blocks = parseMarkdown(text);
  const inlineText = (tokens) => (tokens || []).map((t) => (t.type === "bold" ? inlineText(t.value) : t.value)).join("");
  const lines = [];
  const renderBlocks = (list) => {
    for (const block of list) {
      switch (block.type) {
        case "heading":
        case "paragraph":
          lines.push(inlineText(block.inline));
          break;
        case "hr":
          break;
        case "code":
          lines.push(block.text);
          break;
        case "blockquote":
          renderBlocks(block.blocks);
          break;
        case "ul":
        case "ol": {
          const renderItems = (items) => {
            for (const item of items) {
              lines.push(inlineText(item.inline));
              if (item.children?.length) renderItems(item.children);
            }
          };
          renderItems(block.items);
          break;
        }
        case "table": {
          lines.push(block.header.map(inlineText).join(" "));
          for (const row of block.rows) lines.push(row.map(inlineText).join(" "));
          break;
        }
        default:
          break;
      }
    }
  };
  renderBlocks(blocks);
  return lines.join("\n").trim();
}
