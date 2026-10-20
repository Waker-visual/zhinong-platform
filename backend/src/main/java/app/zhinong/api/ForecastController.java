package app.zhinong.api;

import app.zhinong.weather.WeatherForecastService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/farms")
public class ForecastController {
  private final WeatherForecastService forecasts;
  public ForecastController(WeatherForecastService forecasts) {this.forecasts=forecasts;}
  @GetMapping("/{id}/weather-forecast") public Object forecast(@PathVariable String id) {return forecasts.get(id);}
}
