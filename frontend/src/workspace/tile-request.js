// Keep two HTTP/1.1 same-origin connections available for business API requests.
// One shared, bounded queue for visible map tiles across maps in this page.
// Removing a map cancels queued work immediately; active fetches retain their slot until aborted.
export function createTileRequestQueue({
  concurrency = 4,
  capacity = 48,
} = {}) {
  let active = 0;
  const pending = [];
  const abortError = () => new DOMException("底图请求已取消", "AbortError");
  function drain() {
    while (active < concurrency && pending.length) {
      const entry = pending.shift();
      if (entry.signal.aborted) {
        entry.reject(abortError());
        entry.signal.removeEventListener("abort", entry.cancel);
        continue;
      }
      entry.started = true;
      active++;
      Promise.resolve()
        .then(() =>
          entry.signal.aborted ? Promise.reject(abortError()) : entry.work(),
        )
        .then(entry.resolve, entry.reject)
        .finally(() => {
          active--;
          entry.signal.removeEventListener("abort", entry.cancel);
          drain();
        });
    }
  }
  return (work, signal) =>
    new Promise((resolve, reject) => {
      if (signal.aborted) return reject(abortError());
      if (active >= concurrency && pending.length >= capacity)
        return reject(new Error("底图请求较多，请稍后重试"));
      const entry = { work, signal, resolve, reject, started: false };
      entry.cancel = () => {
        if (!entry.started) {
          const index = pending.indexOf(entry);
          if (index >= 0) pending.splice(index, 1);
          signal.removeEventListener("abort", entry.cancel);
        }
        reject(abortError());
      };
      signal.addEventListener("abort", entry.cancel, { once: true });
      pending.push(entry);
      drain();
    });
}

export const requestMapTile = createTileRequestQueue();
