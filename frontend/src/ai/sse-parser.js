// 纯 SSE（Server-Sent Events）增量解析器：不依赖浏览器 API 或 api.js，
// 可以直接喂入 fetch ReadableStream 解码出的文本片段，正确处理跨 chunk 断行、
// `\r\n` 行尾和多行 data: 字段拼接（标准 SSE 语义）。
// 之所以单独成一个模块：api.js 在模块顶层读取浏览器 sessionStorage，
// Node 测试环境直接 import 会立即抛错；这个解析器不依赖那些全局对象，可以被单测直接导入。

// 创建一个增量解析器：每次收到新的文本片段时调用 feed(chunk)，
// 返回这次调用解析出的完整事件数组（可能为空）。未结束的半行会留在内部缓冲区，
// 等下一次 feed 时拼接，不会丢失或重复。
export function createSseParser() {
  let buffer = "";
  let dataLines = [];
  let eventName = null;

  function flush(events) {
    if (dataLines.length) events.push({ event: eventName, data: dataLines.join("\n") });
    dataLines = [];
    eventName = null;
  }

  function feed(chunk) {
    buffer += chunk;
    const events = [];
    let newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
      let line = buffer.slice(0, newlineIndex);
      buffer = buffer.slice(newlineIndex + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (line === "") {
        flush(events);
        continue;
      }
      if (line.startsWith(":")) continue; // SSE 注释行，忽略
      const colon = line.indexOf(":");
      const field = colon === -1 ? line : line.slice(0, colon);
      let value = colon === -1 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "data") dataLines.push(value);
      else if (field === "event") eventName = value;
      // id/retry 字段当前协议未使用，忽略即可
    }
    return events;
  }

  // 流结束时调用：把缓冲区里没有以空行结束的最后一个事件也吐出来（容错，正规 SSE 不需要）。
  function end() {
    const events = [];
    flush(events);
    return events;
  }

  return { feed, end };
}
