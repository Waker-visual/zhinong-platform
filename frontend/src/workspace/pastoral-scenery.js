// Original decorative scenery. All objects stay outside the data bounding box.
// SVG units are Mercator-projected world units, so scenery follows pan and zoom.
export function sceneryLayout(width, height) {
  if (![width, height].every((n) => Number.isFinite(n) && n > 0)) return [];
  const items = [];
  for (const side of [-1, 1]) {
    const edge = side < 0 ? -64 : width + 64;
    for (let i = 0; i < 6; i++) {
      items.push({
        kind: "tree",
        x: edge + side * (i % 2) * 32,
        y: (height * i) / 5 - 14,
        width: 32,
        height: 40,
      });
    }
    for (let i = 0; i < 3; i++) {
      items.push({
        kind: "tree",
        x: (width * (i + 0.5)) / 3 - 16,
        y: side < 0 ? -72 : height + 48,
        width: 32,
        height: 40,
      });
    }
  }
  items.push({
    kind: "house",
    x: -177,
    y: height * 0.6,
    width: 76,
    height: 66,
  });
  items.push({
    kind: "house",
    x: width + 110,
    y: height * 0.12,
    width: 76,
    height: 66,
  });
  return items;
}

function sprite(item) {
  const tree =
    '<ellipse cx="17" cy="34" rx="15" ry="5" fill="var(--atlas-shadow)"/><path d="M16 34V15" stroke="var(--atlas-roof)" stroke-width="3"/><circle cx="16" cy="17" r="14" fill="var(--atlas-stem)"/><circle cx="12" cy="12" r="10" fill="var(--atlas-leaf)"/><circle cx="9" cy="9" r="6" fill="var(--atlas-leaf-light)"/>';
  const house =
    '<ellipse cx="38" cy="59" rx="37" ry="6" fill="var(--atlas-shadow)"/><path d="M8 27H66V57H8Z" fill="var(--atlas-wall)"/><path d="M0 28 35 2 76 28Z" fill="var(--atlas-roof)"/><path d="M49 12V0H58V19" fill="var(--atlas-roof)"/><path d="M12 27 35 11 61 27" fill="none" stroke="var(--atlas-edge)" stroke-width="2"/><rect x="17" y="34" width="12" height="12" rx="1" fill="var(--atlas-water)"/><rect x="42" y="34" width="14" height="23" rx="1" fill="var(--atlas-roof)"/>';
  return `<g transform="translate(${item.x} ${item.y})" data-scenery="${item.kind}">${item.kind === "tree" ? tree : house}</g>`;
}
export function sceneryMarkup(width, height) {
  const objects = sceneryLayout(width, height);
  if (!objects.length) return "";
  // Footpaths and a small pond also remain wholly outside the data rectangle.
  return `<g fill="none" stroke="var(--atlas-edge)" stroke-width="13" stroke-linecap="round" opacity=".85"><path d="M-112 -85Q-150 ${height * 0.4} -113 ${height + 85}"/><path d="M${width + 89} -90Q${width + 135} ${height * 0.5} ${width + 98} ${height + 95}"/></g><ellipse cx="-143" cy="${height * 0.15}" rx="38" ry="27" fill="var(--atlas-water)" opacity=".75"/><path d="M-163 ${height * 0.15}h24m-12 9h26" stroke="var(--atlas-edge)" stroke-width="2" opacity=".7"/>${objects.map(sprite).join("")}`;
}

// Reuse the overlay on telemetry/selection updates; geometry alone invalidates it.
const overlays = new WeakMap();
export function updatePastoralScenery(L, map, bounds) {
  if (!bounds?.isValid()) return;
  const signature = bounds.toBBoxString();
  const previous = overlays.get(map);
  if (previous?.signature === signature) return;
  previous?.layer.remove();
  const crs = map.options.crs;
  const nw = crs.project(bounds.getNorthWest()),
    se = crs.project(bounds.getSouthEast());
  const spanX = Math.max(1, se.x - nw.x),
    spanY = Math.max(1, nw.y - se.y);
  const scale = 800 / Math.max(spanX, spanY * 1.35);
  const width = spanX * scale,
    height = spanY * scale;
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", `-220 -130 ${width + 440} ${height + 260}`);
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("pastoral-scenery");
  svg.innerHTML = sceneryMarkup(width, height);
  const expanded = L.latLngBounds(
    crs.unproject(L.point(nw.x - 220 / scale, nw.y + 130 / scale)),
    crs.unproject(
      L.point(nw.x + (width + 220) / scale, nw.y - (height + 130) / scale),
    ),
  );
  if (!map.getPane("pastoral-scenery")) {
    const pane = map.createPane("pastoral-scenery");
    pane.style.zIndex = "250";
    pane.style.pointerEvents = "none";
  }
  const layer = L.svgOverlay(svg, expanded, {
    pane: "pastoral-scenery",
    interactive: false,
  }).addTo(map);
  overlays.set(map, { signature, layer });
}
