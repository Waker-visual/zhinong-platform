package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FarmWorkspaceService {

  private final Store store;
  private final JdbcTemplate db;
  private final AssetService assets;
  private final TelemetryService telemetry;
  private final ObjectMapper json;

  public FarmWorkspaceService(
    Store store,
    AssetService assets,
    TelemetryService telemetry,
    ObjectMapper json
  ) {
    this.store = store;
    this.db = store.db();
    this.assets = assets;
    this.telemetry = telemetry;
    this.json = json;
  }

  public List<Map<String, Object>> cards() {
    var rows = db.queryForList(
      """
      SELECT f.id AS "id",f.name AS "name",f.description AS "description",
        COALESCE(p.region,'') AS "region",COALESCE(p.farm_type,'FIELD') AS "farmType",
        COALESCE(p.demo,FALSE) AS "demo",COALESCE(p.layout_revision,0) AS "layoutRevision"
      FROM farms f LEFT JOIN farm_profiles p ON p.tenant_id=f.tenant_id AND p.farm_id=f.id
      WHERE f.tenant_id=? ORDER BY COALESCE(p.demo,FALSE) DESC,f.name
      """,
      Identity.tenant()
    );
    for (var row : rows) {
      Object demo = row.get("demo");
      row.put("demo", Boolean.TRUE.equals(demo) || demo instanceof Number number && number.intValue() != 0);
      String id = row.get("id").toString();
      var plots = plots(id);
      row.put("plots", plots);
      row.put("plotCount", plots.size());
      row.put(
        "areaMu",
        plots
          .stream()
          .map(p -> (BigDecimal) p.get("areaMu"))
          .reduce(BigDecimal.ZERO, BigDecimal::add)
      );
      row.put(
        "deviceCount",
        db.queryForObject(
          "SELECT COUNT(*) FROM devices WHERE tenant_id=? AND farm_id=?",
          Long.class,
          Identity.tenant(),
          id
        )
      );
      row.put(
        "pendingTasks",
        db.queryForObject(
          """
          SELECT COUNT(*) FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
          WHERE t.tenant_id=? AND p.farm_id=? AND t.status IN ('PENDING','RUNNING')
          """,
          Long.class,
          Identity.tenant(),
          id
        )
      );
    }
    return rows;
  }

  public List<Map<String, Object>> plots(String farmId) {
    store.get("farms", farmId);
    var rows = db.queryForList(
      """
      SELECT p.id AS "id",p.name AS "name",p.farm_id AS "farmId",p.area_mu AS "areaMu",
        p.crop AS "crop",s.boundary_json AS "boundaryJson"
      FROM plots p LEFT JOIN plot_shapes s ON s.tenant_id=p.tenant_id AND s.plot_id=p.id
      WHERE p.tenant_id=? AND p.farm_id=? ORDER BY p.name
      """,
      Identity.tenant(),
      farmId
    );
    for (var row : rows) {
      Object boundary = row.remove("boundaryJson");
      try {
        row.put(
          "boundary",
          boundary == null
            ? List.of()
            : json.readValue(boundary.toString(), new TypeReference<List<List<Double>>>() {})
        );
      } catch (Exception e) {
        throw new IllegalStateException("Saved plot geometry is invalid", e);
      }
    }
    return rows;
  }

  public Map<String, Object> workspace(String id, int days) {
    store.get("farms", id);
    var farm = cards()
      .stream()
      .filter(f -> id.equals(f.get("id")))
      .findFirst()
      .orElseThrow(ApiException::missing);
    return Map.of(
      "farm",
      farm,
      "coordinateSystem",
      "LOCAL_PLAN",
      "width",
      1000,
      "height",
      700,
      "plots",
      farm.get("plots"),
      "devices",
      assets.list(id),
      "analytics",
      analytics(id, days),
      "alerts",
      telemetry.alerts(id, null),
      "tasks",
      db.queryForList(
        """
        SELECT t.id AS "id",t.title AS "title",t.status AS "status",t.task_type AS "taskType",
          t.due_date AS "dueDate",p.name AS "plotName",p.id AS "plotId"
        FROM farm_tasks t JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
        WHERE t.tenant_id=? AND p.farm_id=? ORDER BY t.due_date DESC LIMIT 100
        """,
        Identity.tenant(),
        id
      )
    );
  }

  public Map<String, Object> analytics(String id, int days) {
    store.get("farms", id);
    if (days < 1 || days > 365) throw new ApiException(400, "统计范围为 1–365 天");
    String tenant = Identity.tenant();
    LocalDate start = LocalDate.now().minusDays(days - 1);
    var crops = db.queryForList(
      """
      SELECT CASE WHEN crop='' THEN '未填写作物' ELSE crop END AS "name",SUM(area_mu) AS "value"
      FROM plots WHERE tenant_id=? AND farm_id=? GROUP BY crop ORDER BY SUM(area_mu) DESC
      """,
      tenant,
      id
    );
    var status = db.queryForList(
      """
      SELECT t.status AS "code",COUNT(*) AS "value" FROM farm_tasks t
      JOIN plots p ON p.tenant_id=t.tenant_id AND p.id=t.plot_id
      WHERE t.tenant_id=? AND p.farm_id=? GROUP BY t.status
      """,
      tenant,
      id
    );
    var production = db.queryForList(
      """
      SELECT r.record_date AS "date",SUM(r.yield_kg) AS "value" FROM production r
      JOIN plots p ON p.tenant_id=r.tenant_id AND p.id=r.plot_id
      WHERE r.tenant_id=? AND p.farm_id=? AND r.record_date>=? AND r.record_date<=CURRENT_DATE
      GROUP BY r.record_date ORDER BY r.record_date
      """,
      tenant,
      id,
      start
    );
    var distribution = db.queryForList(
      """
      SELECT a.device_type AS "code",COUNT(*) AS "value" FROM asset_profiles a
      JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      WHERE d.tenant_id=? AND d.farm_id=? GROUP BY a.device_type
      """,
      tenant,
      id
    );
    distribution.forEach(v -> v.put("name", MetricCatalog.TYPES.get(v.get("code").toString())));
    var productionPlots = db.queryForList(
      """
      SELECT p.name AS "name",COALESCE(SUM(r.yield_kg),0) AS "value"
      FROM plots p LEFT JOIN production r ON r.tenant_id=p.tenant_id AND r.plot_id=p.id
        AND r.record_date>=? AND r.record_date<=CURRENT_DATE
      WHERE p.tenant_id=? AND p.farm_id=? GROUP BY p.id,p.name ORDER BY SUM(r.yield_kg) DESC
      """,
      start,
      tenant,
      id
    );
    var summary = new LinkedHashMap<String, Object>();
    summary.put(
      "areaMu",
      crops
        .stream()
        .map(v -> (BigDecimal) v.get("value"))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
    );
    summary.put(
      "yieldKg",
      production
        .stream()
        .map(v -> (BigDecimal) v.get("value"))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
    );
    summary.put(
      "tasks",
      status
        .stream()
        .mapToLong(v -> ((Number) v.get("value")).longValue())
        .sum()
    );
    summary.put(
      "completedTasks",
      status
        .stream()
        .filter(v -> v.get("code").equals("COMPLETED"))
        .mapToLong(v -> ((Number) v.get("value")).longValue())
        .sum()
    );
    return Map.of(
      "days",
      days,
      "from",
      start,
      "to",
      LocalDate.now(),
      "summary",
      summary,
      "cropArea",
      crops,
      "taskStatus",
      status,
      "deviceTypes",
      distribution,
      "productionTrend",
      production,
      "productionByPlot",
      productionPlots
    );
  }

  public void profile(String id, WorkspaceInputs.Profile input) {
    Identity.require("ADMIN");
    store.lock("farms", id);
    ensureProfile(id);
    db.update(
      "UPDATE farms SET name=?,description=? WHERE tenant_id=? AND id=?",
      input.name().strip(),
      input.description(),
      Identity.tenant(),
      id
    );
    db.update(
      "UPDATE farm_profiles SET region=?,farm_type=? WHERE tenant_id=? AND farm_id=?",
      input.region(),
      input.farmType(),
      Identity.tenant(),
      id
    );
    store.audit("UPDATE_FARM_PROFILE", id);
  }

  public Map<String, Object> create(WorkspaceInputs.Profile input) {
    Identity.require("ADMIN");
    String id = store.insert(
      "farms",
      Store.fields("name", input.name().strip(), "description", input.description())
    );
    profile(id, input);
    return Map.of("id", id, "name", input.name().strip());
  }

  public Map<String, Object> saveLayout(String id, WorkspaceInputs.Layout input) {
    Identity.require("ADMIN");
    store.lock("farms", id);
    ensureProfile(id);
    int revision = db.queryForObject(
      "SELECT layout_revision FROM farm_profiles WHERE tenant_id=? AND farm_id=?",
      Integer.class,
      Identity.tenant(),
      id
    );
    if (revision != input.revision()) throw new ApiException(
      409,
      "平面图已更新，请刷新后重新编辑，避免覆盖其他修改"
    );
    Set<String> seen = new HashSet<>();
    for (var shape : input.shapes()) {
      if (!seen.add(shape.plotId())) throw new ApiException(400, "地块不能重复提交");
      if (!store.get("plots", shape.plotId()).get("FARM_ID").equals(id)) throw new ApiException(
        400,
        "地块不属于当前农场"
      );
      PlanGeometry.polygon(shape.boundary());
      try {
        db.update(
          store.dialect().upsert("plot_shapes", "tenant_id,plot_id,boundary_json", "tenant_id,plot_id"),
          Identity.tenant(),
          shape.plotId(),
          json.writeValueAsString(shape.boundary())
        );
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalArgumentException(e);
      }
    }
    seen.clear();
    for (var point : input.positions()) {
      if (!seen.add(point.deviceId())) throw new ApiException(400, "设备点位不能重复提交");
      if (!store.get("devices", point.deviceId()).get("FARM_ID").equals(id)) throw new ApiException(
        400,
        "设备不属于当前农场"
      );
      store.lock("devices", point.deviceId());
      if (point.locationMode() == null && "WGS84".equals(assets.detail(point.deviceId()).get("locationMode"))) {
        throw new ApiException(409, "已有 WGS84 设备点位，请刷新地图后明确选择坐标系");
      }
      AssetLocation.validate(point.locationMode(), point.x(), point.y(), point.latitude(), point.longitude());
      db.update(
        "UPDATE asset_profiles SET plan_x=?,plan_y=?,location_mode=?,latitude=?,longitude=?,revision=revision+1 WHERE tenant_id=? AND device_id=?",
        point.x(),
        point.y(),
        AssetLocation.mode(point.locationMode()),
        point.latitude(),
        point.longitude(),
        Identity.tenant(),
        point.deviceId()
      );
    }
    db.update(
      "UPDATE farm_profiles SET layout_revision=layout_revision+1 WHERE tenant_id=? AND farm_id=?",
      Identity.tenant(),
      id
    );
    store.audit("SAVE_FARM_LAYOUT", id);
    return Map.of("revision", revision + 1, "ok", true);
  }

  private void ensureProfile(String id) {
    db.update(
      """
      INSERT INTO farm_profiles(tenant_id,farm_id) SELECT ?,? WHERE NOT EXISTS
      (SELECT 1 FROM farm_profiles WHERE tenant_id=? AND farm_id=?)
      """,
      Identity.tenant(),
      id,
      Identity.tenant(),
      id
    );
  }
}
