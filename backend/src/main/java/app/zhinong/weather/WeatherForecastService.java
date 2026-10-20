package app.zhinong.weather;

import app.zhinong.business.FarmClock;
import app.zhinong.business.Store;
import app.zhinong.security.Identity;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Authorizes every read, resolves only saved farm centres, and owns all forecast cache policy. */
@Service
public class WeatherForecastService {
  static final Duration TTL=Duration.ofMinutes(30),BACKOFF=Duration.ofMinutes(2),MAX_STALE=Duration.ofHours(6);
  static final int CAPACITY=128;
  private final Store store;
  private final FarmClock clock;
  private final WeatherForecastGateway gateway;
  private final boolean enabled;
  private final LinkedHashMap<Key,Entry> cache=new LinkedHashMap<>(16,0.75f,true);
  private final Semaphore requests=new Semaphore(8);
  private record Location(double latitude,double longitude,String label,int revision) {}
  private record Key(String tenant,String farm,double latitude,double longitude,int revision,String zone,LocalDate day) {
    boolean sameLocation(Key other) {
      return tenant.equals(other.tenant) && farm.equals(other.farm) && Double.compare(latitude,other.latitude)==0 &&
        Double.compare(longitude,other.longitude)==0 && revision==other.revision && zone.equals(other.zone);
    }
  }
  private record Payload(List<WeatherForecastGateway.Day> days,LocalDate businessDate,Instant fetchedAt,Instant expiresAt) {}
  private record Outcome(Payload payload,String diagnostic) {}
  private static final class Entry {
    volatile Payload payload;Instant retryAt;String failure;CompletableFuture<Outcome> pending;
    Entry(Payload payload) {this.payload=payload;}
  }

  @Autowired public WeatherForecastService(Store store,FarmClock clock,WeatherForecastGateway gateway,
      @Value("${farm.weather.enabled:true}") boolean enabled) {
    this.store=store;this.clock=clock;this.gateway=gateway;this.enabled=enabled;
  }

  /** No business writes or long-lived transaction; only coordinates, zone and weather fields leave this module. */
  public Map<String,Object> get(String farmId) {
    String tenant=Identity.tenant();store.get("farms",farmId);
    var location=location(tenant,farmId);
    if(!enabled)return result(farmId,location,"DISABLED","FORECAST_DISABLED",null);
    if(location==null)return result(farmId,null,"UNCONFIGURED","LOCATION_NOT_SAVED",null);
    var today=clock.today();
    var key=new Key(tenant,farmId,location.latitude(),location.longitude(),location.revision(),clock.zone().getId(),today);
    Outcome outcome=resolve(key);
    var current=location(tenant,farmId);
    if(!Objects.equals(location,current))return result(farmId,current,current==null?"UNCONFIGURED":"UNAVAILABLE","LOCATION_CHANGED",null);
    var payload=outcome.payload();var now=clock.instant();
    if(payload==null || !payload.fetchedAt().plus(MAX_STALE).isAfter(now))
      return result(farmId,location,"UNAVAILABLE",outcome.diagnostic()==null?"NO_CURRENT_FORECAST":outcome.diagnostic(),null);
    boolean fresh=outcome.diagnostic()==null && payload.businessDate().equals(clock.today()) && payload.expiresAt().isAfter(now);
    return result(farmId,location,fresh?"READY":"STALE",fresh?"OK":outcome.diagnostic()==null?"CACHE_EXPIRED":outcome.diagnostic(),payload);
  }

  private Location location(String tenant,String farm) {
    var rows=store.db().queryForList("SELECT latitude,longitude,location_label,revision FROM farm_georeference WHERE tenant_id=? AND farm_id=?",tenant,farm);
    if(rows.isEmpty())return null;
    var row=rows.getFirst();double lat=((Number)row.get("LATITUDE")).doubleValue(),lon=((Number)row.get("LONGITUDE")).doubleValue();
    if(!Double.isFinite(lat) || !Double.isFinite(lon) || lat< -80 || lat>80 || lon< -180 || lon>180)return null;
    return new Location(lat,lon,row.get("LOCATION_LABEL").toString(),((Number)row.get("REVISION")).intValue());
  }

