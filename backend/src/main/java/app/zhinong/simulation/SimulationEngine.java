package app.zhinong.simulation;

import app.zhinong.api.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic operational stress test. Parameters are explicit scenario assumptions, not a calibrated crop forecast. */
@Component
public class SimulationEngine {

  public static final String VERSION = "operations-1.0";

  public record Crop(
    String id,
    String name,
    int duration,
    int sowingDay,
    double yieldKgMu,
    double ky,
    double kc,
    double minTemp,
    double maxTemp,
    double price,
    String basis
  ) {}

  public static final List<Crop> CROPS = List.of(
    new Crop(
      "RICE",
      "水稻",
      125,
      130,
      550,
      1.1,
      1.15,
      12,
      35,
      2.8,
      "Ky、单产、适温和成本均为演练假设"
    ),
    new Crop(
      "MAIZE",
      "玉米",
      115,
      125,
      600,
      1.25,
      1.05,
      10,
      35,
      2.4,
      "全季 Ky=1.25 参考 FAO；其余为演练假设"
    ),
    new Crop(
      "WHEAT",
      "春小麦",
      105,
      100,
      400,
      1.15,
      1.0,
      5,
      30,
      2.6,
      "春小麦全季 Ky=1.15 参考 FAO；其余为演练假设"
    ),
    new Crop(
      "VEGETABLE",
      "蔬菜通用演练",
      80,
      135,
      2000,
      1.1,
      1.0,
      10,
      30,
      2.0,
      "未区分品种的通用假设，必须结合实际校准"
    )
  );

  public record Plot(
    String id,
    String name,
    String crop,
    double areaMu,
    String model
  ) {}

  private static final class State {

    final Plot plot;
    final Crop crop;
    final int begin, end;
    double soil = 45,
      etNeed,
      etActual,
      heat,
      pressure,
      pestSum,
      remaining,
      irrigation,
      treated,
      cost;
    int activeDays,
      detected = -1,
      finished = -1;
    boolean infected, harvested;

    State(Plot p, SimulationInput in) {
      plot = p;
      crop = CROPS.stream()
        .filter(c -> c.id.equals(p.model))
        .findFirst()
        .orElseThrow(() -> new ApiException(400, "未知作物模型"));
      begin = in.days() == 365 ? crop.sowingDay - 1 : 0;
      end =
        begin + crop.duration - (in.days() == 365 ? 0 : in.initialAge()) - 1;
    }
  }

  private final WeatherData weather;

  public SimulationEngine(WeatherData weather) {
    this.weather = weather;
  }

  public Map<String, Object> run(SimulationInput in, List<Plot> plots) {
    if (in.days() != 90 && in.days() != 365) throw new ApiException(
      400,
      "周期仅支持 90 天或 365 天"
    );
    if (
      in.startDate().isBefore(LocalDate.of(2025, 1, 1)) ||
      in.startDate().plusDays(in.days() - 1).isAfter(LocalDate.of(2025, 12, 31))
    ) throw new ApiException(
      400,
      "天气数据覆盖 2025 全年，请将周期保持在数据范围内"
    );
    if (in.days() == 365 && in.initialAge() != 0) throw new ApiException(
      400,
      "年度模拟从播种开始，初始生长天数须为 0"
    );
    if (in.outbreakDay() >= in.days()) throw new ApiException(
      400,
      "虫害发生日超出周期"
    );
    if (plots.isEmpty() || plots.size() > 100) throw new ApiException(
      400,
      "请选择有 1 至 100 个地块的农场"
    );
    for (double value : new double[] {
      in.droneCapacity(),
      in.manualCapacity(),
      in.severity(),
      in.irrigationM3(),
      in.fertilizerCoverage(),
    })
      if (!Double.isFinite(value)) throw new ApiException(
        400,
        "情景参数必须为有限数值"
      );
    if (
      in.days() == 90 &&
      plots
        .stream()
        .anyMatch(p ->
          CROPS.stream().anyMatch(
            c -> c.id.equals(p.model) && in.initialAge() >= c.duration
          )
        )
    ) throw new ApiException(400, "期初生长天数必须小于所选作物周期");
    var selected = weather.days
      .stream()
      .filter(
        d ->
          !d.date().isBefore(in.startDate()) &&
          d.date().isBefore(in.startDate().plusDays(in.days()))
      )
      .toList();
    var scenarios = new ArrayList<Map<String, Object>>();
    for (String kind : List.of("BASELINE", "OUTAGE", "MITIGATION"))
      scenarios.add(scenario(in, plots, selected, kind));
    return Map.of(
      "modelVersion",
      VERSION,
      "weather",
      weather.provenance,
      "input",
      in,
      "plotSnapshot",
      plots,
      "scenarios",
      scenarios,
      "assumptions",
      assumptions()
    );
  }

