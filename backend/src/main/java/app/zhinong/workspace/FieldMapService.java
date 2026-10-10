package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import app.zhinong.business.Store;
import app.zhinong.database.DatabaseTime;
import app.zhinong.fieldwork.FieldWorkService;
import app.zhinong.security.Identity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted, tenant-scoped map geometry and explicitly simulated field operations. */
@Service @Transactional
public class FieldMapService {
  private final Store store;private final JdbcTemplate db;private final ObjectMapper json;
  private final AssetService assets;private final DeviceCommandService commands;private final FieldWorkService fieldwork;
  public FieldMapService(Store store,ObjectMapper json,AssetService assets,DeviceCommandService commands,FieldWorkService fieldwork) {
    this.store=store;db=store.db();this.json=json;this.assets=assets;this.commands=commands;this.fieldwork=fieldwork;
  }
  public record ParcelInput(@NotBlank String plotId,@NotBlank @Size(max=80) String name,@NotNull @Size(min=3,max=80) List<List<Double>> boundary) {}
  public record WorkInput(@NotBlank String parcelId,@NotBlank String deviceId,@NotBlank @Size(max=90) String title,
    @Pattern(regexp="INSPECTION|SOWING|FERTILIZING|HARVEST|PROTECTION") String taskType,
    @DecimalMin("2") @DecimalMax("20") double widthMeters,@DecimalMin("0") @DecimalMax("179") double bearing,
    @DecimalMin("3") @DecimalMax("20") double headlandMeters,@DecimalMin("1") @DecimalMax("40") double speedKmh,
    @Min(0) @Max(300) int durationSeconds,@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String requestId,
    @Pattern(regexp="AUTO|MANUAL") String planningMode,@Size(max=500) List<List<Double>> manualPoints,
    boolean reverse,@Min(1) @Max(120) Integer simulationRate,@DecimalMin("2") @DecimalMax("30") Double altitudeMeters,
    Boolean recommendBearing) {}
  public record WaterInput(@NotBlank String zoneId,@Min(10) @Max(300) int durationSeconds,
    @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String requestId) {}
  public record JobAction(@NotBlank @Pattern(regexp="PAUSE|RESUME|STOP") String action) {}