  private Outcome resolve(Key key) {
    Entry entry;CompletableFuture<Outcome> pending;boolean owner=false;var now=clock.instant();
    synchronized(cache) {
      entry=cache.get(key);
      if(entry==null) {
        Payload previous=null;
        for(var candidate:cache.entrySet())if(key.sameLocation(candidate.getKey()) && candidate.getValue().payload!=null) {
          var value=candidate.getValue().payload;
          if(previous==null || value.fetchedAt().isAfter(previous.fetchedAt()))previous=value;
        }
        if(cache.size()>=CAPACITY) {
          var iterator=cache.entrySet().iterator();boolean removed=false;
          while(iterator.hasNext())if(iterator.next().getValue().pending==null) {iterator.remove();removed=true;break;}
          if(!removed)return new Outcome(null,"BUSY");
        }
        entry=new Entry(previous);cache.put(key,entry);
      }
      if(entry.payload!=null && entry.payload.businessDate().equals(key.day()) && entry.payload.expiresAt().isAfter(now))
        return new Outcome(entry.payload,null);
      if(entry.retryAt!=null && entry.retryAt.isAfter(now))return new Outcome(entry.payload,entry.failure);
      pending=entry.pending;
      if(pending==null) {pending=new CompletableFuture<>();entry.pending=pending;owner=true;}
    }
    if(!owner)try { return pending.get(7,TimeUnit.SECONDS); }
    catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return new Outcome(entry.payload,"INTERRUPTED");}
    catch(ExecutionException | TimeoutException unavailable) {return new Outcome(entry.payload,"TIMEOUT");}
    Outcome outcome;
    boolean acquired=requests.tryAcquire();
    try {
      if(!acquired)throw new WeatherForecastGateway.Unavailable("BUSY");
      var days=gateway.fetch(key.latitude(),key.longitude(),ZoneId.of(key.zone()),key.day());
      if(days==null || days.stream().noneMatch(d -> d!=null && d.hasData() && !d.date().isBefore(key.day()) && !d.date().isAfter(key.day().plusDays(6))))
        throw new WeatherForecastGateway.Unavailable("INVALID_RESPONSE");
      var fetched=clock.instant();var payload=new Payload(List.copyOf(days),key.day(),fetched,fetched.plus(TTL));
      synchronized(cache) {entry.payload=payload;entry.failure=null;entry.retryAt=null;}
      outcome=new Outcome(payload,null);
    } catch(RuntimeException failure) {
      String diagnostic=failure instanceof WeatherForecastGateway.Unavailable unavailable?unavailable.diagnostic:"UNAVAILABLE";
      synchronized(cache) {entry.failure=diagnostic;entry.retryAt=clock.instant().plus(BACKOFF);outcome=new Outcome(entry.payload,diagnostic);}
    } catch(Error fatal) {
      pending.completeExceptionally(fatal);throw fatal;
    } finally {
      if(acquired)requests.release();
      synchronized(cache) {entry.pending=null;}
    }
    pending.complete(outcome);return outcome;
  }

  private Map<String,Object> result(String farm,Location location,String status,String diagnostic,Payload payload) {
    var checkedAt=clock.instant();
    if(payload!=null && !payload.fetchedAt().plus(MAX_STALE).isAfter(checkedAt)) {
      payload=null;status="UNAVAILABLE";diagnostic="CACHE_TOO_OLD";
    } else if(payload!=null && "READY".equals(status) && (!payload.expiresAt().isAfter(checkedAt) ||
        !payload.businessDate().equals(checkedAt.atZone(clock.zone()).toLocalDate()))) {
      status="STALE";diagnostic="CACHE_EXPIRED";
    }
    var result=new LinkedHashMap<String,Object>();boolean available=payload!=null;
    result.put("farmId",farm);result.put("status",status);result.put("source","OPEN_METEO");result.put("dataKind","PUBLIC_FORECAST");
    result.put("available",available);result.put("usable","READY".equals(status));result.put("stale","STALE".equals(status));
    result.put("location",location==null?null:Map.of("latitude",location.latitude(),"longitude",location.longitude(),"label",location.label()));
    result.put("timeZone",clock.zone().getId());result.put("checkedAt",checkedAt);result.put("fetchedAt",payload==null?null:payload.fetchedAt());
    result.put("expiresAt",payload==null?null:payload.expiresAt());result.put("diagnostic",diagnostic);
    var days=new ArrayList<Map<String,Object>>();
    if(payload!=null) {
      var byDate=new HashMap<LocalDate,WeatherForecastGateway.Day>();payload.days().forEach(d -> byDate.put(d.date(),d));
      LocalDate today=checkedAt.atZone(clock.zone()).toLocalDate();
      for(int i=0;i<7;i++) {
        LocalDate date=today.plusDays(i);var day=byDate.get(date);var row=new LinkedHashMap<String,Object>();
        row.put("date",date.toString());row.put("weatherCode",day==null?null:day.weatherCode());
        row.put("weatherLabel",day==null || day.weatherCode()==null?null:weatherLabel(day.weatherCode()));
        row.put("tempMinC",day==null?null:day.tempMinC());row.put("tempMaxC",day==null?null:day.tempMaxC());
        row.put("precipitationProbabilityPct",day==null?null:day.precipitationProbabilityPct());row.put("precipitationMm",day==null?null:day.precipitationMm());
        row.put("windMaxMs",day==null?null:day.windMaxMs());row.put("gustMaxMs",day==null?null:day.gustMaxMs());row.put("et0Mm",day==null?null:day.et0Mm());days.add(row);
      }
    }
    result.put("days",days);result.put("units",Map.of("tempMinC","°C","tempMaxC","°C","precipitationProbabilityPct","%","precipitationMm","mm","windMaxMs","m/s","gustMaxMs","m/s","et0Mm","mm"));
    result.put("attribution",Map.of("name","Open-Meteo","url","https://open-meteo.com/","licence","CC BY 4.0","licenceUrl","https://creativecommons.org/licenses/by/4.0/"));
    result.put("notice",switch(status) {
      case "UNCONFIGURED" -> "尚未保存农场地理位置，请管理员在地图配置中校准并保存中心；示例地图位置不用于预报。";
      case "DISABLED" -> "公共天气预报已关闭。";
      case "UNAVAILABLE" -> "暂时无法取得有效预报，请稍后重试；不使用历史模拟天气代替预报。";
      case "STALE" -> "预报更新失败或已过期，展示六小时以内的旧资料供核对；不可据此作为当前风险或自动控制依据。";
      default -> "农场已保存中心附近的公共网格预报，可能与田间实测不同；不是现场事实或农艺处方。外部服务仅接收坐标、时区和固定气象参数；免费接口适用于非商业原型。";
    });
    return result;
  }

  static String weatherLabel(int code) {
    return switch(code) {
      case 0 -> "晴";case 1 -> "大部晴";case 2 -> "多云";case 3 -> "阴";case 45 -> "雾";case 48 -> "雾凇";
      case 51 -> "小毛毛雨";case 53 -> "中毛毛雨";case 55 -> "大毛毛雨";case 56 -> "轻冻毛毛雨";case 57 -> "强冻毛毛雨";
      case 61 -> "小雨";case 63 -> "中雨";case 65 -> "大雨";case 66 -> "小冻雨";case 67 -> "大冻雨";
      case 71 -> "小雪";case 73 -> "中雪";case 75 -> "大雪";case 77 -> "米雪";case 80 -> "小阵雨";case 81 -> "中阵雨";case 82 -> "强阵雨";
      case 85 -> "小阵雪";case 86 -> "大阵雪";case 95 -> "雷暴";case 96 -> "雷暴伴小冰雹";case 99 -> "雷暴伴大冰雹";default -> "天气代码 "+code;
    };
  }
}
