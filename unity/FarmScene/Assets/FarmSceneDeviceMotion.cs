using UnityEngine;

public partial class FarmScene
{
    bool deviceDemo=true;
    public void SetDeviceDemo(string value) { deviceDemo=value=="on"; }

    void BuildAnemometer(DeviceView view,Material steel,Material dark)
    {
        DevicePart("wind instrument mast",new Vector3(0,2.2f,0),new Vector3(.08f,4.4f,.08f),steel,view.root,PrimitiveType.Cylinder);
        var rotor=new GameObject("anemometer rotor").transform;rotor.SetParent(view.root,false);rotor.localPosition=new Vector3(0,4.5f,0);
        for(int i=0;i<3;i++) {
            float angle=i*120*Mathf.Deg2Rad;
            var direction=new Vector3(Mathf.Cos(angle),0,Mathf.Sin(angle));
            var arm=DevicePart("wind cup arm",direction*.29f,new Vector3(.58f,.035f,.035f),steel,rotor);
            arm.transform.localRotation=Quaternion.Euler(0,-i*120,0);
            DevicePart("wind cup",direction*.58f,new Vector3(.25f,.15f,.25f),dark,rotor,PrimitiveType.Sphere);
        }
        view.rotors.Add(rotor);
    }

    void BuildSensorSignals(DeviceView view)
    {
        // A few line vertices show a sampling cycle without lights or particle systems.
        var material=Mat("sampling signal","#79d6c6");material.EnableKeyword("_EMISSION");material.SetColor("_EmissionColor",new Color(.1f,.32f,.25f));
        for(int ring=0;ring<2;ring++) {
            var obj=new GameObject("sampling pulse illustration");obj.transform.SetParent(view.root,false);
            obj.transform.localPosition=view.data.kind=="GATEWAY"?new Vector3(0,3.9f,0):new Vector3(.6f,.3f,0);
            var line=obj.AddComponent<LineRenderer>();line.useWorldSpace=false;line.loop=true;line.positionCount=24;
            line.sharedMaterial=material;line.widthMultiplier=.025f;
            line.shadowCastingMode=UnityEngine.Rendering.ShadowCastingMode.Off;line.receiveShadows=false;
            for(int vertex=0;vertex<24;vertex++) {
                float angle=vertex*Mathf.PI/12;
                line.SetPosition(vertex,new Vector3(Mathf.Cos(angle),0,Mathf.Sin(angle))*.55f);
            }
            view.signals.Add(obj.transform);
        }
    }

    void AnimateMonitoringDevice(DeviceView view)
    {
        // Animation is an illustration. Lamp colors, sampled values and irrigation remain telemetry driven.
        bool active=deviceDemo || view.data.known;
        float phase=(PlotSeed(view.data.id)&1023)*.00614f;
        float time=Time.time+phase;
        if(view.cameraHead)view.cameraHead.localRotation=active?Quaternion.Euler(0,Mathf.Sin(time*.3f)*38,0):Quaternion.identity;
        if(view.trap) {
            float glow=active ? .28f+Mathf.Sin(time*1.7f)*.07f : 0;
            view.glow.SetColor("_EmissionColor",new Color(.42f,.26f,.85f)*glow);view.trap.SetPropertyBlock(view.glow);
        }
        for(int i=0;i<view.signals.Count;i++) {
            var signal=view.signals[i];signal.gameObject.SetActive(active);
            if(!active)continue;
            if(view.data.kind=="PEST") {
                float angle=time*(.7f+i*.08f)+i*2.4f;
                float radius=.7f+Mathf.Sin(time*.7f+i)*.17f;
                signal.localPosition=new Vector3(Mathf.Cos(angle)*radius,3.2f+Mathf.Sin(time*1.2f+i)*.32f,Mathf.Sin(angle)*radius);
            } else {
                float pulse=.5f+Mathf.Sin(time*.8f+i*Mathf.PI)*.5f;
                signal.localScale=Vector3.one*(.35f+pulse*.85f);
                // Small scale changes are enough to read as a sensor scan; no flashing or screen-space effects.
            }
        }
    }
}