  public static List<String> assumptions() {
    return List.of(
      "这是经营流程压力测试，不是经过农学标定的产量预测；产量、价格、费用和病虫害强度为可解释的情景假设。",
      "天气为建三江附近 2025 年逐日再分析数据，对其他农场是统一对照天气，未自动匹配农场位置。",
      "年度每块田只模拟一季；季度允许设置期初生长天数。未成熟地块只给潜在产量，不计入本期收获。",
      "简化土壤水桶容量 90 mm、初始 45 mm；有效雨量取 80%，日灌溉按需求等比例分配，1 mm·亩约 0.667 m³。",
      "缺水损失参考 FAO 的 1−Ya/Ym=Ky×(1−ETa/ETm)，仅玉米和春小麦的全季 Ky 引用其表格，其余参数为假设。",
      "降雨超过 2 mm 或日最大风速超过 6 m/s 则关闭当日无人机窗口；这是假设阈值，未模拟小时级安全作业窗口。",
      "故障从虫害发生日开始；巡检发现后进入队列。人工补位方案优先处理未覆盖面积，手动容量仍受总工时限制。",
      "防治是一次覆盖作业；覆盖后压力降低，延误造成累计损失。未包含真实药剂、施药剂量和抗药性。",
      "费用仅含模拟植保（无人机 8 元/亩、人工 22 元/亩）及灌溉（0.5 元/m³），贡献额不等于净利润。",
      "水分、虫害、极端温度及养分损失按顺序归因，不能直接相加成百分比；不会写入生产记录或下发设备指令。"
    );
  }

