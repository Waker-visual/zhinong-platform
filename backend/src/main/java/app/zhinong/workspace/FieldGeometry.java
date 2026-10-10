package app.zhinong.workspace;

import app.zhinong.api.ApiException;
import java.util.*;

/** Small-field WGS84 geometry. Coordinate pairs are [latitude, longitude], never screen pixels. */
public final class FieldGeometry {
  private FieldGeometry() {}
  public record XY(double x, double y) {}
  public record Route(List<List<Double>> points, double lengthMeters, double areaMu, int passes) {}
  private static double cross(XY a, XY b, XY c) { return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x); }
  public static List<XY> local(List<List<Double>> points) {
    double lat=points.getFirst().getFirst(), lng=points.getFirst().get(1), scale=111320*Math.cos(Math.toRadians(lat));
    return points.stream().map(p->new XY((p.get(1)-lng)*scale,(p.getFirst()-lat)*111320)).toList();
  }
  private static double signedArea(List<XY> p) { double area=0;for(int i=0;i<p.size();i++){XY a=p.get(i),b=p.get((i+1)%p.size());area+=a.x*b.y-b.x*a.y;}return area/2; }
  public static void validate(List<List<Double>> points) {
    if(points==null || points.size()<3 || points.size()>80) throw new ApiException(400,"小田块边界需要 3–80 个顶点");
    Set<List<Double>> unique=new HashSet<>();
    for(var p:points) if(p==null || p.size()!=2 || p.getFirst()==null || p.get(1)==null || !Double.isFinite(p.getFirst()) || !Double.isFinite(p.get(1)) || Math.abs(p.getFirst())>80 || Math.abs(p.get(1))>180 || !unique.add(p))
      throw new ApiException(400,"边界须为不重复的 WGS84 [纬度,经度] 坐标");
    var xy=local(points);
    if(Math.abs(signedArea(xy))<20) throw new ApiException(400,"小田块面积不能小于 20 平方米");
    for(int i=0;i<xy.size();i++) for(int j=i+1;j<xy.size();j++) {
      if(j==i+1 || i==0&&j==xy.size()-1) continue;
      XY a=xy.get(i),b=xy.get((i+1)%xy.size()),c=xy.get(j),d=xy.get((j+1)%xy.size());
      if(Math.max(a.x,b.x)<Math.min(c.x,d.x) || Math.max(c.x,d.x)<Math.min(a.x,b.x) || Math.max(a.y,b.y)<Math.min(c.y,d.y) || Math.max(c.y,d.y)<Math.min(a.y,b.y)) continue;
      if(cross(a,b,c)*cross(a,b,d)<=0 && cross(c,d,a)*cross(c,d,b)<=0) throw new ApiException(400,"小田块边界不能自相交");
    }
  }
  public static double areaMu(List<List<Double>> points) { validate(points);return Math.abs(signedArea(local(points)))/666.6666667; }
  public static double recommendedBearing(List<List<Double>> boundary) {
    validate(boundary);var ring=local(boundary);double longest=-1,bearing=0;
    for(int i=0;i<ring.size();i++){var a=ring.get(i);var b=ring.get((i+1)%ring.size());double distance=Math.hypot(b.x-a.x,b.y-a.y);if(distance>longest){longest=distance;bearing=(Math.toDegrees(Math.atan2(b.y-a.y,b.x-a.x))+180)%180;}}
    return (Math.round(bearing*10)/10.0)%180;
  }
  /** Every full segment (and swath clearance) is checked, including concave-field shortcuts. */
  public static Route manual(List<List<Double>> boundary,List<List<Double>> points,double width,double margin) {
    validate(boundary);
    if(points==null||points.size()<2||points.size()>500)throw new ApiException(400,"手绘路线需要 2–500 个路径点");
    if(!Double.isFinite(width)||width<2||width>20||!Double.isFinite(margin)||margin<3||margin>20)throw new ApiException(400,"请核对幅宽和留边参数");
    var ring=local(boundary);var path=new ArrayList<XY>();double lat=boundary.getFirst().get(0),lng=boundary.getFirst().get(1),scale=111320*Math.cos(Math.toRadians(lat));
    for(var p:points){if(p==null||p.size()!=2||p.get(0)==null||p.get(1)==null||!Double.isFinite(p.get(0))||!Double.isFinite(p.get(1)))throw new ApiException(400,"路径点必须为有效 WGS84 经纬度");path.add(new XY((p.get(1)-lng)*scale,(p.get(0)-lat)*111320));}
    double clearance=Math.max(margin,width/2+1),length=0;
    for(int i=0;i<path.size();i++) {
      if(!inside(ring,path.get(i)))throw new ApiException(400,"路径点超出所选田块");
      if(i==0)continue;XY a=path.get(i-1),b=path.get(i);double d=Math.hypot(b.x-a.x,b.y-a.y);
      if(d<0.1)throw new ApiException(400,"相邻路径点过近或重复");length+=d;
      for(int j=0;j<ring.size();j++) {
        XY c=ring.get(j),e=ring.get((j+1)%ring.size());
        boolean crosses=cross(a,b,c)*cross(a,b,e)<=0&&cross(c,e,a)*cross(c,e,b)<=0
          && Math.max(a.x,b.x)>=Math.min(c.x,e.x)&&Math.max(c.x,e.x)>=Math.min(a.x,b.x)
          && Math.max(a.y,b.y)>=Math.min(c.y,e.y)&&Math.max(c.y,e.y)>=Math.min(a.y,b.y);
        double distance=Math.min(Math.min(distance(a,c,e),distance(b,c,e)),Math.min(distance(c,a,b),distance(e,a,b)));
        if(crosses||distance<clearance-0.05)throw new ApiException(400,"完整路线或作业幅宽越过田埂/留边，请调整路径点；不能跨越田块外道路沟渠");
      }
    }
    if(length>100000)throw new ApiException(400,"规划路线不能超过 100 km，请拆分任务");
    // Manual linework may overlap: no unsupported coverage claim is made.
    return new Route(points,length,0,0);
  }
  private static boolean inside(List<XY> ring,XY p) {
    boolean in=false;for(int i=0,j=ring.size()-1;i<ring.size();j=i++) {XY a=ring.get(i),b=ring.get(j);if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)in=!in;}return in;
  }
  private static double distance(XY p,XY a,XY b) {
    double dx=b.x-a.x,dy=b.y-a.y,t=Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)));
    return Math.hypot(p.x-a.x-t*dx,p.y-a.y-t*dy);
  }
  public static Route route(List<List<Double>> boundary,double spacing,double bearing,double headland) {
    validate(boundary);
    if(!Double.isFinite(spacing)||spacing<2||spacing>20||!Double.isFinite(bearing)||bearing<0||bearing>=180||!Double.isFinite(headland)||headland<3||headland>20)
      throw new ApiException(400,"幅宽应为 2–20 米，方向为 0–179 度，地头留白为 3–20 米");
    var original=new ArrayList<>(local(boundary));
    if(signedArea(original)<0) Collections.reverse(original);
    for(int i=0;i<original.size();i++) if(cross(original.get(i),original.get((i+1)%original.size()),original.get((i+2)%original.size())) < -0.01)
      throw new ApiException(409,"当前规划仅支持无内部障碍的凸田块；请先沿田埂拆分凹形区域");
    // Inset every edge: the machine centreline and its swath remain within the field.
    List<XY> safe=new ArrayList<>(original);
    double margin=Math.max(headland,spacing/2+1);
    for(int i=0;i<original.size();i++) {
      XY a=original.get(i),b=original.get((i+1)%original.size());
      double limit=margin*Math.hypot(b.x-a.x,b.y-a.y);
      var next=new ArrayList<XY>();
      for(int k=0;k<safe.size();k++) {
        XY p=safe.get(k),q=safe.get((k+1)%safe.size());double dp=cross(a,b,p)-limit,dq=cross(a,b,q)-limit;
        if(dp>=-1e-8) next.add(p);
        if((dp>=0)!=(dq>=0)) {double t=dp/(dp-dq);next.add(new XY(p.x+t*(q.x-p.x),p.y+t*(q.y-p.y)));}
      }
      safe=next;if(safe.size()<3) throw new ApiException(409,"田块不足以容纳当前幅宽和地头留白，请调整参数");
    }
    double rad=Math.toRadians(bearing),cos=Math.cos(rad),sin=Math.sin(rad);
    var rotated=safe.stream().map(p->new XY(p.x*cos+p.y*sin,-p.x*sin+p.y*cos)).toList();
    double min=rotated.stream().mapToDouble(XY::y).min().orElseThrow(),max=rotated.stream().mapToDouble(XY::y).max().orElseThrow();
    if((max-min)/spacing>250) throw new ApiException(400,"路线过长，请拆分田块或增加幅宽");
    var path=new ArrayList<XY>();int passes=0;
    for(double y=min+Math.min(spacing/2,(max-min)/2);y<max-0.01;y+=spacing) {
      var cuts=new ArrayList<Double>();
      for(int i=0;i<rotated.size();i++) {XY a=rotated.get(i),b=rotated.get((i+1)%rotated.size());if((a.y<=y&&b.y>y)||(b.y<=y&&a.y>y)) cuts.add(a.x+(y-a.y)*(b.x-a.x)/(b.y-a.y));}
      cuts.sort(Double::compare);if(cuts.size()<2) continue;
      double lo=cuts.getFirst(),hi=cuts.getLast();if(hi-lo<1)continue;
      path.add(new XY(passes%2==0?lo:hi,y));path.add(new XY(passes%2==0?hi:lo,y));passes++;
    }
    if(path.size()<2) throw new ApiException(409,"田块内未生成可用作业行，请调整规划参数");
    double length=0;for(int i=1;i<path.size();i++) length+=Math.hypot(path.get(i).x-path.get(i-1).x,path.get(i).y-path.get(i-1).y);
    double latitude=boundary.getFirst().getFirst(),longitude=boundary.getFirst().get(1),scale=111320*Math.cos(Math.toRadians(latitude));
    var geographic=path.stream().map(p->List.of(latitude+(p.x*sin+p.y*cos)/111320,longitude+(p.x*cos-p.y*sin)/scale)).toList();
    return new Route(geographic,length,Math.abs(signedArea(safe))/666.6666667,passes);
  }
}
