export const navigationGroups = [
  "日常作业",
  "农场与设备",
  "分析与管理",
  "平台管理",
];
export function readGroups(storage) {
  try {
    storage ??= globalThis.localStorage;
    const saved = JSON.parse(
      storage?.getItem("zhihe-navigation-groups") || "{}",
    );
    return Object.fromEntries(
      navigationGroups.map((name) => [name, saved?.[name] !== false]),
    );
  } catch {
    return Object.fromEntries(navigationGroups.map((name) => [name, true]));
  }
}
export function saveGroups(groups, storage) {
  try {
    (storage ?? globalThis.localStorage)?.setItem(
      "zhihe-navigation-groups",
      JSON.stringify(groups),
    );
  } catch {
    /* Optional preference. */
  }
}
