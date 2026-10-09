// 滚动锚定的纯判定逻辑：不依赖浏览器或框架，便于单测。
// 规则见 docs/superpowers/specs/2026-10-09-ai-agent-interface-design.md 的“滚动规则”。

// 滚动容器距离底部不超过 threshold（默认 72px）时，视为“贴底”，应继续跟随新内容。
export function isNearBottom(scrollTop, scrollHeight, clientHeight, threshold = 72) {
  const distance = scrollHeight - scrollTop - clientHeight;
  return distance <= threshold;
}