  private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private Object decode(Object v){try{return json.readValue(v.toString(),Object.class);}catch(Exception e){throw new IllegalStateException("Saved spatial JSON is invalid",e);}}
  private List<List<Double>> boundary(Object value){try{return json.readValue(value.toString(),new TypeReference<List<List<Double>>>(){});}catch(Exception e){throw new IllegalStateException("Saved boundary is invalid",e);}}
  private Map<String,Object> parcel(String tenant,String farm,String id) {
    var rows=db.queryForList("SELECT * FROM farm_map_parcels WHERE tenant_id=? AND farm_id=? AND id=?",tenant,farm,id);
    if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
  }
  private Map<String,Object> zone(String farm,String id) {
    var rows=db.queryForList("SELECT z.*,p.plot_id,p.boundary_json FROM farm_map_zones z JOIN farm_map_parcels p ON p.tenant_id=z.tenant_id AND p.id=z.parcel_id AND p.farm_id=z.farm_id WHERE z.tenant_id=? AND z.farm_id=? AND z.id=?",Identity.tenant(),farm,id);
    if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
  }
  public Map<String,Object> workspace(String farm) {
    store.get("farms",farm);String tenant=Identity.tenant();
    var parcels=db.queryForList("""
      SELECT p.id AS "id",p.plot_id AS "plotId",p.name AS "name",p.boundary_json AS "boundaryJson",
        p.source AS "source",p.source_note AS "sourceNote",p.revision AS "revision",q.name AS "plotName",q.crop AS "crop"
      FROM farm_map_parcels p JOIN plots q ON q.tenant_id=p.tenant_id AND q.id=p.plot_id
      WHERE p.tenant_id=? AND p.farm_id=? ORDER BY p.name,p.id
      """,tenant,farm);
    for(var p:parcels){var ring=boundary(p.remove("boundaryJson"));p.put("boundary",ring);p.put("estimatedAreaMu",FieldGeometry.areaMu(ring));}
    var zones=db.queryForList("""
      SELECT z.id AS "id",z.parcel_id AS "parcelId",p.plot_id AS "plotId",z.pump_id AS "pumpId",z.name AS "name",
        z.pipeline_json AS "pipelineJson",z.nodes_json AS "nodesJson",p.boundary_json AS "coverageJson",z.source AS "source"
      FROM farm_map_zones z JOIN farm_map_parcels p ON p.tenant_id=z.tenant_id AND p.id=z.parcel_id AND p.farm_id=z.farm_id
      WHERE z.tenant_id=? AND z.farm_id=? ORDER BY z.name
      """,tenant,farm);
    for(var z:zones) for(String k:List.of("pipeline","nodes","coverage"))z.put(k,decode(z.remove(k+"Json")));
    var jobs=db.queryForList("SELECT * FROM farm_map_jobs WHERE tenant_id=? AND farm_id=? ORDER BY created_at DESC,id DESC LIMIT 100",tenant,farm).stream().map(this::publicJob).toList();
    var totals=db.queryForMap("SELECT COALESCE(SUM(estimated_m3),0) AS \"estimatedM3\",SUM(measured_m3) AS \"measuredM3\",COUNT(*) AS \"runs\" FROM farm_map_jobs WHERE tenant_id=? AND farm_id=? AND kind='IRRIGATION'",tenant,farm);
    return Map.of("parcels",parcels,"zones",zones,"jobs",jobs,"waterTotals",totals,"coordinateSystem","WGS84","executionScope","SIMULATED_ONLY");
  }
  public Map<String,Object> addParcel(String farm,ParcelInput input) {
    Identity.require("ADMIN");store.lock("farms",farm);var plot=store.get("plots",input.plotId());
    if(!farm.equals(plot.get("FARM_ID")))throw new ApiException(400,"小田块必须属于当前农场的经营地块");
    FieldGeometry.validate(input.boundary());
    var references=db.queryForList("SELECT latitude,longitude,width_meters,height_meters FROM farm_georeference WHERE tenant_id=? AND farm_id=?",Identity.tenant(),farm);
    if(references.isEmpty())throw new ApiException(409,"请先保存农场位置与地图范围");
    var g=references.getFirst();double lat=((Number)g.get("LATITUDE")).doubleValue(),lng=((Number)g.get("LONGITUDE")).doubleValue();
    for(var p:input.boundary()) if(Math.abs(p.getFirst()-lat)*111320>((Number)g.get("HEIGHT_METERS")).doubleValue()/2+2 || Math.abs(p.get(1)-lng)*111320*Math.cos(Math.toRadians(lat))>((Number)g.get("WIDTH_METERS")).doubleValue()/2+2)
      throw new ApiException(400,"小田块超出当前农场配置范围，请先核对位置");
    if(db.queryForObject("SELECT COUNT(*) FROM farm_map_parcels WHERE tenant_id=? AND farm_id=?",Integer.class,Identity.tenant(),farm)>=100)throw new ApiException(409,"单农场最多保存 100 个小田块");
    String id=UUID.randomUUID().toString();
    db.update("INSERT INTO farm_map_parcels(id,tenant_id,farm_id,plot_id,name,boundary_json,source,source_note,created_at) VALUES(?,?,?,?,?,?,'USER_ESTIMATE','用户按底图估绘，未实测',?)",id,Identity.tenant(),farm,input.plotId(),input.name().strip(),encode(input.boundary()),OffsetDateTime.now());
    store.audit("MAP_PARCEL_ESTIMATED",id);return Map.of("id",id);
  }
  private void activeFarm(String farm) {
    store.get("farms",farm);
    if(db.queryForObject("SELECT COUNT(*) FROM farm_archives WHERE tenant_id=? AND farm_id=?",Integer.class,Identity.tenant(),farm)>0)throw new ApiException(409,"该农场已归档，请在农场档案中恢复后操作");
  }
  public Map<String,Object> preview(String farm,WorkInput input) {
    activeFarm(farm);var p=parcel(Identity.tenant(),farm,input.parcelId());var d=assets.detail(input.deviceId());
    if(!farm.equals(d.get("farmId")) || !"MACHINERY".equals(d.get("deviceType")))throw new ApiException(400,"请选择当前农场的农机终端");
    @SuppressWarnings("unchecked") var profile=(Map<String,Object>)d.getOrDefault("machinery",Map.of("kind","GENERIC","model",d.get("model"),"taskTypes",List.of("INSPECTION","SOWING","FERTILIZING","HARVEST","PROTECTION")));
    boolean drone="DRONE".equals(profile.get("kind"));
    if(!Double.isFinite(input.speedKmh())||input.speedKmh()<1||input.speedKmh()>(drone?40:12))throw new ApiException(400,drone?"无人机规划速度应为 1–40 km/h":"地面农机规划速度应为 1–12 km/h");
    if(input.taskType()==null||!((List<?>)profile.get("taskTypes")).contains(input.taskType()))throw new ApiException(400,"所选农机不支持该作业类型");
    if(drone&&(input.altitudeMeters()==null||!Double.isFinite(input.altitudeMeters())||input.altitudeMeters()<2||input.altitudeMeters()>30))throw new ApiException(400,"请填写 2–30 m 的模拟相对作物飞行高度");
    var ring=boundary(p.get("BOUNDARY_JSON"));boolean manual="MANUAL".equals(input.planningMode());
    double angle=Boolean.TRUE.equals(input.recommendBearing())?FieldGeometry.recommendedBearing(ring):input.bearing();
    var route=manual?FieldGeometry.manual(ring,input.manualPoints(),input.widthMeters(),input.headlandMeters()):FieldGeometry.route(ring,input.widthMeters(),angle,input.headlandMeters());
    var points=new ArrayList<>(route.points());if(input.reverse())Collections.reverse(points);
    int rate=input.simulationRate()==null?10:input.simulationRate();
    double expectedSeconds=route.lengthMeters()/(input.speedKmh()/3.6);
    int simulationSeconds=Math.max(1,(int)Math.ceil(expectedSeconds/rate));
    if(simulationSeconds>86400)throw new ApiException(400,"模拟任务超过 24 小时，请拆分路线或提高演示倍速");
    var result=new LinkedHashMap<String,Object>();
    result.put("points",points);result.put("lengthMeters",route.lengthMeters());result.put("workAreaMu",manual?null:route.areaMu());result.put("passes",route.passes());
    result.put("estimatedMinutes",expectedSeconds/60);result.put("simulationSeconds",simulationSeconds);result.put("simulationRate",rate);
    result.put("bearing",angle);result.put("planningMode",manual?"MANUAL":"AUTO");result.put("machine",profile);result.put("parcelId",input.parcelId());result.put("deviceId",input.deviceId());
    result.put("note",(drone?"模拟航线；边界留退让，飞行高度为相对作物高度。":"规划中心线；已预留地头，未验证转弯半径。")+"已检查所选田块边界；未接入田内障碍、道路通行、禁飞区及厂家导航。"+(manual?"手绘路线的重叠覆盖面积未计算。":"覆盖面积为内缩作业区域估算。"));
    return result;
  }
  private void lockTenant(){if(db.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=TRUE FOR UPDATE",Identity.tenant()).isEmpty())throw new ApiException(403,"当前租户已停用");}
  private Map<String,Object> prior(String farm,String request,String params,String kind) {
    var rows=db.queryForList("SELECT * FROM farm_map_jobs WHERE tenant_id=? AND request_id=?",Identity.tenant(),request);
    if(rows.isEmpty())return null;var old=rows.getFirst();
    if(!farm.equals(old.get("FARM_ID"))||!kind.equals(old.get("KIND"))||!params.equals(old.get("PARAMETERS_JSON"))||!Identity.current().memberId().equals(old.get("OWNER_ID")))throw new ApiException(409,"请求编号已使用，重试必须保持同一操作内容");
    return publicJob(old);
  }
  private void available(Map<String,Object> asset,String farm,String type) {
    if(!farm.equals(asset.get("farmId")) || !type.equals(asset.get("deviceType")))throw new ApiException(400,"设备与当前农场或作业类型不匹配");
    if(!"SIMULATED".equals(asset.get("protocol")))throw new ApiException(409,"该流程仅支持模拟设备；实体农机调度和网关限时灌溉尚未接入");
    if(!"ACTIVE".equals(asset.get("lifecycle")) || !"FRESH".equals(asset.get("freshness")))throw new ApiException(409,"设备停用、维护中或反馈过期，请先检查并采集");
    if(((Number)asset.get("alertCount")).intValue()>0)throw new ApiException(409,"设备存在告警，请先处理后再下发");
    if(db.queryForObject("SELECT COUNT(*) FROM farm_map_jobs WHERE tenant_id=? AND device_id=? AND status IN ('RUNNING','PAUSED')",Integer.class,Identity.tenant(),asset.get("id"))>0)throw new ApiException(409,"设备已有未结束的地图任务，请先停止或完成");
  }
  public Map<String,Object> dispatch(String farm,WorkInput input) {
    Identity.require("ADMIN");activeFarm(farm);lockTenant();String params=encode(input);var old=prior(farm,input.requestId(),params,"MACHINERY");if(old!=null)return old;
    store.lock("devices",input.deviceId());var d=assets.detail(input.deviceId());available(d,farm,"MACHINERY");var p=parcel(Identity.tenant(),farm,input.parcelId());
    var route=preview(farm,input);String id=UUID.randomUUID().toString();
    if(input.taskType()==null)throw new ApiException(400,"请选择作业类型");
    @SuppressWarnings("unchecked") var task=(Map<String,Object>)fieldwork.create(new FieldWorkService.PlanInput(p.get("PLOT_ID").toString(),"[模拟农机] "+input.title().strip(),input.taskType(),LocalDate.now(),Identity.current().memberId(),"SERVICE","地图模拟任务；按估绘边界规划，不代表真实生产作业。"),null);
    insert(id,farm,p,input.deviceId(),task.get("id").toString(),input.requestId(),"MACHINERY",input.title().strip(),params,encode(route.get("points")),Math.max(10,Math.min(300,((Number)route.get("simulationSeconds")).intValue())),null,null);
    db.update("INSERT INTO machinery_job_plans(tenant_id,job_id,snapshot_json,simulation_seconds) VALUES(?,?,?,?)",Identity.tenant(),id,encode(route),route.get("simulationSeconds"));
    event(Identity.tenant(),id,"ACCEPTED","模拟调度已接收；型号、路线与参数快照已保存，未发送至实体设备");
    syncTask(Identity.tenant(),id,"RUNNING","模拟作业已下发，实机定位轨迹未接入");store.audit("MAP_MACHINERY_DISPATCH",id);return job(farm,id);
  }
  public Map<String,Object> water(String farm,WaterInput input) {
    Identity.require("ADMIN","OPERATOR");activeFarm(farm);lockTenant();String params=encode(input);var old=prior(farm,input.requestId(),params,"IRRIGATION");if(old!=null)return old;
    var z=zone(farm,input.zoneId());String pump=z.get("PUMP_ID").toString();store.lock("devices",pump);var d=assets.detail(pump);available(d,farm,"PUMP");
    String id=UUID.randomUUID().toString();
    var command=commands.create(pump,new DeviceCommandService.Input("map-start-"+id,"PUMP_START",null,"地图分区定时模拟灌溉 "+input.durationSeconds()+" 秒"));
    if(!"SUCCEEDED".equals(command.get("status")))throw new ApiException(409,"模拟水泵未确认启动");
    var flow=assets.latest(Identity.tenant(),pump,"FLOW");
    if(flow==null || ((Number)flow.get("value")).doubleValue()<=0)throw new ApiException(409,"缺少有效模拟流量，无法计算用水估算");
    insert(id,farm,parcel(Identity.tenant(),farm,z.get("PARCEL_ID").toString()),pump,null,input.requestId(),"IRRIGATION",z.get("NAME").toString(),params,"[]",input.durationSeconds(),command.get("id").toString(),new BigDecimal(flow.get("value").toString()));
    event(Identity.tenant(),id,"ACCEPTED","模拟水泵已确认启动");store.audit("MAP_IRRIGATION_START",id);return job(farm,id);
  }
  private void insert(String id,String farm,Map<String,Object> p,String device,String task,String request,String kind,String title,String params,String route,int duration,String command,BigDecimal flow) {
    var now=OffsetDateTime.now();
    db.update("""
      INSERT INTO farm_map_jobs(id,tenant_id,farm_id,plot_id,parcel_id,device_id,task_id,owner_id,request_id,kind,title,status,
        parameters_json,route_json,boundary_json,duration_seconds,start_command_id,flow_m3h,created_at,started_at,last_tick_at,result_note)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?,?,?,'模拟执行中；未操作实体设备')
      """,id,Identity.tenant(),farm,p.get("PLOT_ID"),p.get("ID"),device,task,Identity.current().memberId(),request,kind,title,params,route,p.get("BOUNDARY_JSON"),duration,command,flow,now,now,now);
  }
  private Map<String,Object> storedJob(String tenant,String farm,String id,boolean lock) {
    var rows=db.queryForList("SELECT * FROM farm_map_jobs WHERE tenant_id=? AND farm_id=? AND id=?"+(lock?" FOR UPDATE":""),tenant,farm,id);
    if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
  }
  public Map<String,Object> job(String farm,String id) {store.get("farms",farm);return publicJob(storedJob(Identity.tenant(),farm,id,false));}
  private Map<String,Object> publicJob(Map<String,Object> row) {
    var result=new LinkedHashMap<String,Object>();
    String[][] keys={{"id","ID"},{"parcelId","PARCEL_ID"},{"plotId","PLOT_ID"},{"deviceId","DEVICE_ID"},{"taskId","TASK_ID"},{"kind","KIND"},{"title","TITLE"},{"status","STATUS"},{"durationSeconds","DURATION_SECONDS"},{"elapsedSeconds","ELAPSED_SECONDS"},{"estimatedM3","ESTIMATED_M3"},{"measuredM3","MEASURED_M3"},{"resultNote","RESULT_NOTE"},{"startedAt","STARTED_AT"},{"finishedAt","FINISHED_AT"},{"flowM3h","FLOW_M3H"}};
    for(var k:keys)result.put(k[0],row.get(k[1]));
    result.put("route",decode(row.get("ROUTE_JSON")));result.put("boundary",decode(row.get("BOUNDARY_JSON")));
    result.put("parameters",decode(row.get("PARAMETERS_JSON")));
    var plans=db.queryForList("SELECT snapshot_json,simulation_seconds FROM machinery_job_plans WHERE tenant_id=? AND job_id=?",row.get("TENANT_ID"),row.get("ID"));
    double duration=duration(row);result.put("durationSeconds",duration);
    if(!plans.isEmpty())result.put("plan",decode(plans.getFirst().get("SNAPSHOT_JSON")));
    result.put("progress",Math.min(100,100*((Number)row.get("ELAPSED_SECONDS")).doubleValue()/duration));
    result.put("events",db.queryForList("SELECT action AS \"action\",note AS \"note\",occurred_at AS \"occurredAt\" FROM farm_map_job_events WHERE tenant_id=? AND job_id=? ORDER BY occurred_at,id",row.get("TENANT_ID"),row.get("ID")));
    result.put("receiptId","SIM-"+row.get("ID"));result.put("executionSource","SIMULATED");result.put("actualTrack",List.of());return result;
  }
  private double duration(Map<String,Object> row) {
    var seconds=db.queryForList("SELECT simulation_seconds FROM machinery_job_plans WHERE tenant_id=? AND job_id=?",Integer.class,row.get("TENANT_ID"),row.get("ID"));
    return seconds.isEmpty()?((Number)row.get("DURATION_SECONDS")).doubleValue():seconds.getFirst();
  }
  private void event(String tenant,String job,String action,String note) {
    db.update("INSERT INTO farm_map_job_events(id,tenant_id,job_id,action,note,occurred_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),tenant,job,action,note,OffsetDateTime.now());
  }

  public Map<String,Object> act(String farm,String id,String action) {
    Identity.require("ADMIN","OPERATOR");store.get("farms",farm);lockTenant();var initial=storedJob(Identity.tenant(),farm,id,false);store.lock("devices",initial.get("DEVICE_ID").toString());
    var r=storedJob(Identity.tenant(),farm,id,true);
    if(!Set.of("RUNNING","PAUSED").contains(r.get("STATUS")))return publicJob(r);
    if(!action.equals("STOP") && !"MACHINERY".equals(r.get("KIND")))throw new ApiException(409,"当前灌溉设备仅支持启动和停止，不支持暂停续灌");
    advance(r,Instant.now());r=storedJob(Identity.tenant(),farm,id,true);
    if(!Set.of("RUNNING","PAUSED").contains(r.get("STATUS")))return publicJob(r);
    if(action.equals("STOP"))finish(r,"STOPPED","人工停止模拟任务");
    else if(action.equals("PAUSE") && "RUNNING".equals(r.get("STATUS")))db.update("UPDATE farm_map_jobs SET status='PAUSED',result_note='模拟任务已暂停' WHERE tenant_id=? AND id=?",Identity.tenant(),id);
    else if(action.equals("RESUME") && "PAUSED".equals(r.get("STATUS")))db.update("UPDATE farm_map_jobs SET status='RUNNING',last_tick_at=?,result_note='模拟任务继续执行' WHERE tenant_id=? AND id=?",OffsetDateTime.now(),Identity.tenant(),id);
    event(Identity.tenant(),id,action,"模拟任务操作："+action);store.audit("MAP_JOB_"+action,id);return job(farm,id);
  }
  /** System entry point: tenant is taken from the persisted job, never a request argument. */
  public void tick(String tenant,String farm,String id) {
    var initial=storedJob(tenant,farm,id,false);db.queryForList("SELECT id FROM devices WHERE tenant_id=? AND id=? FOR UPDATE",tenant,initial.get("DEVICE_ID"));
    var r=storedJob(tenant,farm,id,true);advance(r,Instant.now());
  }
  private void advance(Map<String,Object> r,Instant now) {
    if(!"RUNNING".equals(r.get("STATUS")))return;
    String t=r.get("TENANT_ID").toString(),id=r.get("ID").toString(),device=r.get("DEVICE_ID").toString();
    double prior=((Number)r.get("ELAPSED_SECONDS")).doubleValue(),duration=duration(r);
    double delta=Math.max(0,Duration.between(DatabaseTime.offset(r.get("LAST_TICK_AT")).toInstant(),now).toMillis()/1000.0),elapsed=Math.min(duration,prior+delta);
    double estimated=((Number)r.get("ESTIMATED_M3")).doubleValue();
    if("IRRIGATION".equals(r.get("KIND")))estimated+=((Number)r.get("FLOW_M3H")).doubleValue()*(elapsed-prior)/3600;
    db.update("UPDATE farm_map_jobs SET elapsed_seconds=?,estimated_m3=?,last_tick_at=? WHERE tenant_id=? AND id=?",elapsed,estimated,now.atOffset(ZoneOffset.UTC),t,id);
    if(db.queryForObject("SELECT COUNT(*) FROM asset_profiles a JOIN tenants t ON t.id=a.tenant_id JOIN members m ON m.tenant_id=a.tenant_id AND m.id=? WHERE a.tenant_id=? AND a.device_id=? AND a.protocol='SIMULATED' AND a.lifecycle='ACTIVE' AND t.enabled=TRUE AND m.enabled=TRUE AND m.role IN ('ADMIN','OPERATOR')",Integer.class,r.get("OWNER_ID"),t,device)==0) {finish(r,"STOPPED","设备配置、操作者或租户状态已变化，停止模拟任务");return;}
    if("IRRIGATION".equals(r.get("KIND"))) {
      var pump=assets.latest(t,device,"PUMP_RUNNING");
      var fault=assets.latest(t,device,"FAULT");
      if(pump==null || ((Number)pump.get("value")).intValue()!=1 || fault==null || ((Number)fault.get("value")).intValue()!=0) {finish(r,"STOPPED","水泵已停止或出现故障，结束模拟灌溉");return;}
    }
    if(elapsed>=duration)finish(r,"COMPLETED","模拟任务已完成");
  }
  private void finish(Map<String,Object> r,String status,String note) {
    String tenant=r.get("TENANT_ID").toString(),id=r.get("ID").toString();String stop=null;
    if("IRRIGATION".equals(r.get("KIND")))stop=commands.stopMapSimulation(tenant,id);
    db.update("UPDATE farm_map_jobs SET status=?,finished_at=?,stop_command_id=?,result_note=? WHERE tenant_id=? AND id=?",status,OffsetDateTime.now(),stop,note+("IRRIGATION".equals(r.get("KIND"))?"；用水为模拟流量积分估算，未实测":"；未接入真实农机执行与轨迹"),tenant,id);
    event(tenant,id,status,note);
    syncTask(tenant,id,status.equals("COMPLETED")?"COMPLETED":"CANCELLED",note+"；非真实生产作业");
  }
  private void syncTask(String tenant,String id,String status,String note) {
    var r=db.queryForMap("SELECT task_id,owner_id,boundary_json,parameters_json FROM farm_map_jobs WHERE tenant_id=? AND id=?",tenant,id);if(r.get("TASK_ID")==null)return;
    Object task=r.get("TASK_ID");db.update("UPDATE farm_tasks SET status=? WHERE tenant_id=? AND id=?",status,tenant,task);
    double area=0;
    if(status.equals("COMPLETED")) {
      @SuppressWarnings("unchecked") var params=(Map<String,Object>)decode(r.get("PARAMETERS_JSON"));
      var plans=db.queryForList("SELECT snapshot_json FROM machinery_job_plans WHERE tenant_id=? AND job_id=?",String.class,tenant,id);
      if(!plans.isEmpty()) {var snapshot=(Map<?,?>)decode(plans.getFirst());area=snapshot.get("workAreaMu") instanceof Number n?n.doubleValue():0;}
      else area=FieldGeometry.route(boundary(r.get("BOUNDARY_JSON")),((Number)params.get("widthMeters")).doubleValue(),((Number)params.get("bearing")).doubleValue(),((Number)params.get("headlandMeters")).doubleValue()).areaMu();
      if("MANUAL".equals(params.get("planningMode")))note+="；手绘路线未核定面积，面积字段保留 0（未测），不表示无作业";
      note+="；作业面积为模拟规划覆盖估算，未实测";
      db.update("UPDATE task_fieldwork SET completed_at=?,completion_note=?,actual_area_mu=? WHERE tenant_id=? AND task_id=?",java.sql.Timestamp.from(Instant.now()),note,area,tenant,task);
    }
    db.update("INSERT INTO field_work_logs(id,tenant_id,task_id,actor_id,action,note,method,actual_area_mu,occurred_at) VALUES(?,?,?,?,?,?,'SERVICE',?,?)",UUID.randomUUID().toString(),tenant,task,r.get("OWNER_ID"),status,note,area,java.sql.Timestamp.from(Instant.now()));
  }
}
