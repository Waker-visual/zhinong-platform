package app.zhinong.weather;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Internal seam: public forecasts contain no farm identity or operating records. */
interface WeatherForecastGateway {
  record Day(LocalDate date,Integer weatherCode,Double tempMinC,Double tempMaxC,
      Double precipitationProbabilityPct,Double precipitationMm,Double windMaxMs,Double gustMaxMs,Double et0Mm) {
    boolean hasData() {
      return weatherCode!=null || tempMinC!=null || tempMaxC!=null || precipitationProbabilityPct!=null ||
        precipitationMm!=null || windMaxMs!=null || gustMaxMs!=null || et0Mm!=null;
    }
  }
  List<Day> fetch(double latitude,double longitude,ZoneId zone,LocalDate firstDate);

  final class Unavailable extends RuntimeException {
    final String diagnostic;
    Unavailable(String diagnostic) { super(diagnostic);this.diagnostic=diagnostic; }
  }
}
