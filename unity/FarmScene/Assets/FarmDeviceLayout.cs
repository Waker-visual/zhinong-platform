using System;
using System.Collections.Generic;
using UnityEngine;

// Presentation-only offsets. The authoritative device and irrigation coordinates are never changed.
public static class FarmDeviceLayout
{
    public struct Item
    {
        public string id;
        public Vector2 position;
        public float radius;
        public Item(string id,Vector2 position,float radius) { this.id=id;this.position=position;this.radius=radius; }
    }

    public static Dictionary<string,Vector2> Resolve(IEnumerable<Item> source)
    {
        var items=new List<Item>(source);
        // Large equipment gets its original location first; IDs make the result independent of API order.
        items.Sort((a,b)=> { int size=b.radius.CompareTo(a.radius);return size!=0?size:StringComparer.Ordinal.Compare(a.id,b.id); });
        var placed=new List<Item>();
        var result=new Dictionary<string,Vector2>();
        foreach(var item in items) {
            var position=item.position;
            if(!Clear(position,item.radius,placed)) {
                bool found=false;
                for(int ring=1;!found;ring++) {
                    float radius=ring*.75f;
                    int steps=Mathf.Max(12,Mathf.CeilToInt(2*Mathf.PI*radius/1.25f));
                    for(int step=0;step<steps;step++) {
                        float angle=step*2*Mathf.PI/steps;
                        var candidate=item.position+new Vector2(Mathf.Cos(angle),Mathf.Sin(angle))*radius;
                        if(!Clear(candidate,item.radius,placed))continue;
                        position=candidate;found=true;break;
                    }
                }
            }
            placed.Add(new Item(item.id,position,item.radius));
            result[item.id]=position;
        }
        return result;
    }

    static bool Clear(Vector2 point,float radius,List<Item> placed)
    {
        foreach(var other in placed) {
            float spacing=radius+other.radius+.25f;
            if((point-other.position).sqrMagnitude<spacing*spacing)return false;
        }
        return true;
    }
}

public partial class FarmScene
{
    static float DeviceFootprint(string kind)
    {
        switch(kind) {
            case "SPRAYER": return 6.5f;
            case "HARVESTER": return 4.6f;
            case "TRACTOR": return 3.5f;
            case "GATE": return 4;
            case "PUMP": return 3.1f;
            case "DRONE": return 1.9f;
            case "WEATHER": return 1.5f;
            default: return 1.1f;
        }
    }

    void LayoutDevices()
    {
        var items=new List<FarmDeviceLayout.Item>();
        foreach(var view in deviceViews.Values)
            items.Add(new FarmDeviceLayout.Item(view.data.id,new Vector2(view.data.x,view.data.z),view.footprint));
        var positions=FarmDeviceLayout.Resolve(items);
        foreach(var view in deviceViews.Values) {
            var position=positions[view.data.id];
            view.displayOffset=new Vector3(position.x-view.data.x,0,position.y-view.data.z);
            view.root.position=new Vector3(position.x,view.root.position.y,position.y);
            bool shifted=view.displayOffset.sqrMagnitude>.01f;
            if(shifted && !view.anchorLine) {
                var line=new GameObject("registered location connector "+view.data.id);line.transform.SetParent(content,false);
                view.anchorLine=line.AddComponent<LineRenderer>();
                view.anchorLine.sharedMaterial=Mat("device location connector","#a0c7ba");
                view.anchorLine.useWorldSpace=true;view.anchorLine.positionCount=2;view.anchorLine.widthMultiplier=.07f;
                view.anchorLine.shadowCastingMode=UnityEngine.Rendering.ShadowCastingMode.Off;
                view.anchorLine.receiveShadows=false;
            }
            if(view.anchorLine) {
                view.anchorLine.enabled=shifted;
                view.anchorLine.SetPosition(0,new Vector3(view.data.x,.25f,view.data.z));
                view.anchorLine.SetPosition(1,new Vector3(position.x,.25f,position.y));
            }
        }
    }
}
