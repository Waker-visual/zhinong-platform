import L from "leaflet";

// Requests use the application's authenticated origin, so browser third-party access is not required.
export const AuthenticatedTileLayer = L.TileLayer.extend({
  initialize(provider, options) {
    this.provider = provider;
    L.TileLayer.prototype.initialize.call(
      this,
      "/api/map-tiles/" + provider + "/{z}/{x}/{y}",
      options,
    );
    this.on("tileunload", ({ tile }) => {
      tile._mapCancel?.();
      if (tile._mapBlob) URL.revokeObjectURL(tile._mapBlob);
    });
  },
  createTile(coords, done) {
    const tile = document.createElement("img");
    tile.alt = "";
    tile.setAttribute("role", "presentation");
    const controller = new AbortController();
    tile._mapAbort = controller;
    const timeout = setTimeout(() => controller.abort(), 15000);
    let settled = false;
    tile._mapCancel = () => {
      settled = true;
      clearTimeout(timeout);
      controller.abort();
    };
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      done(error, tile);
    };
    tile.onload = () => finish(null);
    tile.onerror = () => finish(new Error("底图图片无法解码"));
    fetch(this.getTileUrl(coords), {
      headers: {
        Authorization:
          "Bearer " + (sessionStorage.getItem("zhinong-session") || ""),
      },
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          const data = await response.json().catch(() => ({}));
          throw new Error(
            data.message || "底图请求失败（" + response.status + "）",
          );
        }
        const blob = await response.blob();
        if (controller.signal.aborted) throw new Error("底图请求已取消");
        tile._mapBlob = URL.createObjectURL(blob);
        tile.src = tile._mapBlob;
      })
      .catch((error) =>
        finish(
          new Error(
            error.name === "AbortError"
              ? "底图连接超时，请重试"
              : error.message,
          ),
        ),
      );
    return tile;
  },
});
