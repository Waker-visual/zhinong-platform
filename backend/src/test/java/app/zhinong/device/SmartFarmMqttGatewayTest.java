package app.zhinong.device;

import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmartFarmMqttGatewayTest {
  final ObjectMapper json=new ObjectMapper();
  @Test void routeCannotPublishToUnapprovedPhysicalDevice() {
    var route=new SmartFarmMqttGateway.Route("example-pump","/fixture/up","/fixture/down","FARM_TEST_DEVICE_KEY",true);
    assertTrue(SmartFarmMqttGateway.allows(route,Map.of("externalId","example-pump","topic","/fixture/down")));
    assertFalse(SmartFarmMqttGateway.allows(route,Map.of("externalId","example-pump","topic","/another-device/down")));
    assertFalse(SmartFarmMqttGateway.allows(route,Map.of("externalId","another-device","topic","/fixture/down")));
    var readOnly=new SmartFarmMqttGateway.Route("example-pump","/fixture/up","/fixture/down","FARM_TEST_DEVICE_KEY",false);
    assertFalse(SmartFarmMqttGateway.allows(readOnly,Map.of("externalId","example-pump","topic","/fixture/down")));
  }
  @Test void sampleTimeUsesDeviceTimeWithExplicitZoneAndRejectsFutureOrInvalidDates() {
    Instant received=Instant.parse("2026-10-06T14:00:00Z");
    var input=json.valueToTree(Map.of("params",Map.of("sys_time","2026-10-06 21:59:00")));
    assertEquals(received.minusSeconds(60),SmartFarmMqttGateway.sampleTime(input,received,ZoneId.of("Asia/Shanghai")));
    assertEquals(received,SmartFarmMqttGateway.sampleTime(json.createObjectNode(),received,ZoneOffset.UTC));
    for(String value: new String[]{"2026-02-30 12:00:00","2026-10-07T00:00:00Z","bad-clock"}) {
      var packet=json.valueToTree(Map.of("params",Map.of("sys_time",value)));
      assertThrows(ApiException.class,()->SmartFarmMqttGateway.sampleTime(packet,received,ZoneOffset.UTC));
    }
  }
}
