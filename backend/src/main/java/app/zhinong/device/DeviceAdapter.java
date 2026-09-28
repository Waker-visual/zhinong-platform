package app.zhinong.device;

import java.math.BigDecimal;

/** Adapter receives a tenant-owned device ID, never credentials or endpoint URLs from a browser. */
public interface DeviceAdapter {
  BigDecimal sample(String tenantId, String deviceId, String metric);
}
