using System;
using System.Collections;
using System.Collections.Generic;
using System.Reflection;
using UnityEngine;

// Runs from FarmBuilder or with -executeMethod FarmDeviceChecks.Validate.
public static class FarmDeviceChecks
{
    const BindingFlags PrivateInstance=BindingFlags.NonPublic|BindingFlags.Instance;
    static void Require(bool condition,string message) { if(!condition)throw new Exception("Farm device regression: "+message); }

    public static void Validate()
    {
        var original=new Vector2(17,-9);
        var items=new List<FarmDeviceLayout.Item> {
            new FarmDeviceLayout.Item("pest",original,1.1f),
            new FarmDeviceLayout.Item("weather",original,1.5f),
            new FarmDeviceLayout.Item("sprayer",original,6.5f),
            new FarmDeviceLayout.Item("camera",original+Vector2.right,1.1f),
            new FarmDeviceLayout.Item("isolated",new Vector2(150,80),1.1f)
        };
        var forward=FarmDeviceLayout.Resolve(items);
        for(int i=0;i<items.Count;i++)for(int j=i+1;j<items.Count;j++) {
            float minimum=items[i].radius+items[j].radius+.25f;
            Require(Vector2.Distance(forward[items[i].id],forward[items[j].id])>=minimum-.0001f,"equipment footprints overlap");
        }
        Require(items[0].position==original,"authoritative coordinates were changed");
        Require(forward["isolated"]==new Vector2(150,80),"an isolated device was moved");
        items.Reverse();
        var reversed=FarmDeviceLayout.Resolve(items);
        foreach(var entry in forward)Require(entry.Value==reversed[entry.Key],"API ordering changed the visual layout");
        var repeated=FarmDeviceLayout.Resolve(items);
        foreach(var entry in forward)Require(entry.Value==repeated[entry.Key],"telemetry refresh moved a stationary device");

        // Exercise the actual selection/close code without creating materials or importing models.
        var container=new GameObject("device regression fixture");
        try {
            var scene=container.AddComponent<FarmScene>();
            var views=(IDictionary)typeof(FarmScene).GetField("deviceViews",PrivateInstance).GetValue(scene);
            var viewType=typeof(FarmScene).GetNestedType("DeviceView",BindingFlags.NonPublic);
            var roots=new Dictionary<string,Transform>();
            foreach(string id in new[]{"pest","weather","camera"}) {
                var root=new GameObject(id).transform;root.SetParent(container.transform);root.position=new Vector3(forward[id].x,0,forward[id].y);roots[id]=root;
                var view=Activator.CreateInstance(viewType,true);
                viewType.GetField("data").SetValue(view,new FarmScene.Device { id=id,kind=id=="pest"?"PEST":"WEATHER",x=original.x,z=original.y });
                viewType.GetField("root").SetValue(view,root);views.Add(id,view);
            }
            for(int cycle=0;cycle<3;cycle++) {
                scene.SelectDevice("pest");scene.SelectDevice("");
                foreach(var entry in roots) {
                    Require(entry.Value.gameObject.activeSelf,"closing details toggled another model's visibility");
                    Require(entry.Value.position==new Vector3(forward[entry.Key].x,0,forward[entry.Key].y),"closing details changed a model position");
                }
            }
            scene.SetDeviceDemo("off");
            Require(!(bool)typeof(FarmScene).GetField("deviceDemo",PrivateInstance).GetValue(scene),"demo animation cannot be disabled");
            scene.SetDeviceDemo("on");
            Require((bool)typeof(FarmScene).GetField("deviceDemo",PrivateInstance).GetValue(scene),"demo animation cannot be enabled");
            foreach(DictionaryEntry entry in views) {
                var data=(FarmScene.Device)viewType.GetField("data").GetValue(entry.Value);
                Require(!data.known && !data.running && data.opening==0,"demo changed sampled device state");
            }
        } finally { UnityEngine.Object.DestroyImmediate(container); }
        Debug.Log("Farm device checks passed: stable layout, footprint spacing, selection/close, immutable coordinates and demo state separation.");
    }
}
