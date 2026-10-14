mergeInto(LibraryManager.library, {
  FarmPlot: function (id) { window.farmPlot(UTF8ToString(id)); },
  FarmPoints: function (json) { window.farmPoints(UTF8ToString(json)); },
  FarmReady: function () { window.farmReady(); }
});