  private Map<String, Object> scenario(
    SimulationInput in,
    List<Plot> plots,
    List<WeatherData.Day> days,
    String kind
  ) {
    var states = plots
      .stream()
      .map(p -> new State(p, in))
      .toList();
    var timeline = new ArrayList<Map<String, Object>>();
    var events = new ArrayList<Map<String, Object>>();
    double totalWater = 0,
      totalCost = 0;
    int closedDays = 0,
      lateDays = 0;
    for (int day = 0; day < days.size(); day++) {
      var w = days.get(day);
      boolean flying = w.rain() <= 2 && w.wind() <= 6;
      boolean outage =
        !kind.equals("BASELINE") &&
        day >= in.outbreakDay() &&
        day < in.outbreakDay() + in.outageDays();
      double drone = flying && !outage ? in.drones() * in.droneCapacity() : 0,
        manual = kind.equals("MITIGATION") ? in.manualCapacity() : 0;
      double needM3 = 0,
        waterUsed = 0,
        covered = 0,
        backlog = 0,
        pressure = 0;
      int active = 0,
        late = 0;
      for (var s : states) {
        if (day < s.begin || day > s.end) continue;
        double et = w.et0() * s.crop.kc;
        s.soil = Math.min(90, s.soil + w.rain() * 0.8);
        needM3 += (Math.max(0, et + 25 - s.soil) * s.plot.areaMu * 2) / 3;
      }
      double ratio = needM3 == 0 ? 0 : Math.min(1, in.irrigationM3() / needM3);
      // Queue order is stable so a rerun of the same snapshot has exactly the same output.
      for (var s : states) {
        if (day < s.begin || day > s.end) continue;
        active++;
        s.activeDays++;
        double et = w.et0() * s.crop.kc,
          mm = Math.max(0, et + 25 - s.soil) * ratio;
        double m3 = (mm * s.plot.areaMu * 2) / 3;
        waterUsed += m3;
        s.irrigation += m3;
        s.cost += m3 * 0.5;
        s.soil = Math.min(90, s.soil + mm);
        double actual = Math.min(s.soil, et);
        s.soil -= actual;
        s.etNeed += et;
        s.etActual += actual;
        s.heat +=
          Math.max(0, w.high() - s.crop.maxTemp) +
          Math.max(0, s.crop.minTemp - w.low()) * 0.3;
        if (day == in.outbreakDay() && in.severity() > 0) {
          s.infected = true;
          s.pressure = in.severity();
          s.remaining = s.plot.areaMu;
          event(
            events,
            w.date(),
            s,
            "PEST",
            "设定虫害发生，等待巡检",
            s.pressure
          );
        }
        if (s.infected) {
          if (s.detected < 0 && day % in.inspectionInterval() == 0) {
            s.detected = day;
            event(
              events,
              w.date(),
              s,
              "DETECTED",
              "巡检发现，进入植保队列",
              s.remaining
            );
          }
          if (s.detected >= 0 && s.remaining > 0) {
            double byDrone = Math.min(s.remaining, drone);
            drone -= byDrone;
            s.remaining -= byDrone;
            double byHand = Math.min(s.remaining, manual);
            manual -= byHand;
            s.remaining -= byHand;
            double done = byDrone + byHand;
            covered += done;
            s.treated += done;
            s.cost += byDrone * 8 + byHand * 22;
            if (done > 0) {
              s.pressure = Math.max(
                0,
                s.pressure - (in.severity() * 0.9 * done) / s.plot.areaMu
              );
              event(events, w.date(), s, "TREATED", "当日植保覆盖（亩）", done);
            }
            if (s.remaining < 0.00001 && s.finished < 0) {
              s.finished = day;
              event(
                events,
                w.date(),
                s,
                "CLEARED",
                "本次防治覆盖完成，响应用时（天）",
                day - s.detected
              );
            }
            if (s.remaining > 0 && day - s.detected > in.responseDays()) late++;
          }
          double suitability = w.temperature() >= 15 && w.temperature() <= 30
            ? 1
            : 0.25;
          s.pressure = Math.min(
            1,
            Math.max(
              0,
              s.pressure + (s.remaining > 0 ? 0.014 * suitability : -0.008)
            )
          );
          s.pestSum += s.pressure;
          pressure += s.pressure;
          if (s.detected >= 0) backlog += s.remaining;
        }
        if (day == s.end) {
          s.harvested = true;
          event(
            events,
            w.date(),
            s,
            "HARVEST",
            "达到模型设定成熟日",
            s.activeDays
          );
        }
      }
      if (active > 0 && !flying) closedDays++;
      if (late > 0) lateDays++;
      totalWater += waterUsed;
      timeline.add(
        Map.of(
          "date",
          w.date().toString(),
          "temperature",
          w.temperature(),
          "rain",
          w.rain(),
          "wind",
          w.wind(),
          "flying",
          flying,
          "outage",
          outage,
          "irrigationM3",
          r(waterUsed),
          "treatedMu",
          r(covered),
          "backlogMu",
          r(backlog),
          "pestPressure",
          active == 0 ? 0 : r(pressure / active)
        )
      );
    }
    var results = new ArrayList<Map<String, Object>>();
    double harvested = 0,
      potential = 0,
      ideal = 0,
      lossPest = 0,
      revenue = 0;
    var findings = new ArrayList<String>();
    for (var s : states) {
      double waterLoss = s.etNeed == 0
        ? 0
        : Math.min(0.85, s.crop.ky * (1 - s.etActual / s.etNeed));
      double pestLoss = s.activeDays == 0
        ? 0
        : Math.min(0.6, (s.pestSum / s.activeDays) * 0.65);
      double heatLoss = Math.min(
        0.35,
        (s.heat / Math.max(1, s.activeDays)) * 0.025
      );
      double nutrientLoss = (1 - in.fertilizerCoverage()) * 0.25;
      double base = s.crop.yieldKgMu * s.plot.areaMu,
        waterKg = base * waterLoss,
        pestKg = (base - waterKg) * pestLoss,
        heatKg = (base - waterKg - pestKg) * heatLoss,
        nutrientKg = (base - waterKg - pestKg - heatKg) * nutrientLoss;
      double yield = Math.max(0, base - waterKg - pestKg - heatKg - nutrientKg);
      ideal += base;
      potential += yield;
      lossPest += pestKg;
      totalCost += s.cost;
      if (s.harvested) {
        harvested += yield;
        revenue += yield * s.crop.price;
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("plotId", s.plot.id);
      result.put("name", s.plot.name);
      result.put("crop", s.crop.name);
      result.put("areaMu", s.plot.areaMu);
      result.put("harvested", s.harvested);
      result.put("idealKg", r(base));
      result.put("potentialKg", r(yield));
      result.put("harvestKg", s.harvested ? r(yield) : 0);
      result.put("waterLossKg", r(waterKg));
      result.put("pestLossKg", r(pestKg));
      result.put("heatLossKg", r(heatKg));
      result.put("nutrientLossKg", r(nutrientKg));
      result.put("untreatedMu", r(s.remaining));
      result.put(
        "responseDays",
        s.finished < 0 ? null : s.finished - s.detected
      );
      result.put("cost", r(s.cost));
      results.add(result);
      if (s.remaining > 0.01) findings.add(
        s.plot.name +
          "：仍有 " +
          r(s.remaining) +
          " 亩未完成防治，需外协资源或提高人工补位能力。"
      );
      if (
        s.finished >= 0 && s.finished - s.detected > in.responseDays()
      ) findings.add(
        s.plot.name +
          "：防治覆盖用了 " +
          (s.finished - s.detected) +
          " 天，超过 " +
          in.responseDays() +
          " 天响应目标。"
      );
      if (waterLoss > 0.1) findings.add(
        s.plot.name +
          "：缺水损失占理论单产 " +
          r(waterLoss * 100) +
          "%，应检验泵站容量与灌溉排程。"
      );
      if (!s.harvested) findings.add(
        s.plot.name + "：本周期未达到成熟日，潜在产量不计入本期收获。"
      );
    }
    if (states.stream().noneMatch(s -> s.infected)) findings.add(
      "虫害事件没有命中在田作物：请调整发生日或季节，不应据此认为防治配置已通过验收。"
    );
    if (closedDays > 0) findings.add(
      "有在田作物期间，" +
        closedDays +
        " 天不满足假设飞行窗口；应测试雨天/大风期间的替代作业安排。"
    );
    if (lateDays > 0) findings.add(
      "共有 " +
        lateDays +
        " 天存在超期防治队列；建议加入超期提醒、替代资源和分区优先级。"
    );
    if (findings.isEmpty()) findings.add(
      "本组假设下未出现所检查的资源缺口；仍需用本地实测数据和其他故障情景复测。"
    );
    return Map.of(
      "id",
      kind,
      "name",
      switch (kind) {
        case "BASELINE" -> "资源正常";
        case "OUTAGE" -> "无人机缺位";
        default -> "人工补位";
      },
      "summary",
      Map.of(
        "idealKg",
        r(ideal),
        "potentialKg",
        r(potential),
        "harvestKg",
        r(harvested),
        "pestLossKg",
        r(lossPest),
        "cost",
        r(totalCost),
        "revenue",
        r(revenue),
        "contribution",
        r(revenue - totalCost),
        "irrigationM3",
        r(totalWater),
        "overdueDays",
        lateDays,
        "closedDays",
        closedDays
      ),
      "plots",
      results,
      "days",
      timeline,
      "events",
      events,
      "findings",
      findings
    );
  }

  private static void event(
    List<Map<String, Object>> events,
    LocalDate date,
    State s,
    String type,
    String text,
    double value
  ) {
    events.add(
      Map.of(
        "date",
        date.toString(),
        "plotId",
        s.plot.id,
        "plotName",
        s.plot.name,
        "type",
        type,
        "message",
        text,
        "value",
        r(value)
      )
    );
  }

  private static double r(double n) {
    return Math.round(n * 100.0) / 100.0;
  }
}
