package app.zhinong.device;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SimulatedDeviceAdapter implements DeviceAdapter {

  @Override
  public BigDecimal sample(String tenantId, String deviceId, String metric) {
    double phase = Math.floorMod((tenantId + deviceId).hashCode(), 360);
    double wave = Math.sin(Instant.now().getEpochSecond() / 300.0 + phase);
    double value = "TEMPERATURE".equals(metric) ? 23 + wave * 6 : 55 + wave * 15;
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }
}
