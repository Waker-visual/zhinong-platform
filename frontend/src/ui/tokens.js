// 脚本绘制的颜色（ECharts、Leaflet）写成 "var(--令牌)"，绘制前按所在元素解析成实际颜色，
// 因而大屏模式、地图面板等深色区域里的图形会取深色区域的值。
const TOKEN = /^var\((--[\w-]+)\)$/;

export function resolveTokens(value, style) {
  if (typeof value === "string") {
    const match = value.match(TOKEN);
    return match ? style.getPropertyValue(match[1]).trim() || value : value;
  }
  if (Array.isArray(value)) return value.map((v) => resolveTokens(v, style));
  if (value && Object.getPrototypeOf(value) === Object.prototype)
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, resolveTokens(v, style)]),
    );
  return value;
}

export function tokenColor(value, element = document.documentElement) {
  return resolveTokens(value, getComputedStyle(element));
}
