using System;
using System.Collections.Generic;
using UnityEngine;

public partial class FarmScene
{
    [Serializable] public class Device { public string id, kind; public float x,z,opening; public bool known,running,alert; }
    [Serializable] public class DeviceStates { public Device[] devices; public ZoneState[] zones; }
    class DeviceView { public Device data; public Transform root, gate; public Vector3 gateBase; public Renderer lamp; public readonly List<Transform> rotors=new List<Transform>(); public readonly List<Transform> flow=new List<Transform>(); }
    readonly Dictionary<string,DeviceView> deviceViews=new Dictionary<string,DeviceView>();
    string focusedDevice="";
    void FocusDevice(string id) {
        focusedDevice=deviceViews.ContainsKey(id)?id:"";
        deviceViews.TryGetValue(focusedDevice,out var selectedDevice);
        foreach(var view in deviceViews.Values) {
            bool overlapping=selectedDevice!=null && view!=selectedDevice && new Vector2(view.data.x-selectedDevice.data.x,view.data.z-selectedDevice.data.z).sqrMagnitude<4;
            view.root.gameObject.SetActive(!overlapping);
        }
    }
    class CropParcel { public List<Vector3> polygon; public string crop; public int seed; }
    readonly List<CropParcel> cropParcels=new List<CropParcel>();
    readonly Dictionary<string,GameObject> cropTiles=new Dictionary<string,GameObject>();
    float nextCrops;

