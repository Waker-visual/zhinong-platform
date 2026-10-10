package app.zhinong.workspace;
import static org.junit.jupiter.api.Assertions.*;
import app.zhinong.api.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class FieldGeometryTest {
  List<List<Double>> rectangle() { return List.of(List.of(47.26,132.73),List.of(47.26,132.7312),List.of(47.2606,132.7312),List.of(47.2606,132.73)); }
  @Test void routesStayInsideFieldWithHeadlandInEveryDirection() {
    for(double angle:List.of(0.,30.,90.,135.,179.)) {
      var route=FieldGeometry.route(rectangle(),5,angle,6);
      assertTrue(route.lengthMeters()>100);assertTrue(route.passes()>1);
      for(var p:route.points()) {
        assertTrue((p.get(0)-47.26)*111320>=5.99);
        assertTrue((47.2606-p.get(0))*111320>=5.99);
        assertTrue((p.get(1)-132.73)*111320*Math.cos(Math.toRadians(47.26))>=5.99);
        assertTrue((132.7312-p.get(1))*111320*Math.cos(Math.toRadians(47.26))>=5.99);
      }
    }
    var reverse=new ArrayList<>(rectangle());Collections.reverse(reverse);
    assertEquals(FieldGeometry.areaMu(rectangle()),FieldGeometry.areaMu(reverse),.001);
    assertDoesNotThrow(()->FieldGeometry.route(reverse,5,90,4));
  }
  @Test void refusesInvalidAndConcaveNavigationInsteadOfCrossingObstacles() {
    assertThrows(ApiException.class,()->FieldGeometry.route(rectangle(),Double.NaN,90,4));
    var crossed=List.of(rectangle().get(0),rectangle().get(2),rectangle().get(1),rectangle().get(3));
    assertThrows(ApiException.class,()->FieldGeometry.validate(crossed));
    var concave=List.of(rectangle().get(0),rectangle().get(1),List.of(47.2602,132.7306),rectangle().get(2),rectangle().get(3));
    assertDoesNotThrow(()->FieldGeometry.validate(concave));
    assertThrows(ApiException.class,()->FieldGeometry.route(concave,5,90,4));
    assertThrows(ApiException.class,()->FieldGeometry.route(List.of(List.of(47.26,132.73),List.of(47.26,132.7301),List.of(47.2601,132.7301),List.of(47.2601,132.73)),20,90,20));
  }
  @Test void manualSegmentsCannotCutAcrossAConcaveFieldEvenWhenBothEndpointsAreInside() {
    var concave=List.of(List.of(47.26,132.73),List.of(47.26,132.732),List.of(47.262,132.732),List.of(47.262,132.7314),List.of(47.2606,132.7314),List.of(47.2606,132.7306),List.of(47.262,132.7306),List.of(47.262,132.73));
    assertDoesNotThrow(()->FieldGeometry.validate(concave));
    assertThrows(ApiException.class,()->FieldGeometry.manual(concave,List.of(List.of(47.2615,132.7303),List.of(47.2615,132.7317)),3,4));
    var valid=FieldGeometry.manual(concave,List.of(List.of(47.2615,132.7303),List.of(47.2603,132.7303),List.of(47.2603,132.7317),List.of(47.2615,132.7317)),3,4);
    assertTrue(valid.lengthMeters()>300);assertEquals(0,valid.areaMu(),"Manual overlapping coverage is not measured");
    assertThrows(ApiException.class,()->FieldGeometry.manual(rectangle(),List.of(List.of(47.26001,132.7302),List.of(47.26001,132.731)),5,4));
  }
}
