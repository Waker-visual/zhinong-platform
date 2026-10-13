using System;
using System.Collections.Generic;
using UnityEngine;

public partial class FarmScene
{
    [Serializable] public class IrrigationNode { public string kind; public float x,z; }
    [Serializable] public class Zone { public string id,pumpId,parcelId; public Point[] points; public IrrigationNode[] nodes; }
    [Serializable] public class ZoneState { public string id; public bool running; }
    class ZoneView { public Zone data; public Vector3 anchor; public bool running; public readonly List<Transform> droplets=new List<Transform>(); public readonly List<Vector3> outlets=new List<Vector3>(); }
    readonly Dictionary<string,ZoneView> zoneViews=new Dictionary<string,ZoneView>();
    void BuildIrrigation(Zone[] zones)
    {
        var pipe=Mat("irrigation pipe","#386a79",.4f);var steel=Mat("device steel","#bac5bd",.55f);var water=Mat("flow","#9edbea",.7f);
        var segments=new HashSet<string>();
        foreach(var zone in zones) {
            if(zone.points==null || zone.points.Length<2)continue;
            var view=new ZoneView {data=zone};zoneViews[zone.id]=view;
            for(int i=1;i<zone.points.Length;i++) {
                var a=new Vector3(zone.points[i-1].x,.35f,zone.points[i-1].z);var b=new Vector3(zone.points[i].x,.35f,zone.points[i].z);
                if(Vector3.Distance(a,b)<.01f)continue;
                string key=a.x.ToString("F2")+":"+a.z.ToString("F2")+":"+b.x.ToString("F2")+":"+b.z.ToString("F2");
                if(!segments.Add(key))continue;
                var obj=Box("mapped irrigation main",(a+b)/2,new Vector3(.28f,Vector3.Distance(a,b)/2,.28f),pipe,content,PrimitiveType.Cylinder);
                obj.transform.rotation=Quaternion.FromToRotation(Vector3.up,(b-a).normalized);
                // Couplings and supports expose the pipe structure at close range.
                for(float d=0;d<Vector3.Distance(a,b);d+=12) {
                    var point=Vector3.Lerp(a,b,d/Vector3.Distance(a,b));
                    var coupling=Box("pipe coupling",point,new Vector3(.36f,.09f,.36f),steel,content,PrimitiveType.Cylinder);
                    coupling.transform.rotation=obj.transform.rotation;
                    Box("pipe support",point-Vector3.up*.23f,new Vector3(.6f,.2f,.5f),steel,content);
                }
            }
            view.anchor=new Vector3(zone.points[zone.points.Length-1].x,0,zone.points[zone.points.Length-1].z);
            if(zone.nodes!=null)foreach(var node in zone.nodes) {
                var p=new Vector3(node.x,0,node.z);
                Box(node.kind=="VALVE"?"mapped zone valve":"mapped outlet",p+Vector3.up*.6f,new Vector3(.18f,.6f,.18f),pipe,content,PrimitiveType.Cylinder);
                if(node.kind=="VALVE") {
                    for(int i=0;i<12;i++){float a=i*Mathf.PI/6,b=(i+1)*Mathf.PI/6;Beam(p+new Vector3(Mathf.Cos(a)*.35f,1.2f,Mathf.Sin(a)*.35f),p+new Vector3(Mathf.Cos(b)*.35f,1.2f,Mathf.Sin(b)*.35f),.045f,.045f,Mat("valve wheel","#be8d52"),content);}
                    Box("valve crossbar",p+Vector3.up*1.2f,new Vector3(.65f,.05f,.05f),steel,content);
                } else {
                    Box("outlet elbow",p+new Vector3(.3f,1.1f,0),new Vector3(.16f,.35f,.16f),steel,content,PrimitiveType.Cylinder).transform.Rotate(0,0,90);
                    view.outlets.Add(p);
                    for(int i=0;i<10;i++){var drop=Box("zone discharge feedback",p,Vector3.one*.12f,water,content,PrimitiveType.Sphere).transform;drop.gameObject.SetActive(false);view.droplets.Add(drop);}
                }
            }
        }
    }
    void AnimateIrrigation()
    {
        foreach(var view in zoneViews.Values)for(int i=0;i<view.droplets.Count;i++) {
            var drop=view.droplets[i];drop.gameObject.SetActive(view.running);
            if(view.running){float t=Mathf.Repeat(Time.time*.7f+(i%10)*.1f,1);drop.position=view.outlets[i/10]+new Vector3(.3f+t*1.5f,1.1f+.5f*t-1.3f*t*t,0);}
        }
    }
}
