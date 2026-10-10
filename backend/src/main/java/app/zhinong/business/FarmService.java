package app.zhinong.business;

import static app.zhinong.business.Store.fields;

import app.zhinong.api.ApiException;
import app.zhinong.device.DeviceAdapter;
import app.zhinong.fieldwork.FieldWorkService;
import app.zhinong.security.Identity;
import app.zhinong.workspace.AssetService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FarmService {

  private final Store store;
  private final DeviceAdapter adapter;
  private final AssetService assets;
  private final FieldWorkService fieldWork;

  public FarmService(
    Store store,
    DeviceAdapter adapter,
    AssetService assets,
    FieldWorkService fieldWork
  ) {
    this.store = store;
    this.adapter = adapter;
    this.assets = assets;
    this.fieldWork = fieldWork;
  }

  public List<Map<String, Object>> list(String table) {
    return store.list(table);
  }

  public List<Map<String, Object>> scopedList(String table, String farmId) {
    if (farmId == null || farmId.isBlank()) return store.list(table);
    store.get("farms", farmId);
    if ("plots".equals(table)) return store
      .db()
      .queryForList(
        "SELECT * FROM plots WHERE tenant_id=? AND farm_id=? ORDER BY name",
        Identity.tenant(),
        farmId
      );
    if (
      !Set.of("plantings", "farm_tasks", "production").contains(table)
    ) throw new IllegalArgumentException("Unsupported farm filter");
    return store
      .db()
      .queryForList(
        "SELECT b.* FROM " +
          table +
          " b JOIN plots p ON p.tenant_id=b.tenant_id AND p.id=b.plot_id WHERE b.tenant_id=? AND p.farm_id=? ORDER BY b.id",
        Identity.tenant(),
        farmId
      );
  }

  public Map<String, Object> dashboard() {
    String tenant = Identity.tenant();
    var db = store.db();
    return Map.of(
      "farms",
      db.queryForObject(
        "SELECT COUNT(*) FROM farms WHERE tenant_id=? AND " + Store.activeScope("farms"),
        Long.class,
        tenant
      ),
      "plots",
      db.queryForObject(
        "SELECT COUNT(*) FROM plots WHERE tenant_id=? AND " + Store.activeScope("plots"),
        Long.class,
        tenant
      ),
      "areaMu",
      db.queryForObject(
        "SELECT COALESCE(SUM(area_mu),0) FROM plots WHERE tenant_id=? AND " + Store.activeScope("plots"),
        BigDecimal.class,
        tenant
      ),
      "yieldKg",
      db.queryForObject(
        "SELECT COALESCE(SUM(yield_kg),0) FROM production WHERE tenant_id=? AND " + Store.activeScope("production"),
        BigDecimal.class,
        tenant
      ),
      "pendingTasks",
      db.queryForObject(
        "SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=? AND status IN ('PENDING','RUNNING') AND " + Store.activeScope("farm_tasks"),
        Long.class,
        tenant
      ),
      "devices",
      db.queryForObject(
        "SELECT COUNT(*) FROM devices WHERE tenant_id=? AND " + Store.activeScope("devices"),
        Long.class,
        tenant
      ),
      // 导航计数与今日农场同一口径：按服务器日期判断逾期，逾期优先于受阻，二者不重复计数。
      "overdueTasks",
      db.queryForObject(
        "SELECT COUNT(*) FROM farm_tasks WHERE tenant_id=? AND status IN ('PENDING','RUNNING') AND due_date<? AND " + Store.activeScope("farm_tasks"),
        Long.class,
        tenant,
        LocalDate.now()
      ),
      "blockedTasks",
      db.queryForObject(
        """
        SELECT COUNT(*) FROM farm_tasks t
        JOIN task_fieldwork d ON d.tenant_id=t.tenant_id AND d.task_id=t.id
        WHERE t.tenant_id=? AND t.status IN ('PENDING','RUNNING') AND t.due_date>=? AND d.blocked_reason<>'' AND NOT EXISTS(SELECT 1 FROM plots p JOIN farm_archives a ON a.tenant_id=p.tenant_id AND a.farm_id=p.farm_id WHERE p.tenant_id=t.tenant_id AND p.id=t.plot_id)
        """,
        Long.class,
        tenant,
        LocalDate.now()
      ),
      "openIssues",
      db.queryForObject(
        "SELECT COUNT(*) FROM field_issues WHERE tenant_id=? AND status<>'RESOLVED' AND " + Store.activeScope("field_issues"),
        Long.class,
        tenant
      )
    );
  }

  public Map<String, Object> farm(Records.Farm input) {
    Identity.require("ADMIN");
    return store.get(
      "farms",
      store.insert(
        "farms",
        fields("name", input.name().strip(), "description", input.description())
      )
    );
  }

  public Map<String, Object> updateFarm(String id, Records.Farm input) {
    Identity.require("ADMIN");
    store.lock("farms", id);
    store
      .db()
      .update(
        "UPDATE farms SET name=?,description=? WHERE tenant_id=? AND id=?",
        input.name().strip(),
        input.description(),
        Identity.tenant(),
        id
      );
    store.audit("UPDATE_FARM", id);
    return store.get("farms", id);
  }

  public void deleteFarm(String id) {
    Identity.require("ADMIN");
    store.lock("farms", id);
    store
      .db()
      .update(
        "DELETE FROM farms WHERE tenant_id=? AND id=?",
        Identity.tenant(),
        id
      );
    store.audit("DELETE_FARM", id);
  }

  public Map<String, Object> plot(Records.Plot input) {
    Identity.require("ADMIN");
    store.get("farms", input.farmId());
    return store.get(
      "plots",
      store.insert(
        "plots",
        fields(
          "farm_id",
          input.farmId(),
          "name",
          input.name().strip(),
          "area_mu",
          input.areaMu(),
          "crop",
          input.crop()
        )
      )
    );
  }

  public Map<String, Object> updatePlot(String id, Records.Plot input) {
    Identity.require("ADMIN");
    store.lock("plots", id);
    store.get("farms", input.farmId());
    long linked = store
      .db()
      .queryForObject(
        """
        SELECT COUNT(*) FROM asset_profiles a JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
        WHERE a.tenant_id=? AND a.plot_id=? AND d.farm_id<>?
        """,
        Long.class,
        Identity.tenant(),
        id,
        input.farmId()
      );
    if (linked > 0) throw new ApiException(
      409,
      "地块已关联设备，请先解除设备关联再跨农场迁移"
    );
    BigDecimal max = store
      .db()
      .queryForObject(
        "SELECT COALESCE(MAX(area_mu),0) FROM plantings WHERE tenant_id=? AND plot_id=?",
        BigDecimal.class,
        Identity.tenant(),
        id
      );
    if (input.areaMu().compareTo(max) < 0) throw new ApiException(
      409,
      "面积不能小于已有种植计划"
    );
    store
      .db()
      .update(
        "UPDATE plots SET farm_id=?,name=?,area_mu=?,crop=? WHERE tenant_id=? AND id=?",
        input.farmId(),
        input.name(),
        input.areaMu(),
        input.crop(),
        Identity.tenant(),
        id
      );
    store.audit("UPDATE_PLOT", id);
    return store.get("plots", id);
  }

  public void deletePlot(String id) {
    Identity.require("ADMIN");
    store.lock("plots", id);
    store
      .db()
      .update(
        "DELETE FROM plots WHERE tenant_id=? AND id=?",
        Identity.tenant(),
        id
      );
    store.audit("DELETE_PLOT", id);
  }

  public Map<String, Object> planting(Records.Planting input) {
    Identity.require("ADMIN");
    var plot = store.lock("plots", input.plotId());
    if (input.endDate().isBefore(input.startDate())) throw new ApiException(
      400,
      "结束日期不能早于开始日期"
    );
    if (input.areaMu().compareTo((BigDecimal) plot.get("AREA_MU")) > 0) {
      throw new ApiException(400, "种植面积不能大于地块面积");
    }
    // Serialize on the plot row so concurrent plans cannot bypass the overlap check.
    long overlap = store
      .db()
      .queryForObject(
        """
        SELECT COUNT(*) FROM plantings WHERE tenant_id=? AND plot_id=?
        AND status<>'FINISHED' AND start_date<=? AND end_date>=?
        """,
        Long.class,
        Identity.tenant(),
        input.plotId(),
        input.endDate(),
        input.startDate()
      );
    if (overlap > 0) throw new ApiException(
      409,
      "该地块在所选时间已有未结束的种植计划"
    );
    return store.get(
      "plantings",
      store.insert(
        "plantings",
        fields(
          "plot_id",
          input.plotId(),
          "crop",
          input.crop(),
          "variety",
          input.variety(),
          "area_mu",
          input.areaMu(),
          "start_date",
          input.startDate(),
          "end_date",
          input.endDate(),
          "status",
          "PLANNED"
        )
      )
    );
  }

  public Map<String, Object> plantingStatus(String id, String status) {
    Identity.require("ADMIN", "OPERATOR");
    String old = store.lock("plantings", id).get("STATUS").toString();
    if (
      !((old.equals("PLANNED") && status.equals("ACTIVE")) ||
        (old.equals("ACTIVE") && status.equals("FINISHED")))
    ) {
      throw new ApiException(
        409,
        "种植计划只能由待开始转为进行中，再转为已结束"
      );
    }
    store
      .db()
      .update(
        "UPDATE plantings SET status=? WHERE tenant_id=? AND id=?",
        status,
        Identity.tenant(),
        id
      );
    store.audit("PLANTING_" + status, id);
    return store.get("plantings", id);
  }

  public Map<String, Object> task(Records.Task input) {
    Identity.require("ADMIN");
    store.get("plots", input.plotId());
    return store.get(
      "farm_tasks",
      store.insert(
        "farm_tasks",
        fields(
          "plot_id",
          input.plotId(),
          "title",
          input.title(),
          "task_type",
          input.taskType(),
          "due_date",
          input.dueDate(),
          "status",
          "PENDING",
          "note",
          input.note()
        )
      )
    );
  }

  public Map<String, Object> taskStatus(String id, String status) {
    Identity.require("ADMIN", "OPERATOR");
    String old = store.lock("farm_tasks", id).get("STATUS").toString();
    fieldWork.checkLegacyTransition(id, status);
    var transitions = Map.of(
      "PENDING",
      Set.of("RUNNING", "CANCELLED"),
      "RUNNING",
      Set.of("COMPLETED", "CANCELLED")
    );
    if (
      !transitions.getOrDefault(old, Set.of()).contains(status)
    ) throw new ApiException(409, "不允许的任务状态变更");
    store
      .db()
      .update(
        "UPDATE farm_tasks SET status=? WHERE tenant_id=? AND id=?",
        status,
        Identity.tenant(),
        id
      );
    store.audit("TASK_" + status, id);
    return store.get("farm_tasks", id);
  }

  public Map<String, Object> production(Records.Production input) {
    Identity.require("ADMIN", "OPERATOR");
    store.get("plots", input.plotId());
    return store.get(
      "production",
      store.insert(
        "production",
        fields(
          "plot_id",
          input.plotId(),
          "record_date",
          input.recordDate(),
          "yield_kg",
          input.yieldKg(),
          "note",
          input.note()
        )
      )
    );
  }

  public Map<String, Object> device(Records.Device input) {
    Identity.require("ADMIN");
    store.get("farms", input.farmId());
    String unit = input.metric().equals("TEMPERATURE") ? "℃" : "%";
    var created = store.get(
      "devices",
      store.insert(
        "devices",
        fields(
          "farm_id",
          input.farmId(),
          "name",
          input.name(),
          "metric",
          input.metric(),
          "unit",
          unit,
          "adapter",
          input.adapter()
        )
      )
    );
    assets.adoptLegacy(created.get("ID").toString());
    return created;
  }

  public Map<String, Object> observe(String id, Records.Observation input) {
    Identity.require("ADMIN", "OPERATOR");
    var device = store.get("devices", id);
    var profile = assets.detail(id);
    if (
      !"ACTIVE".equals(profile.get("lifecycle")) ||
      !"MANUAL".equals(profile.get("protocol"))
    ) {
      throw new ApiException(409, "设备状态或接入方式不允许人工录入");
    }
    if (!device.get("ADAPTER").equals("MANUAL")) throw new ApiException(
      409,
      "模拟设备请使用模拟采集"
    );
    return observation(
      id,
      input.value(),
      input.measuredAt(),
      "MANUAL",
      device.get("METRIC").toString()
    );
  }

  public Map<String, Object> sample(String id) {
    Identity.require("ADMIN", "OPERATOR");
    var device = store.get("devices", id);
    var profile = assets.detail(id);
    if (!Set.of("TEMPERATURE", "HUMIDITY", "SOIL_MOISTURE").contains(device.get("METRIC").toString())) {
      throw new ApiException(409, "此类设备请使用多指标采集接口，避免覆盖设备反馈");
    }
    if (
      !"ACTIVE".equals(profile.get("lifecycle")) ||
      !"SIMULATED".equals(profile.get("protocol"))
    ) {
      throw new ApiException(409, "设备状态或接入方式不允许模拟采集");
    }
    if (!device.get("ADAPTER").equals("SIMULATED")) throw new ApiException(
      409,
      "该设备仅支持人工录入"
    );
    String metric = device.get("METRIC").toString();
    return observation(
      id,
      adapter.sample(Identity.tenant(), id, metric),
      LocalDateTime.now(),
      "SIMULATED",
      metric
    );
  }

  private Map<String, Object> observation(
    String id,
    BigDecimal value,
    LocalDateTime time,
    String source,
    String metric
  ) {
    BigDecimal min = BigDecimal.valueOf(metric.equals("TEMPERATURE") ? -80 : 0);
    BigDecimal max = BigDecimal.valueOf(
      metric.equals("TEMPERATURE") ? 100 : 100
    );
    if (
      value.compareTo(min) < 0 || value.compareTo(max) > 0
    ) throw new ApiException(400, "监测值超出该指标允许范围");
    return store.get(
      "observations",
      store.insert(
        "observations",
        fields(
          "device_id",
          id,
          "measured_value",
          value,
          "measured_at",
          store.dialect().mysql() ? time.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime() : time,
          "source",
          source
        )
      )
    );
  }
}
