package app.zhinong.risk;

import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Transparent screening of a current public forecast, not official warnings or crop prescriptions. */
public final class WeatherRiskRules {
  private WeatherRiskRules() {}
  @SuppressWarnings("unchecked")
  public static List<Map<String,Object>> evaluate(Map<String,Object> forecast,Instant now) {
    if(!usable(forecast,now))return List.of();
    var days=(List<Map<String,Object>>)forecast.getOrDefault("days",List.of());
    var results=new ArrayList<Map<String,Object>>();
    rule(results,days,"HEAT","高温作业风险","WARNING",d->atLeast(d,"tempMaxC",35),
      List.of("tempMaxC"),"最高温达到35℃教学筛查阈值；调整露天作业时段，核查遮阴、通风和人员防暑，结合现场确认。","℃");
    rule(results,days,"FROST","低温与霜冻线索","HIGH",d->atMost(d,"tempMinC",0),
      List.of("tempMinC"),"最低温不高于0℃；核查作物耐寒性和设施保温条件，关注当地气象部门预警。不能据网格温度认定地块已结霜。","℃");
    rule(results,days,"RAIN","强降水与排水风险","HIGH",d->atLeast(d,"precipitationMm",25),
      List.of("precipitationMm"),"日降水达到25mm教学筛查阈值；提前巡查排水沟、低洼区和电气设施，复核田间积水。","mm");
    rule(results,days,"GUST","大风与设施风险","HIGH",d->atLeast(d,"gustMaxMs",10),
      List.of("gustMaxMs"),"阵风达到10m/s教学筛查阈值；核查设施固定与高空、无人机、喷施作业条件，是否作业按现场与设备限制作人工决定。","m/s");
    rule(results,days,"THUNDER","雷暴天气线索","HIGH",d->number(d,"weatherCode")!=null && Set.of(95d,96d,99d).contains(number(d,"weatherCode")),
      List.of("weatherCode"),"预报天气代码含雷暴；关注官方预警，避开雷电环境下的露天与电气维护作业。","WMO");
    var cooling=new ArrayList<Map<String,Object>>();
    for(int i=1;i<days.size();i++) {
      var previous=days.get(i-1);var current=days.get(i);
      Double a=number(previous,"tempMaxC"),b=number(current,"tempMaxC");
      if(a!=null&&b!=null&&LocalDate.parse(previous.get("date").toString()).plusDays(1).toString().equals(current.get("date").toString())&&a-b>=8)cooling.add(current);
    }
    if(!cooling.isEmpty()) {
      var item=item("COOLING","短期明显降温","WARNING",cooling,
        "相邻两日最高温预报下降至少8℃；复核苗期和设施保温措施。该规则不是作物冻害诊断。");
      var evidence=new ArrayList<String>();
      for(var day:cooling){int i=days.indexOf(day);evidence.add(days.get(i-1).get("date")+" → "+day.get("date")+"：最高温 "+days.get(i-1).get("tempMaxC")+" → "+day.get("tempMaxC")+"℃");}
      item.put("evidence",evidence);results.add(item);
    }
    return results;
  }

  private static void rule(List<Map<String,Object>> out,List<Map<String,Object>> days,String code,String title,String severity,
      Predicate<Map<String,Object>> predicate,List<String> values,String recommendation,String unit) {
    var matching=days.stream().filter(predicate).toList();if(matching.isEmpty())return;
    var item=item(code,title,severity,matching,recommendation);
    item.put("evidence",matching.stream().map(d->d.get("date")+"："+d.get(values.getFirst())+" "+unit).toList());out.add(item);
  }
  private static Map<String,Object> item(String code,String title,String severity,List<Map<String,Object>> days,String recommendation) {
    var item=new LinkedHashMap<String,Object>();item.put("id","weather:"+code);item.put("source","WEATHER");
    item.put("severity",severity);item.put("title",title);item.put("status","FORECAST");
    item.put("affectedDate",days.getFirst().get("date"));item.put("endDate",days.getLast().get("date"));
    item.put("affectedDates",days.stream().map(d->d.get("date")).toList());item.put("recommendation",recommendation);
    item.put("action",Map.of("target","forecast","label","核对七日预报"));item.put("ruleVersion","weather-screen-v1");return item;
  }
  private static boolean validUntil(Object value,Instant now) {
    try{return value!=null&&Instant.parse(value.toString()).isAfter(now);}catch(DateTimeException e){return false;}
  }
  public static boolean usable(Map<String,Object> forecast,Instant now){return "READY".equals(forecast.get("status"))&&validUntil(forecast.get("expiresAt"),now);}
  private static Double number(Map<String,Object> d,String key) {Object v=d.get(key);return v instanceof Number n&&Double.isFinite(n.doubleValue())?n.doubleValue():null;}
  private static boolean atLeast(Map<String,Object> d,String key,double n){Double v=number(d,key);return v!=null&&v>=n;}
  private static boolean atMost(Map<String,Object> d,String key,double n){Double v=number(d,key);return v!=null&&v<=n;}
}