    void UpdateCrops()
    {
        if(Time.unscaledTime<nextCrops || !content)return;
        nextCrops=Time.unscaledTime+.08f;
        var wanted=new HashSet<string>();int built=0;
        int cx=Mathf.FloorToInt(target.x/24),cz=Mathf.FloorToInt(target.z/24);
        if(distance<240) for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int p=0;p<cropParcels.Count;p++) {
            var parcel=cropParcels[p];if(parcel.crop=="unknown")continue;
            int x=cx+dx,z=cz+dz;string key=p+":"+x+":"+z;wanted.Add(key);
            if(cropTiles.ContainsKey(key) || built>=1)continue;
            var bounds=new Bounds(new Vector3(x*24+12,0,z*24+12),new Vector3(24,4,24));
            var parcelBounds=new Bounds(parcel.polygon[0],Vector3.zero);foreach(var point in parcel.polygon)parcelBounds.Encapsulate(point);
            if(!bounds.Intersects(parcelBounds))continue;
            cropTiles[key]=PlantRows(parcel.polygon,parcel.crop,bounds,parcel.seed);built++;
        }
        var expired=new List<string>();foreach(var item in cropTiles)if(!wanted.Contains(item.Key))expired.Add(item.Key);
        foreach(var key in expired) { var obj=cropTiles[key];if(obj){var mesh=obj.GetComponent<MeshFilter>().sharedMesh;meshes.Remove(mesh);Destroy(mesh);Destroy(obj);}cropTiles.Remove(key); }
    }

    GameObject DevicePart(string name,Vector3 position,Vector3 size,Material material,Transform parent,PrimitiveType type=PrimitiveType.Cube)
    {
        // Device dimensions are full metres; Unity's cylinder primitive is two units tall.
        if(type==PrimitiveType.Cylinder)size.y*=.5f;
        return Box(name,position,size,material,parent,type);
    }

    void BuildDevice(Device data)
    {
        var root=new GameObject("registered "+data.kind).transform;root.SetParent(content);root.position=new Vector3(data.x,0,data.z);
        var view=new DeviceView {data=data,root=root};deviceViews[data.id]=view;
        var steel=Mat("device steel","#bac5bd",.55f);var dark=Mat("device rubber","#283b3a");var blue=Mat("device enamel","#376f78",.4f);var white=Mat("device housing","#e3e4d7",.2f);
        // Models use metre dimensions, regardless of the farm's boundary size.
        if(data.kind!="DRONE" && data.kind!="TRACTOR" && data.kind!="HARVESTER" && data.kind!="SPRAYER")
            DevicePart("concrete plinth",new Vector3(0,.12f,0),new Vector3(1.2f,.24f,1.2f),Mat("concrete","#95968a"),root);
        if(data.kind=="PUMP") {
            DevicePart("pump foundation",new Vector3(0,.15f,0),new Vector3(4,.3f,3),steel,root);
            DevicePart("motor",new Vector3(0,1,0),new Vector3(1.2f,1.25f,1.2f),blue,root,PrimitiveType.Cylinder).transform.Rotate(90,0,0);
            for(int i=0;i<9;i++)DevicePart("motor cooling fin",new Vector3(0,1,-.55f+i*.14f),new Vector3(1.35f,.05f,1.35f),steel,root,PrimitiveType.Cylinder).transform.Rotate(90,0,0);
            DevicePart("pump chamber",new Vector3(0,.9f,1),new Vector3(1.5f,1.5f,.7f),blue,root,PrimitiveType.Sphere);
            DevicePart("discharge pipe",new Vector3(1.25f,.9f,1),new Vector3(.35f,1.4f,.35f),steel,root,PrimitiveType.Cylinder).transform.Rotate(0,0,90);
            DevicePart("electrical cabinet",new Vector3(-1.4f,1,0),new Vector3(.65f,1.6f,.5f),white,root);
            DevicePart("controller display",new Vector3(-1.4f,1.3f,-.26f),new Vector3(.4f,.3f,.025f),dark,root);
            var rotor=DevicePart("motor fan",new Vector3(0,1,-.78f),new Vector3(.85f,.12f,.12f),dark,root).transform;view.rotors.Add(rotor);
            for(int i=0;i<9;i++)view.flow.Add(DevicePart("flow indicator",new Vector3(2+i*.12f,.8f,1),Vector3.one*.10f,Mat("flow","#9edbea",.7f),root,PrimitiveType.Sphere).transform);
        } else if(data.kind=="GATE") {
            foreach(int sign in new[]{-1,1})DevicePart("sluice pier",new Vector3(sign*1.8f,1.7f,0),new Vector3(.6f,3.4f,1.2f),steel,root);
            DevicePart("sluice beam",new Vector3(0,3.4f,0),new Vector3(4.2f,.35f,1.2f),steel,root);
            view.gate=DevicePart("sluice plate",new Vector3(0,1.1f,0),new Vector3(3,2.1f,.25f),blue,root).transform;view.gateBase=view.gate.localPosition;
            DevicePart("screw actuator",new Vector3(0,3,0),new Vector3(.12f,2.1f,.12f),steel,root,PrimitiveType.Cylinder);
            DevicePart("gate motor",new Vector3(0,3.85f,0),new Vector3(.65f,.55f,.5f),blue,root);
            DevicePart("channel water",new Vector3(0,.2f,0),new Vector3(3,.08f,7),Mat("water","#537f87",.7f),root);
        } else if(data.kind=="DRONE") {
            DevicePart("flight controller",new Vector3(0,.9f,0),new Vector3(.7f,.28f,.9f),white,root,PrimitiveType.Sphere);
            DevicePart("battery pack",new Vector3(0,1.06f,0),new Vector3(.40f,.18f,.50f),dark,root);
            DevicePart("spray tank",new Vector3(0,.58f,0),new Vector3(.65f,.6f,.75f),white,root,PrimitiveType.Sphere);
            foreach(int side in new[]{-1,1})DevicePart("tank restraint",new Vector3(side*.22f,.57f,0),new Vector3(.035f,.5f,.70f),dark,root);
            foreach(int x in new[]{-1,1})foreach(int z in new[]{-1,1}) {
                var arm=DevicePart("carbon arm",new Vector3(x*.5f,.9f,z*.5f),new Vector3(.08f,.08f,1.4f),dark,root);arm.transform.Rotate(0,x*z*45,0);
                DevicePart("rotor motor",new Vector3(x,.95f,z),new Vector3(.16f,.12f,.16f),steel,root,PrimitiveType.Cylinder);
                view.rotors.Add(DevicePart("propeller",new Vector3(x,1.1f,z),new Vector3(.95f,.035f,.1f),dark,root).transform);
                DevicePart("landing leg",new Vector3(x*.38f,.3f,z*.35f),new Vector3(.04f,.6f,.04f),dark,root);
            }
            foreach(int side in new[]{-1,1}) {
                DevicePart("landing skid",new Vector3(side*.38f,.025f,0),new Vector3(.07f,.05f,1.1f),dark,root);
                DevicePart("spray outlet",new Vector3(side*.65f,.4f,0),new Vector3(.12f,.15f,.12f),steel,root,PrimitiveType.Cylinder);
            }
            DevicePart("spray distribution bar",new Vector3(0,.5f,0),new Vector3(1.4f,.06f,.06f),dark,root);
            if(!materials.TryGetValue("spray mist",out var mist)) {
                mist=new Material(Shader.Find("Farm/SprayMist"));materials["spray mist"]=mist;
            }
            for(int i=0;i<20;i++) {
                var drop=DevicePart("atomized spray feedback",Vector3.zero,Vector3.one*.1f,mist,root,PrimitiveType.Sphere);
                drop.GetComponent<Renderer>().shadowCastingMode=UnityEngine.Rendering.ShadowCastingMode.Off;
                view.flow.Add(drop.transform);
            }
        } else if(data.kind=="TRACTOR" || data.kind=="HARVESTER" || data.kind=="SPRAYER") {
            var saved=content;content=root;Tractor(Vector3.zero);content=saved;
            if(data.kind=="TRACTOR") for(int i=0;i<5;i++)view.flow.Add(DevicePart("engine exhaust feedback",new Vector3(.6f,3.7f,.55f),Vector3.one*.15f,Mat("exhaust haze","#a0a69c"),root,PrimitiveType.Sphere).transform);
            if(data.kind=="HARVESTER") {
                DevicePart("grain hopper",new Vector3(-1,3,0),new Vector3(2.5f,1.6f,2.8f),white,root);
                view.rotors.Add(DevicePart("header reel",new Vector3(3,1,0),new Vector3(1.3f,6.8f,1.3f),blue,root,PrimitiveType.Cylinder).transform);
                view.rotors[0].Rotate(90,0,0);
                for(int i=0;i<15;i++)DevicePart("cutter tooth",new Vector3(3.8f,.45f,-3+i*.42f),new Vector3(.8f,.12f,.12f),steel,root);
            }
            if(data.kind=="SPRAYER") {
                DevicePart("solution tank",new Vector3(-1,2.1f,0),new Vector3(1.8f,1.4f,2),white,root,PrimitiveType.Sphere);
                DevicePart("spray boom",new Vector3(-2,1.5f,0),new Vector3(.12f,.12f,12),steel,root);
                for(int i=0;i<13;i++)view.flow.Add(DevicePart("spray nozzle feedback",new Vector3(-2,1.25f,-6+i),new Vector3(.08f,.35f,.08f),Mat("flow","#9edbea",.7f),root).transform);
            }
        } else if(data.kind=="WEATHER") {
            var saved=content;content=root;ReferenceAsset("sensor",Vector3.zero,4);content=saved;
        } else {
            float height=data.kind=="SOIL"?1.2f:3.2f;
            DevicePart("galvanized mast",new Vector3(0,height/2,0),new Vector3(.10f,height,.10f),steel,root,PrimitiveType.Cylinder);
            DevicePart("junction enclosure",new Vector3(.25f,height*.65f,0),new Vector3(.45f,.55f,.25f),white,root);
            DevicePart("cabinet door seam",new Vector3(.25f,height*.65f,-.132f),new Vector3(.40f,.50f,.018f),steel,root);
            DevicePart("cabinet door",new Vector3(.25f,height*.65f,-.144f),new Vector3(.375f,.475f,.012f),white,root);
            DevicePart("cabinet latch",new Vector3(.39f,height*.65f,-.16f),new Vector3(.025f,.09f,.025f),dark,root);
            if(data.kind=="CAMERA") {
                DevicePart("camera bracket",new Vector3(.4f,height,0),new Vector3(.9f,.09f,.09f),steel,root);
                DevicePart("camera housing",new Vector3(.8f,height,0),new Vector3(.38f,.3f,.65f),white,root);
                DevicePart("camera lens",new Vector3(.8f,height,-.34f),new Vector3(.23f,.23f,.045f),dark,root,PrimitiveType.Sphere);
            } else if(data.kind=="PEST") {
                DevicePart("insect light trap",new Vector3(0,height,0),new Vector3(.6f,.65f,.6f),Mat("trap glass","#a8a3ca",.5f),root,PrimitiveType.Cylinder);
                DevicePart("trap hood",new Vector3(0,height+.7f,0),new Vector3(.9f,.07f,.9f),steel,root,PrimitiveType.Cylinder);
                for(int i=0;i<8;i++){float a=i*Mathf.PI/4;DevicePart("trap cage",new Vector3(Mathf.Sin(a)*.34f,height,Mathf.Cos(a)*.34f),new Vector3(.025f,1.2f,.025f),steel,root);}
            } else if(data.kind=="SOIL" || data.kind=="WATER") {
                DevicePart("probe conduit",new Vector3(.6f,.4f,0),new Vector3(.06f,.8f,.06f),dark,root,PrimitiveType.Cylinder);
                DevicePart("probe lead",new Vector3(.35f,.65f,0),new Vector3(.7f,.04f,.04f),dark,root);
            } else DevicePart("antenna",new Vector3(0,height+.5f,0),new Vector3(.04f,1,.04f),dark,root);
            var panel=new GameObject("solar panel assembly").transform;panel.SetParent(root,false);panel.localPosition=new Vector3(-.3f,height-.2f,0);panel.localRotation=Quaternion.Euler(18,0,0);
            DevicePart("aluminium panel frame",Vector3.zero,new Vector3(.82f,.055f,.67f),steel,panel);
            DevicePart("solar cells",Vector3.up*.032f,new Vector3(.78f,.012f,.63f),Mat("solar","#203944",.5f),panel);
            for(int cell=1;cell<6;cell++)DevicePart("cell busbar",new Vector3(-.39f+cell*.13f,.04f,0),new Vector3(.006f,.003f,.63f),steel,panel);
            for(int cell=1;cell<3;cell++)DevicePart("cell seam",new Vector3(0,.04f,-.315f+cell*.21f),new Vector3(.78f,.003f,.004f),dark,panel);
        }
        view.lamp=DevicePart("feedback lamp",data.kind=="DRONE"?new Vector3(.28f,.98f,-.3f):new Vector3(.45f,1.6f,-.4f),Vector3.one*(data.kind=="DRONE"?.07f:.15f),dark,root,PrimitiveType.Sphere).GetComponent<Renderer>();
    }
    public void UpdateDevices(string json)
    {
        var states=JsonUtility.FromJson<DeviceStates>(json);if(states.devices==null)return;
        if(states.zones!=null)foreach(var state in states.zones)if(zoneViews.TryGetValue(state.id,out var zone))zone.running=state.running;
        foreach(var state in states.devices)if(deviceViews.TryGetValue(state.id,out var view)) {
            view.data.x=state.x;view.data.z=state.z;view.root.position=new Vector3(state.x,view.root.position.y,state.z);
            view.data.known=state.known;view.data.running=state.running;view.data.opening=state.opening;view.data.alert=state.alert;
            view.lamp.sharedMaterial=Mat(state.alert?"alert":state.known?"feedback online":"feedback unknown",state.alert?"#edac55":state.known?"#98c790":"#67736e");
        }
        FocusDevice(focusedDevice);
    }
    public void SelectDevice(string id) { FocusDevice(id);if(deviceViews.TryGetValue(id,out var view)){target=view.root.position+Vector3.up*1.4f;distance=view.data.kind=="DRONE"?12:16;pitch=view.data.kind=="DRONE"?38:28;} }
    void AnimateDevices()
    {
        AnimateIrrigation();
        foreach(var view in deviceViews.Values) {
            bool running=view.data.known && view.data.running;
            foreach(var rotor in view.rotors)if(running)rotor.Rotate(view.data.kind=="DRONE"?Vector3.up:Vector3.forward,Time.deltaTime*720,Space.Self);
            for(int i=0;i<view.flow.Count;i++) {var flow=view.flow[i];flow.gameObject.SetActive(running);if(running && view.data.kind=="PUMP")flow.localPosition=new Vector3(1.8f+Mathf.Repeat(Time.time*1.5f+i*.15f,1.4f),.9f-Mathf.Repeat(Time.time*1.5f+i*.15f,1.4f)*.4f,1);}
            if(running && view.data.kind=="TRACTOR")for(int i=0;i<view.flow.Count;i++){float rise=Mathf.Repeat(Time.time*.6f+i*.2f,1);view.flow[i].localPosition=new Vector3(.6f+rise*.15f,3.7f+rise,.55f);view.flow[i].localScale=Vector3.one*(.08f+rise*.18f);}
            if(running && view.data.kind=="DRONE")for(int i=0;i<view.flow.Count;i++) {
                float fall=Mathf.Repeat(Time.time*.75f+i*.137f,1),side=i%2==0?-1:1;
                view.flow[i].localPosition=new Vector3(side*.65f+Mathf.Sin(i*3.1f)*fall*.3f,.35f-fall*2.7f,Mathf.Sin(i*2.4f)*fall*.30f);
                view.flow[i].localScale=new Vector3(.05f+fall*.24f,.24f+fall*.9f,.05f+fall*.24f);
            }
            if(view.gate && view.data.known)view.gate.localPosition=Vector3.MoveTowards(view.gate.localPosition,view.gateBase+Vector3.up*(view.data.opening/100*2),Time.deltaTime);
            if(view.data.kind=="DRONE")view.root.position=new Vector3(view.data.x,running?2.5f+Mathf.Sin(Time.time)*.08f:0,view.data.z);
        }
    }
}
