package app.zhinong.risk;

import app.zhinong.business.*;
import app.zhinong.security.Identity;
import app.zhinong.weather.WeatherForecastService;
import app.zhinong.workspace.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** A read-only, prioritised action list. Weather never creates field incidents or device commands. */
@Service
public class FarmRiskService {
  private final Store store;
  private final FarmClock clock;
  private final WeatherForecastService forecasts;
  private final TelemetryService telemetry;
  private final AssetService assets;
  public FarmRiskService(Store store,FarmClock clock,WeatherForecastService forecasts,TelemetryService telemetry,AssetService assets) {
    this.store=store;this.clock=clock;this.forecasts=forecasts;this.telemetry=telemetry;this.assets=assets;
  }

  public Map<String,Object> overview(String farmId) {
    store.get("farms",farmId);String tenant=Identity.tenant();
    // Do not hold a database transaction/connection while an external forecast is requested.
    var forecast=forecasts.get(farmId);
    var capturedAt=clock.instant();
    var items=new ArrayList<Map<String,Object>>(WeatherRiskRules.evaluate(forecast,capturedAt));
    long weather=items.size();
    var alertTotals=telemetry.riskAlertSummary(farmId);
    long device=count(alertTotals,"activeAlerts"),deviceHigh=count(alertTotals,"highAlerts");
    for(var alert:telemetry.riskAlerts(farmId)) {
      var item=base("device:"+alert.get("id"),"DEVICE",text(alert,"severity"),
        text(alert,"deviceName")+" · "+text(alert,"metricName"),text(alert,"status"),text(alert,"recommendation"),"alerts","进入预警中心处理");
      for(String key:List.of("plotId","plotName","deviceId","deviceName"))item.put(key,alert.get(key));
      var evidence=new ArrayList<String>();evidence.add(text(alert,"message"));
      if(alert.get("value")!=null)evidence.add("告警记录值："+alert.get("value")+"；不是当前实测值");
      if(alert.get("thresholdSeconds")!=null)evidence.add("接收或采样有效窗口："+alert.get("thresholdSeconds")+" 秒");
      item.put("evidence",evidence);item.put("occurredAt",alert.get("openedAt"));items.add(item);
    }
    String linked="""
      SELECT COUNT(*) FROM alert_field_issues link JOIN device_alerts a ON a.tenant_id=link.tenant_id AND a.id=link.alert_id
      JOIN devices d ON d.tenant_id=a.tenant_id AND d.id=a.device_id
      WHERE link.tenant_id=i.tenant_id AND link.issue_id=i.id AND a.status<>'RESOLVED' AND d.farm_id=p.farm_id
      """;
    String urgentLinked=linked+" AND a.metric IN ('OFFLINE','FAULT','EMERGENCY_STOP')";
    String fieldQuery="""
      SELECT i.id AS "id",p.id AS "plotId",p.name AS "plotName",i.category AS "category",
        i.description AS "description",i.status AS "status",i.created_at AS "createdAt",
      """+" CASE WHEN i.severity='HIGH' OR ("+urgentLinked+")>0 THEN 'HIGH' ELSE 'WARNING' END AS \"riskSeverity\","+
      " ("+linked+") AS \"linkedAlerts\" FROM field_issues i JOIN plots p ON p.tenant_id=i.tenant_id AND p.id=i.plot_id"+
      " WHERE i.tenant_id=? AND p.farm_id=? AND i.status<>'RESOLVED'";
    var fieldTotals=store.db().queryForMap("SELECT COUNT(*) AS \"total\",COUNT(CASE WHEN q.\"riskSeverity\"='HIGH' THEN 1 END) AS \"high\" FROM ("+fieldQuery+") q",tenant,farmId);
    long field=count(fieldTotals,"total"),fieldHigh=count(fieldTotals,"high");
    for(var issue:store.db().queryForList("SELECT * FROM ("+fieldQuery+") q ORDER BY CASE WHEN q.\"riskSeverity\"='HIGH' THEN 0 ELSE 1 END,q.\"createdAt\" DESC,q.\"id\" LIMIT 80",tenant,farmId)) {
      var item=base("field:"+issue.get("id"),"FIELD",text(issue,"riskSeverity"),text(issue,"plotName")+" · 现场问题",
        text(issue,"status"),"进入农事协作核对负责人、执行任务与复查记录；任务完成不等于问题已经消除。","daily","跟进现场处置");
      item.put("plotId",issue.get("plotId"));item.put("plotName",issue.get("plotName"));
      var evidence=new ArrayList<String>();evidence.add(text(issue,"description"));
      long linkedCount=count(issue,"linkedAlerts");if(linkedCount>0)evidence.add("已合并 "+linkedCount+" 条关联设备告警，同一现场问题统一跟进");
      item.put("linkedDeviceAlerts",linkedCount);item.put("evidence",evidence);item.put("occurredAt",issue.get("createdAt"));items.add(item);
    }
    long beforeQuality=items.size();
    String forecastStatus=text(forecast,"status");
    if("READY".equals(forecastStatus)&&!WeatherRiskRules.usable(forecast,capturedAt))forecastStatus="STALE";
    if(!"READY".equals(forecastStatus)) {
      boolean noLocation="UNCONFIGURED".equals(forecastStatus);
      var item=base("data:forecast","DATA_QUALITY",noLocation||"DISABLED".equals(forecastStatus)?"INFO":"WARNING",
        noLocation?"需校准农场参考位置":"七日天气风险筛查暂不可用","DATA_GAP",
        noLocation?"先在农场概览保存参考位置，再读取该区域天气。":"DISABLED".equals(forecastStatus)?"公共天气预报未启用，请管理员核对服务配置，并查看当地官方气象信息。":"请重试天气服务，并以当地官方气象信息核实。旧预报不能作为新的风险判断。",
        noLocation?"map":"forecast",noLocation?"校准参考位置":"查看天气状态");
      item.put("evidence",List.of("天气状态："+forecastStatus,"未来风险资料不完整，不代表未来没有天气风险"));items.add(item);
    } else {
      @SuppressWarnings("unchecked") var days=(List<Map<String,Object>>)forecast.getOrDefault("days",List.of());
      long complete=days.stream().filter(d->List.of("weatherCode","tempMinC","tempMaxC","precipitationMm","gustMaxMs").stream().allMatch(k->d.get(k)!=null)).count();
      if(complete<7) {
        var item=base("data:forecast-partial","DATA_QUALITY","WARNING","七日预报有资料缺口","DATA_GAP",
          "缺失数值没有按零计算；请重试预报，并结合当地官方气象信息核实，未触发风险不代表没有风险。","forecast","查看缺测日期");
        item.put("evidence",List.of("完整核心预报日期 "+complete+" / 7 天"));items.add(item);
      }
    }
    var codes=new HashSet<String>();MetricCatalog.METRICS.forEach(m->codes.add(m.code()));
    long channels=0,unusable=0;var missingReasons=new TreeMap<String,Integer>();
    for(var reading:assets.farmReadings(tenant,farmId,codes)) {
      if(!"ACTIVE".equals(reading.get("lifecycle")))continue;channels++;
      @SuppressWarnings("unchecked") var latest=(Map<String,Object>)reading.get("latest");
      var quality=AssetService.quality(latest,text(reading,"metric"),text(reading,"protocol"),"ACTIVE",((Number)reading.get("intervalSeconds")).intValue(),capturedAt);
      if(!quality.fresh()){unusable++;missingReasons.merge(quality.reason(),1,Integer::sum);}
    }
    if(channels==0||unusable>0) {
      var item=base("data:monitoring","DATA_QUALITY",channels==0?"INFO":"WARNING",channels==0?"尚无在用监测指标":"当前监测资料不完整","DATA_GAP",
        "核查设备配置、采集时间与来源，必要时现场补测；不依据无效读数安排自动灌溉或施药。","devices","核查监测资料");
      var evidence=new ArrayList<String>();evidence.add("在用配置指标 "+channels+" 项，当前不可用 "+unusable+" 项");
      missingReasons.forEach((reason,n)->evidence.add(reason+"："+n+" 项"));item.put("evidence",evidence);items.add(item);
    }
    long quality=items.size()-beforeQuality;
    long weatherHigh=items.stream().filter(i->"WEATHER".equals(i.get("source"))&&"HIGH".equals(i.get("severity"))).count();
    long info=items.stream().filter(i->"INFO".equals(i.get("severity"))).count();
    long high=weatherHigh+deviceHigh+fieldHigh,total=weather+device+field+quality;
    var summary=new LinkedHashMap<String,Object>();summary.put("high",high);summary.put("warning",total-high-info);summary.put("info",info);
    summary.put("total",total);summary.put("weather",weather);summary.put("device",device);summary.put("field",field);summary.put("dataQuality",quality);
    items.sort(Comparator.comparingInt((Map<String,Object> i)->rank(text(i,"severity"))).thenComparing(i->text(i,"affectedDate")).thenComparing(i->text(i,"id")));
    var result=new LinkedHashMap<String,Object>();result.put("farmId",farmId);result.put("generatedAt",capturedAt);result.put("forecastStatus",forecastStatus);
    result.put("forecastFetchedAt",forecast.get("fetchedAt"));result.put("forecastExpiresAt",forecast.get("expiresAt"));
    result.put("summary",summary);result.put("items",items.stream().limit(80).toList());result.put("total",total);result.put("truncated",total>Math.min(items.size(),80));
    result.put("notice","天气为公开区域网格预报，风险为教学规则筛查，不是官方气象预警或作物灾害诊断。设备告警关联未关闭现场问题时合并跟进，保留预警中心原记录；总数含独立风险与资料缺口。最多展示80项，操作需有权限人员在原流程确认，未自动派工或控制设备。");
    return result;
  }

  private static int rank(String severity){return switch(severity){case "HIGH"->0;case "WARNING"->1;default->2;};}
  private static long count(Map<String,Object> map,String key){Object value=map.get(key);return value instanceof Number n?n.longValue():0;}
  private static String text(Map<String,Object> map,String key){return Objects.toString(map.get(key),"");}
  private static LinkedHashMap<String,Object> base(String id,String source,String severity,String title,String status,String recommendation,String target,String label) {
    var item=new LinkedHashMap<String,Object>();item.put("id",id);item.put("source",source);item.put("severity",severity);item.put("title",title);
    item.put("status",status);item.put("recommendation",recommendation);item.put("action",Map.of("target",target,"label",label));return item;
  }
}
