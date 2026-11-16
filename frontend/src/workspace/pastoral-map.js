// Original vector textures: boundaries and positions always remain in the source CRS.
// These rows describe an illustration, not a survey of individual plants.
let serial = 0;
export function cropTexture(crop = "") {
  if (/玉米|小麦|水稻|稻|麦/.test(crop)) return "grain";
  if (/果|桃|梨|苹果|柑|橘/.test(crop)) return "orchard";
  if (/番茄|辣椒|草莓|茄/.test(crop)) return "fruit";
  return "leaf";
}

export function cropPatternMarkup(id, crop) {
  const type = cropTexture(crop);
  const leaf =
    '<path d="M8 15C1 15 0 7 2 5C8 5 11 8 8 15ZM9 15C9 7 13 4 17 5C17 11 14 15 9 15Z" fill="var(--atlas-leaf)"/><path d="M9 18V10" stroke="var(--atlas-stem)" stroke-width="1.3"/>';
  const marks =
    type === "grain"
      ? '<path d="M8 23V7M8 14L3 9M8 18L13 12M8 10L12 5" fill="none" stroke="var(--atlas-grain)" stroke-width="2" stroke-linecap="round"/>'
      : type === "orchard"
        ? '<circle cx="14" cy="15" r="10" fill="var(--atlas-stem)" opacity=".2"/><circle cx="13" cy="12" r="9" fill="var(--atlas-leaf)"/><circle cx="10" cy="9" r="5" fill="var(--atlas-leaf-light)"/>'
        : leaf +
          (type === "fruit"
            ? '<circle cx="6" cy="12" r="2.1" fill="var(--atlas-fruit)"/><circle cx="13" cy="10" r="2" fill="var(--atlas-fruit)"/>'
            : "");
  const size = type === "orchard" ? 34 : 24;
  return `<pattern id="${id}" width="${size}" height="${size + 6}" patternUnits="userSpaceOnUse" patternTransform="rotate(-24)"><rect width="100%" height="100%" fill="var(--atlas-${type === "grain" ? "soil" : "field"})"/><path d="M0 ${size + 2}H${size}" stroke="var(--atlas-row)" stroke-width="3"/>${marks}</pattern>`;
}

export function paintPastoralParcel(layer, crop, selected = false) {
  const path = layer.getElement();
  if (!path) return;
  const svg = path.ownerSVGElement;
  let defs = svg.querySelector("defs[data-pastoral]");
  if (!defs) {
    defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
    defs.dataset.pastoral = String(++serial);
    svg.prepend(defs);
  }
  const id = `atlas-${defs.dataset.pastoral}-${cropTexture(crop)}`;
  if (!defs.querySelector(`#${id}`))
    defs.insertAdjacentHTML("beforeend", cropPatternMarkup(id, crop));
  path.style.fill = `url(#${id})`;
  path.style.fillOpacity = "1";
  path.classList.add("pastoral-parcel");
  path.classList.toggle("pastoral-selected", selected);
}

export const MAP_MODES = ["PASTORAL", "SATELLITE", "STREET", "PLAN"];
// Version the preference once: the earlier release implicitly defaulted to illustration.
export const MAP_STYLE_KEY = "zhihe-map-style-v2";
export function readMapMode(storage, configuredMode = "SATELLITE") {
  const fallback = MAP_MODES.includes(configuredMode)
    ? configuredMode
    : "SATELLITE";
  try {
    const mode = (storage ?? globalThis.localStorage)?.getItem(MAP_STYLE_KEY);
    return MAP_MODES.includes(mode) ? mode : fallback;
  } catch {
    return fallback;
  }
}

// A schematic's "ordinary" option is not the geographic map's offline PLAN mode.
export function readDiagramStyle(storage) {
  try {
    const saved = (storage ?? globalThis.localStorage)?.getItem(
      "zhihe-diagram-style",
    );
    if (saved === "PASTORAL" || saved === "PLAIN") return saved === "PASTORAL";
    return readMapMode(storage) === "PASTORAL";
  } catch {
    return false;
  }
}
export function saveDiagramStyle(pastoral, storage) {
  try {
    (storage ?? globalThis.localStorage)?.setItem(
      "zhihe-diagram-style",
      pastoral ? "PASTORAL" : "PLAIN",
    );
  } catch {
    /* Optional preference. */
  }
}
export function saveMapMode(mode, storage) {
  try {
    if (MAP_MODES.includes(mode))
      (storage ?? globalThis.localStorage)?.setItem(MAP_STYLE_KEY, mode);
  } catch {
    /* Optional preference. */
  }
}
