package app.zhinong.bootstrap;
import app.zhinong.workspace.MetricCatalog;
import java.time.LocalDate;
/** Transparent mathematical assumptions for synthetic research signals. */
public final class ResearchSignals {
  private ResearchSignals() {}
  public static double reading(String metric,LocalDate day,double hour,String plot) {
    double season=Math.sin((day.getDayOfYear()-100)*Math.PI*2/365.25),diurnal=Math.sin((hour-8)*Math.PI*2/24);
    double rain=(day.toEpochDay()%11==0?12:day.toEpochDay()%7==0?4:0);
    double variation=(Math.floorMod(plot.hashCode(),7)-3)*0.4;
    int layer=metric.endsWith("_3")?3:metric.endsWith("_2")?2:1;
    var spec=MetricCatalog.get(metric);
    double value=switch(metric) {
      case "TEMPERATURE" -> 10+16*season+4*diurnal;
      case "SOIL_TEMPERATURE", "SOIL_TEMPERATURE_2", "SOIL_TEMPERATURE_3" -> 13+10*season+1.5*diurnal/layer;
      case "HUMIDITY" -> 63-13*diurnal+Math.min(20,rain*1.2);
      case "SOIL_MOISTURE", "SOIL_MOISTURE_2", "SOIL_MOISTURE_3" -> 37+2*(layer-1)+4*season+Math.min(9,rain*0.7)/layer-2*diurnal/layer+variation;
      case "SOIL_EC", "SOIL_EC_2", "SOIL_EC_3" -> 680+20*layer-20*season-Math.min(9,rain*0.7)*8/layer;
      case "RAINFALL" -> rain*Math.max(0,Math.min(1,(hour-7)/6));
      case "LIGHT" -> Math.max(0,Math.sin((hour-6)*Math.PI/12))*45000;
      case "CAMERA_ONLINE", "REMOTE_ENABLED" -> 1;
      case "ENERGY", "WATER_TOTAL" -> spec.normal();
      case "WATER_LEVEL" -> spec.normal()+rain*0.01+0.04*season;
      case "WIND_SPEED" -> 2.3+Math.abs(Math.sin(day.toEpochDay()/3.0))*1.7;
      case "PEST_COUNT" -> Math.max(0,8+11*season+(day.toEpochDay()%9));
      case "FAULT", "PUMP_RUNNING", "STANDBY_RUNNING", "PUMP_FREQUENCY", "CURRENT", "FLOW" -> 0;
      case "GATE_OPENING" -> 0;
      default -> spec.normal()+Math.sin(day.toEpochDay()/8.0)*Math.abs(spec.normal())*0.025;
    };
    value=Math.max(spec.min(),Math.min(spec.max(),value));
    return MetricCatalog.discrete(metric)?Math.round(value):value;
  }
}
