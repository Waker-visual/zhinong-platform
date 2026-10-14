using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.Rendering;

// Authorized farm geometry with scenic demonstration assets; no business records in the player build.
public partial class FarmScene : MonoBehaviour
{
    [Serializable] public class Point { public float x, z; public bool alert; }
    [Serializable] public class Plot { public string id, parcelId, crop; public Point[] points; }
    [Serializable] public class Marker { public string id, kind; public float x,y; public bool visible; }
    [Serializable] public class Projection { public Marker[] points; public bool satelliteMapped; }
    float nextProjection;
    float validationAfter;
    bool validationReported;
    bool sceneVisible=true;
    readonly List<float> validationFrames=new List<float>();
    float farmRadius = 100, environmentRadius = 650, maxDistance = 280;
    Vector2 halfSize = new Vector2(100,100);
    Bounds farmBounds;
    [Serializable] public class Farm { public double originLatitude,originLongitude; public Plot[] plots; public Device[] devices; public Zone[] zones; }
    class Parcel { public string id, parcelId; public GameObject ground; public Vector3 center; public Material surface; public List<Renderer> edges = new List<Renderer>(); }
    readonly List<Parcel> parcels = new List<Parcel>();
    Device[] plantingDevices = Array.Empty<Device>();
    readonly List<Mesh> meshes = new List<Mesh>();
    readonly Dictionary<string, Material> materials = new Dictionary<string, Material>();
    Transform content;
    Camera cameraView;
    Material crops, earth, selected;
    float yaw = -12, pitch = 30, distance = 265;
    Vector3 target, pointer, panAnchor;
    readonly Plane panPlane = new Plane(Vector3.up, Vector3.zero);
    bool panning;
    bool dragged;
    string season = "summer";
#if UNITY_WEBGL && !UNITY_EDITOR
    [DllImport("__Internal")] static extern void FarmPlot(string id);
    [DllImport("__Internal")] static extern void FarmReady();
    [DllImport("__Internal")] static extern void FarmPoints(string json);
#endif
    Material Mat(string name, string hex, float smooth = 0)
    {
        if (materials.TryGetValue(name, out var existing)) return existing;
        var mat = new Material(Shader.Find(name == "crops" || name == "vegetable foliage" || name.StartsWith("crop cover ") ? "Farm/Foliage" : name == "earth" || name == "grass" || name == "road" || name == "water" || name == "paddy" || name.StartsWith("field ") ? "Farm/Ground" : "Standard"));
        ColorUtility.TryParseHtmlString(hex, out var color);
        mat.color = color; mat.SetFloat("_Glossiness", smooth);
        if(name=="crops" || name=="vegetable foliage")mat.SetFloat("_Detail",1);
        if (mat.HasProperty("_Rows")) { mat.SetFloat("_Rows",name == "earth" || name.StartsWith("field ") ? 1 : 0); mat.SetFloat("_Wet",name == "paddy" || name == "water" ? 1 : 0); } materials[name] = mat;
        return mat;
    }
    void Awake()
    {
        Application.targetFrameRate = 30;
        Application.runInBackground = true;
        // WebGL has its own default quality level; configure the running player explicitly.
        QualitySettings.shadows = ShadowQuality.All;
        QualitySettings.shadowDistance = 290;
        QualitySettings.shadowResolution = Screen.width < 700 ? ShadowResolution.Medium : ShadowResolution.High;
        QualitySettings.shadowCascades = 4;
        QualitySettings.pixelLightCount = 1;
        QualitySettings.antiAliasing = Screen.width < 700 ? 2 : 4;
        QualitySettings.anisotropicFiltering = AnisotropicFiltering.ForceEnable;
#if UNITY_WEBGL && !UNITY_EDITOR
        WebGLInput.captureAllKeyboardInput = false;
#endif
        crops = Mat("crops", "#7d973b"); earth = Mat("earth", "#766443"); selected = Mat("selected", "#c7b56c");
        cameraView = Camera.main;
        ResetView("");
    }
    void Start()
    {
#if UNITY_WEBGL && !UNITY_EDITOR
        FarmReady();
#endif
    }
    GameObject Box(string name, Vector3 position, Vector3 scale, Material mat, Transform parent, PrimitiveType type = PrimitiveType.Cube)
    {
        var obj = GameObject.CreatePrimitive(type); obj.name = name; obj.transform.SetParent(parent, false);
        obj.transform.localPosition = position; obj.transform.localScale = scale;
        obj.GetComponent<Renderer>().sharedMaterial = mat;
        Destroy(obj.GetComponent<Collider>()); return obj;
    }
    GameObject Beam(Vector3 a, Vector3 b, float width, float height, Material mat, Transform parent)
    {
        var obj = Box("field edge", (a + b) / 2, new Vector3(width, height, Vector3.Distance(a, b)), mat, parent);
        obj.transform.rotation = Quaternion.LookRotation(b - a);
        return obj;
    }
    public void LoadFarm(string json)
    {
        var farm = JsonUtility.FromJson<Farm>(json);
        plantingDevices = farm.devices ?? Array.Empty<Device>();
        if (content) Destroy(content.gameObject);
        foreach (var mesh in meshes) Destroy(mesh); meshes.Clear(); parcels.Clear(); deviceViews.Clear(); cropParcels.Clear(); cropTiles.Clear(); zoneViews.Clear();
        content = new GameObject("Farm geometry").transform;
        imageTreeMeshes.Clear();
        validationAfter=Time.unscaledTime+5;validationReported=false;validationFrames.Clear();
        satelliteMapped=false;
        if (farm.plots == null || farm.plots.Length == 0) return;
        farmBounds = new Bounds(new Vector3(farm.plots[0].points[0].x,0,farm.plots[0].points[0].z),Vector3.zero);
        foreach(var parcel in farm.plots) foreach(var point in parcel.points) farmBounds.Encapsulate(new Vector3(point.x,0,point.z));
        halfSize = new Vector2(farmBounds.extents.x, farmBounds.extents.z);
        farmRadius = Mathf.Max(40,halfSize.x,halfSize.y);
        maxDistance = Mathf.Max(100, farmBounds.size.magnitude * 1.65f);
        environmentRadius = Mathf.Max(1800, maxDistance * 4.5f);
        RenderSettings.fogStartDistance = maxDistance * 1.05f;
        RenderSettings.fogEndDistance = maxDistance * 2.6f;
        cameraView.farClipPlane = environmentRadius * 3;
        ResetView("");
        Material grass = Mat("grass", "#727951"), road = Mat("road", "#9b8b66"), water = Mat("water", "#537f87", .7f);
        BuildSatellite(farm);
        Landscape(grass);
        if(!satelliteMapped) {
        Waterway(road,water);
        float outer = farmRadius + 340;
        SurroundingField(-outer, -halfSize.x-38, -outer, outer, false);
        SurroundingField(-halfSize.x-35, halfSize.x+35, halfSize.y+38, outer, true);
        SurroundingField(halfSize.x+38, outer, -outer, outer, false);
        SurroundingField(-halfSize.x-35, halfSize.x+35, -outer, -halfSize.y-38, true);
        Box("service lane", new Vector3(0, .05f, -halfSize.y-12), new Vector3(halfSize.x*2+50, .2f, 5), road, content);
        Box("irrigation canal (illustration)", new Vector3(0, .05f, -halfSize.y-7), new Vector3(halfSize.x*2+50, .18f, 2), water, content);
        }
        foreach (var plot in farm.plots)
        {
            if (plot.points == null || plot.points.Length < 3) continue;
            var verts = new List<Vector3>();
            foreach (var p in plot.points) {
                var v = new Vector3(p.x, .15f, p.z);
                if (verts.Count == 0 || Vector3.Distance(verts[verts.Count - 1], v) > .001f) verts.Add(v);
            }
            if (verts.Count > 2 && Vector3.Distance(verts[0], verts[verts.Count - 1]) < .001f) verts.RemoveAt(verts.Count - 1);
            var indices = Triangulate(verts);
            if (indices.Count == 0) continue;
            var mesh = new Mesh(); mesh.SetVertices(verts); mesh.SetTriangles(indices, 0); mesh.RecalculateNormals(); meshes.Add(mesh);
            var obj = new GameObject("parcel"); obj.transform.SetParent(content);
            var surface = satelliteMapped ? satelliteGround : plot.crop == "unknown" ? earth : Mat("field " + plot.crop, plot.crop == "vegetable" ? "#617d3c" : "#819344");
            obj.AddComponent<MeshFilter>().sharedMesh = mesh; obj.AddComponent<MeshRenderer>().sharedMaterial = surface;
            obj.AddComponent<MeshCollider>().sharedMesh = mesh;
            Vector3 center = Vector3.zero; foreach (var v in verts) center += v; center /= verts.Count;
            var parcel = new Parcel { id = plot.id, parcelId = plot.parcelId, ground = obj, center = center, surface = surface };
            parcels.Add(parcel);
            for (int i = 0; i < verts.Count; i++) parcel.edges.Add(Beam(verts[i], verts[(i + 1) % verts.Count], .48f, .22f, road, content).GetComponent<Renderer>());
            cropParcels.Add(new CropParcel { polygon=verts, crop=plot.crop, seed=PlotSeed(plot.id) });
            var cropBounds=new Bounds(verts[0],Vector3.zero);foreach(var v in verts)cropBounds.Encapsulate(v);
            PlantRows(verts,plot.crop,cropBounds,PlotSeed(plot.id),true);
        }
        if(!satelliteMapped) {
        // Generic context is only a fallback where no georeferenced imagery is available.
        Buildings(new Vector3(halfSize.x*.45f, 0, halfSize.y+24)); Tractor(new Vector3(-20, 0, -halfSize.y-12));
        // Layered shelter belts surround every camera heading; scenic, not registered assets.
        for (int ring=0;ring<3;ring++) for(int i=0;i<135;i++) {
            float angle=(i*2.6667f+ring*1.3f)*Mathf.Deg2Rad;
            float radius=farmBounds.extents.magnitude+100+ring*85+Mathf.PerlinNoise(i*.9f,ring+8)*35;
            float x=Mathf.Cos(angle)*radius,z=Mathf.Sin(angle)*radius;
            Tree(new Vector3(x,Height(x,z),z),i+ring*139);
        }
        for (int i = 0; i < 70; i++) {
            float x = -halfSize.x-50 + i * (halfSize.x*2+100)/69, z = halfSize.y+55 + Mathf.PerlinNoise(i*.4f, 2)*22;
            Tree(new Vector3(x, Height(x,z), z), i);
        }
        for (int i = 0; i < 30; i++) { float x = -halfSize.x-46 - Mathf.PerlinNoise(i*.7f,3)*9, z = -halfSize.y+i*(halfSize.y*2)/29; Tree(new Vector3(x,Height(x,z),z),i); }
        Fence(new Vector3(-halfSize.x-10,0,-halfSize.y-17), new Vector3(halfSize.x+10,0,-halfSize.y-17));
        }
        if (farm.devices != null) foreach (var device in farm.devices) BuildDevice(device);
        if(farm.zones!=null)BuildIrrigation(farm.zones);
        SetSeason(season);
    }
    float Height(float x, float z) { if(satelliteMapped)return -.15f;float edge = Mathf.Clamp01((Mathf.Max(Mathf.Abs(x),Mathf.Abs(z))-farmRadius-450)/250); return -0.15f + edge * (3 + 9 * Mathf.PerlinNoise(x*.006f+4,z*.009f+4)); }
    void SurroundingField(float left,float right,float front,float back,bool harvest)
    {
        // Scenic context outside registered farm bounds; never part of the clickable business geometry.
        var mat=Mat(harvest ? "distant stubble" : "distant rows",harvest ? "#b2a471" : "#7b8550");
        if(!mat.mainTexture) {
            var texture=new Texture2D(128,128); var pixels=new Color[128*128];
            for(int y=0;y<128;y++) for(int x=0;x<128;x++) { float stripe=x%8<2?.58f:.87f; float value=stripe+Mathf.PerlinNoise(x*.7f,y*.6f)*.13f; pixels[y*128+x]=new Color(value,value,value); }
            texture.SetPixels(pixels);texture.Apply();mat.mainTexture=texture;
        }
        var v=new List<Vector3>();var t=new List<int>();var uv=new List<Vector2>();
        const int n=22;
        for(int z=0;z<=n;z++)for(int x=0;x<=n;x++){ float px=Mathf.Lerp(left,right,(float)x/n),pz=Mathf.Lerp(front,back,(float)z/n);v.Add(new Vector3(px,Height(px,pz)+.16f,pz));uv.Add(new Vector2(px*.035f,pz*.035f)); }
        for(int z=0;z<n;z++)for(int x=0;x<n;x++){int a=z*(n+1)+x;t.AddRange(new[]{a,a+n+1,a+1,a+1,a+n+1,a+n+2});}
        var mesh=new Mesh();mesh.SetVertices(v);mesh.SetTriangles(t,0);mesh.SetUVs(0,uv);mesh.RecalculateNormals();meshes.Add(mesh);
        var field=new GameObject("countryside illustration");field.transform.SetParent(content);field.AddComponent<MeshFilter>().sharedMesh=mesh;field.AddComponent<MeshRenderer>().sharedMaterial=mat;
    }
    void Landscape(Material grass)
    {
        var vertices = new List<Vector3>(); var triangles = new List<int>(); var uv = new List<Vector2>();
        const int n = 100;
        for (int z = 0; z <= n; z++) for (int x = 0; x <= n; x++) { float px=(x-50)*environmentRadius/50, pz=(z-50)*environmentRadius/50; vertices.Add(new Vector3(px,satelliteMapped ? -1.5f : Height(px,pz),pz)); uv.Add(new Vector2(px*.04f,pz*.04f)); }
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) { int a=z*(n+1)+x; triangles.AddRange(new[]{a,a+n+1,a+1,a+1,a+n+1,a+n+2}); }
        var mesh = new Mesh(); mesh.SetVertices(vertices); mesh.SetTriangles(triangles,0); mesh.SetUVs(0,uv); mesh.RecalculateNormals(); meshes.Add(mesh);
        var land = new GameObject("rolling countryside"); land.transform.SetParent(content); land.AddComponent<MeshFilter>().sharedMesh=mesh; land.AddComponent<MeshRenderer>().sharedMaterial=grass;
    }

    void Waterway(Material road, Material water)
    {
        // Scenic water network inspired by the Yangzhou aerial reference; not a measured utility network.
        var route = new List<Vector3>(); var towpath = new List<Vector3>();
        for(int i=0;i<=60;i++) {
            float z=-environmentRadius*.7f+i*environmentRadius*1.4f/60, x=-halfSize.x-48-Mathf.Sin(i*.12f)*13;
            route.Add(new Vector3(x,0,z)); towpath.Add(new Vector3(x-9,0,z));
        }
        Ribbon(route,5,water); Ribbon(towpath,2.8f,road);
        var stone=Mat("concrete", "#95968a");
        Box("canal crossing",new Vector3(-halfSize.x-48,.8f,-halfSize.y-12),new Vector3(20,.6f,5),stone,content);
        for(int side=-1;side<=1;side+=2) Fence(new Vector3(-halfSize.x-58,1,-halfSize.y-12+side*2.5f),new Vector3(-halfSize.x-38,1,-halfSize.y-12+side*2.5f));
        for(int i=0;i<5;i++) {
            float x=35+i*18,z=-halfSize.y-70-Mathf.Sin(i)*8;
            var p=new Vector3(x,Height(x,z),z);
            var wall=Mat("village wall","#ddd8c8");var tile=Mat("village roof","#5b6260");
            Box("distant village house",p+Vector3.up*3,new Vector3(10,6,8),wall,content);
            foreach(int side in new[]{-1,1})Box("village roof",p+new Vector3(0,6.9f,side*2.2f),new Vector3(11,.22f,5),tile,content).transform.Rotate(side*24,0,0);
            for(int k=0;k<3;k++)Box("village window",p+new Vector3(-3+k*3,3,-4.05f),new Vector3(1.3f,1.8f,.1f),tile,content);
            Tree(p+new Vector3(7,0,6),i*3);
        }
    }
    void Ribbon(List<Vector3> route,float halfWidth,Material mat)
    {
        var v=new List<Vector3>();var t=new List<int>();
        for(int i=0;i<route.Count;i++) {
            var direction=route[Mathf.Min(i+1,route.Count-1)]-route[Mathf.Max(i-1,0)];
            var side=Vector3.Cross(direction.normalized,Vector3.up)*halfWidth;
            foreach(var point in new[]{route[i]-side,route[i]+side})v.Add(new Vector3(point.x,Height(point.x,point.z)+.22f+route[i].y,point.z));
            if(i>0){int a=i*2;t.AddRange(new[]{a-2,a-1,a,a-1,a+1,a});}
        }
        var mesh=new Mesh();mesh.SetVertices(v);mesh.SetTriangles(t,0);mesh.RecalculateNormals();meshes.Add(mesh);
        var obj=new GameObject("scenic canal corridor");obj.transform.SetParent(content);obj.AddComponent<MeshFilter>().sharedMesh=mesh;obj.AddComponent<MeshRenderer>().sharedMaterial=mat;
    }
    void Fence(Vector3 a, Vector3 b)
    {
        var wood=Mat("fence", "#d0c2a0");
        for(float t=0;t<=1;t+=.025f) Box("fence post", Vector3.Lerp(a,b,t)+Vector3.up*.8f,new Vector3(.15f,1.6f,.15f),wood,content);
        Beam(a+Vector3.up*.65f,b+Vector3.up*.65f,.09f,.09f,wood,content);
        Beam(a+Vector3.up*1.25f,b+Vector3.up*1.25f,.09f,.09f,wood,content);
    }
    static float Cross(Vector3 a, Vector3 b, Vector3 c) => (b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x);
    static List<int> Triangulate(List<Vector3> v)
    {
        var remaining = new List<int>(); for (int i = 0; i < v.Count; i++) remaining.Add(i);
        float area = 0; for (int i = 0; i < v.Count; i++) area += v[i].x * v[(i+1)%v.Count].z - v[(i+1)%v.Count].x * v[i].z;
        if (area > 0) remaining.Reverse();
        var result = new List<int>();
        int budget = v.Count * v.Count;
        while (remaining.Count > 2 && budget-- > 0) {
            bool found = false;
            for (int i = 0; i < remaining.Count; i++) {
                int a = remaining[(i + remaining.Count - 1) % remaining.Count], b = remaining[i], c = remaining[(i + 1) % remaining.Count];
                if (Cross(v[a], v[b], v[c]) >= -.00001f) continue;
                bool inside = false;
                foreach (int p in remaining) if (p != a && p != b && p != c && Cross(v[a],v[b],v[p]) <= 0 && Cross(v[b],v[c],v[p]) <= 0 && Cross(v[c],v[a],v[p]) <= 0) { inside = true; break; }
                if (inside) continue;
                result.AddRange(new[] { a, b, c }); remaining.RemoveAt(i); found = true; break;
            }
            if (!found) return new List<int>();
        }
        return result;
    }
    static bool Inside(float x, float z, List<Vector3> polygon)
    {
        bool inside = false;
        for (int i = 0, j = polygon.Count - 1; i < polygon.Count; j = i++) {
            var a = polygon[i]; var b = polygon[j];
            if ((a.z > z) != (b.z > z) && x < (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x) inside = !inside;
        }
        return inside;
    }
    GameObject PlantRows(List<Vector3> polygon, string crop, Bounds tile, int seed,bool lowDetail=false)
    {
        var vertices = new List<Vector3>(); var triangles = new List<int>();
        var colors = new List<Color>();
        bool vegetable = crop == "vegetable", corn = crop == "corn", stubble = crop == "stubble", unknown = crop == "unknown";
        if (unknown) return null;
        var bounds = tile;
        // One row direction for the whole parcel, including every streamed tile.
        var along=Vector3.forward;float longest=0;
        for(int i=0;i<polygon.Count;i++) {
            var edge=polygon[(i+1)%polygon.Count]-polygon[i];edge.y=0;
            if(edge.sqrMagnitude>longest){longest=edge.sqrMagnitude;along=edge.normalized;}
        }
        if(along.z<0)along=-along;
        var across=new Vector3(along.z,0,-along.x);
        var rowBounds=new Bounds(new Vector3(Vector3.Dot(bounds.min,across),0,Vector3.Dot(bounds.min,along)),Vector3.zero);
        foreach(float tx in new[]{bounds.min.x,bounds.max.x})foreach(float tz in new[]{bounds.min.z,bounds.max.z}) {
            var corner=new Vector3(tx,0,tz);rowBounds.Encapsulate(new Vector3(Vector3.Dot(corner,across),0,Vector3.Dot(corner,along)));
        }
        // Combined mesh keeps thousands of individual plants within one draw call per field.
        float rowSpacing=vegetable?1.1f:.75f, plantSpacing=vegetable?.95f:.65f;
        // Global row/plant indices make seeded variation continuous across streamed tile seams.
        for(int row=Mathf.FloorToInt((rowBounds.min.x-.25f)/rowSpacing);row<=Mathf.CeilToInt((rowBounds.max.x+.25f)/rowSpacing);row++)
            for(int plant=Mathf.FloorToInt((rowBounds.min.z-.5f)/plantSpacing);plant<=Mathf.CeilToInt((rowBounds.max.z+.5f)/plantSpacing);plant++) {
                float u=(row+.5f)*rowSpacing+(PlantRandom(seed,row,plant,1)-.5f)*rowSpacing*.32f;
                float vAlong=(plant+.5f+(row%2)*.42f)*plantSpacing+(PlantRandom(seed,row,plant,2)-.5f)*plantSpacing*.38f;
                var position=across*u+along*vAlong;float x=position.x,z=position.z;
                if(x<bounds.min.x || x>=bounds.max.x || z<bounds.min.z || z>=bounds.max.z)continue;
                if(PlantRandom(seed,row,plant,3)<.025f || !Inside(x,z,polygon) || !Inside(x+.4f,z,polygon) || !Inside(x-.4f,z,polygon))continue;
                bool occupied=false;
                foreach(var device in plantingDevices) {
                    float clearance=device.kind=="DRONE"?2.2f:device.kind=="PUMP" || device.kind=="GATE"?2.5f:device.kind=="TRACTOR" || device.kind=="HARVESTER" || device.kind=="SPRAYER"?3.5f:.8f;
                    if((x-device.x)*(x-device.x)+(z-device.z)*(z-device.z)<clearance*clearance){occupied=true;break;}
                }
                if(occupied)continue;
                int start = vertices.Count;
                float h = (stubble ? .19f : vegetable ? .35f : corn ? 2.3f : 1.1f) * Mathf.Lerp(.82f,1.18f,PlantRandom(seed,row,plant,4));
                if(lowDetail) {
                    var root=new Vector3(x,.18f,z);
                    if(vegetable)for(int leaf=0;leaf<4;leaf++) {
                        float angle=leaf*Mathf.PI*.5f;
                        CurvedLeaf(vertices,triangles,root,new Vector3(Mathf.Cos(angle),0,Mathf.Sin(angle)),.4f,.29f,.14f,2);
                    } else {
                        AddBlade(vertices,triangles,root-across*.23f,root+across*.23f,root+Vector3.up*(h*.7f));
                        AddBlade(vertices,triangles,root-along*.23f,root+along*.23f,root+Vector3.up*(h*.7f));
                    }
                } else if (stubble) {
                    for(int stem=0;stem<4;stem++) {
                        var root=new Vector3(x+(PlantRandom(seed,row,plant,10+stem)-.5f)*.22f,-.10f,z+(PlantRandom(seed,row,plant,14+stem)-.5f)*.22f);
                        AddBlade(vertices,triangles,root-across*.015f,root+across*.015f,root+Vector3.up*h+along*.035f);
                        AddBlade(vertices,triangles,root-along*.015f,root+along*.015f,root+Vector3.up*h+along*.035f);
                    }
                } else if (vegetable) {
                    // Curved, tapered leaves with an upright inner heart, rather than a flat star.
                    for (int leaf=0;leaf<8;leaf++) {
                        float angle=leaf*2.39996f+PlantRandom(seed,row,plant,5)*Mathf.PI*2;
                        var axis=new Vector3(Mathf.Cos(angle),0,Mathf.Sin(angle));
                        float length=leaf<6?.38f+PlantRandom(seed,row,plant,20+leaf)*.12f:.20f;
                        float lift=leaf<6?.31f:.44f;
                        CurvedLeaf(vertices,triangles,new Vector3(x,.18f,z),axis,length,lift,leaf<6?.14f:.09f,5);
                    }
                } else for(int stalk=0;stalk<2;stalk++) {
                    float px=x+stalk*.16f, pz=z+Mathf.PerlinNoise(x+7,z+8)*.22f;
                    AddBlade(vertices,triangles,new Vector3(px-.025f,.23f,pz),new Vector3(px+.025f,.23f,pz),new Vector3(px+.11f,h+.25f,pz));
                    for(int leaf=0;leaf<3;leaf++) {
                        float angle=leaf*2.4f+stalk*1.3f;
                        CurvedLeaf(vertices,triangles,new Vector3(px,.3f+leaf*h*.2f,pz),new Vector3(Mathf.Cos(angle),0,Mathf.Sin(angle)),corn?.62f:.43f,h*.34f,corn?.09f:.032f,2);
                    }
                    for(int grain=0;grain<(vegetable ? 0 : 5);grain++) { float y=h+.06f+grain*.075f; AddBlade(vertices,triangles,new Vector3(px+.04f,y,pz),new Vector3(px+.1f,y+.14f,pz+.035f),new Vector3(px+.17f,y+.025f,pz)); }
                }
                float rotation=PlantRandom(seed,row,plant,5)*Mathf.PI*2;
                float size=Mathf.Lerp(.85f,1.12f,PlantRandom(seed,row,plant,6));
                float shade=Mathf.Lerp(.68f,1,PlantRandom(seed,row,plant,7));
                for(int c=start;c<vertices.Count;c++) {
                    var v=vertices[c];float dx=(v.x-x)*size,dz=(v.z-z)*size;
                    vertices[c]=new Vector3(x+dx*Mathf.Cos(rotation)-dz*Mathf.Sin(rotation),v.y,z+dx*Mathf.Sin(rotation)+dz*Mathf.Cos(rotation));
                    float light=shade*Mathf.Lerp(.65f,1,Mathf.Clamp01(v.y/1.2f));
                    colors.Add(new Color(light,light*Mathf.Lerp(.94f,1.03f,PlantRandom(seed,row,plant,8)),light*.9f,Mathf.Clamp01(v.y/3)));
                }
            }
        var mesh = new Mesh { indexFormat = IndexFormat.UInt32 }; mesh.SetVertices(vertices); mesh.SetTriangles(triangles,0); mesh.SetColors(colors);
        // AddBlade keeps each triangle's vertices together; double-sided indices must not cancel normals.
        var normals=new Vector3[vertices.Count];
        for(int i=0;i<vertices.Count;i+=3) {
            var normal=Vector3.Cross(vertices[i+1]-vertices[i],vertices[i+2]-vertices[i]).normalized;
            if(normal.y<0)normal=-normal;
            normal=(normal+Vector3.up*.45f).normalized;
            normals[i]=normals[i+1]=normals[i+2]=normal;
        }
        mesh.normals=normals; meshes.Add(mesh);
        var plants = new GameObject(lowDetail?"continuous crop cover":"crop rows"); plants.transform.SetParent(content); plants.AddComponent<MeshFilter>().sharedMesh = mesh;
        var material=lowDetail?Mat("crop cover "+crop,vegetable?"#658744":"#879e43"):stubble?Mat("cut stalks", "#8f8050"):vegetable?Mat("vegetable foliage", "#658744"):crops;
        if(lowDetail && !vegetable)material.color=crops.color;
        plants.AddComponent<MeshRenderer>().sharedMaterial=material;
        return plants;
    }
    static int PlotSeed(string id) { unchecked { int seed=17;foreach(char c in id)seed=seed*31+c;return seed; } }
    static float PlantRandom(int seed,int row,int plant,int channel) { unchecked { uint h=(uint)seed ^ (uint)row*374761393u ^ (uint)plant*668265263u ^ (uint)channel*2246822519u;h=(h^(h>>13))*1274126177u;return ((h^(h>>16))&0xffffff)/16777215f; } }
    static void AddBlade(List<Vector3> v,List<int> t,Vector3 a,Vector3 b,Vector3 c) { int n=v.Count;v.AddRange(new[]{a,b,c});t.AddRange(new[]{n,n+1,n+2,n+2,n+1,n}); }
    static void CurvedLeaf(List<Vector3> vertices,List<int> triangles,Vector3 root,Vector3 axis,float length,float lift,float width,int segments) {
        var side=new Vector3(-axis.z,0,axis.x);
        Vector3 Edge(float t,float sign) {
            float spread=Mathf.Pow(Mathf.Max(0,Mathf.Sin(Mathf.PI*t)),width>.08f?.65f:1)*width;
            return root+axis*(length*t)+Vector3.up*(lift*Mathf.Sin(t*Mathf.PI*.78f))+side*(spread*sign);
        }
        for(int i=0;i<segments;i++) {
            float a=(float)i/segments,b=(float)(i+1)/segments;
            var left=Edge(a,-1);var right=Edge(a,1);var nextLeft=Edge(b,-1);var nextRight=Edge(b,1);
            if(i>0)AddBlade(vertices,triangles,left,right,nextLeft);
            if(i<segments-1)AddBlade(vertices,triangles,right,nextRight,nextLeft);
        }
    }
    void Tree(Vector3 position, int seed)
    {
        if(ReferenceAsset("tree",position,7+seed%3,seed*137))return;
        Material bark = Mat("bark", "#65533a"), leaves = Mat("leaves", "#4f7046");
        Box("trunk", position + Vector3.up * 1.8f, new Vector3(.6f,3.6f,.6f), bark, content, PrimitiveType.Cylinder);
        for(int i=0;i<5;i++) { float angle=(i*137+seed*12)*Mathf.Deg2Rad; Box("leaf crown",position+new Vector3(Mathf.Cos(angle)*1.4f,4+i*.65f,Mathf.Sin(angle)*1.4f),new Vector3(3.3f+seed%2,3.4f,3.1f),leaves,content,PrimitiveType.Sphere); }
    }
    void Buildings(Vector3 p)
    {
        bool referenceBarn=ReferenceAsset("barn",p,10,180);
        if(referenceBarn) {
            for(int i=0;i<3;i++)ReferenceAsset("silo",p+new Vector3(24+i*8,0,0),14);
        }
        Material wall = Mat("wall", "#e3d9bd"), roof = Mat("roof", "#657d7b"), glass = Mat("glass", "#adcecc", .6f);
        if(!referenceBarn) {
        Box("farm shed", p + Vector3.up * 3, new Vector3(18,6,10), wall, content);
        for (int sign = -1; sign <= 1; sign += 2) Box("pitched roof", p + new Vector3(0,7,sign*2.7f), new Vector3(20,.35f,6.2f), roof, content).transform.Rotate(sign*25,0,0);
        for (int i = 0; i < 5; i++) Box("shed windows", p + new Vector3(-6+i*3,3,-5.05f), new Vector3(1.7f,2,.1f), glass, content);
        Box("barn door",p+new Vector3(0,1.7f,-5.12f),new Vector3(3,3.4f,.15f),roof,content);
        for(int i=0;i<7;i++) Box("roof seam",p+new Vector3(-9+i*3,7,0),new Vector3(.1f,.1f,11.5f),wall,content);
        var metal=Mat("silo steel", "#a9b7b1", .45f);
        for(int i=0;i<3;i++) {
            var silo=p+new Vector3(16+i*5,0,1);
            Box("grain silo",silo+Vector3.up*5,new Vector3(4,5,4),metal,content,PrimitiveType.Cylinder);
            Box("silo cap",silo+Vector3.up*10,new Vector3(4.1f,1.2f,4.1f),metal,content,PrimitiveType.Sphere);
            for(int j=0;j<9;j++) Box("silo seam",silo+Vector3.up*(.7f+j),new Vector3(4.06f,.025f,4.06f),roof,content,PrimitiveType.Cylinder);
            Box("silo ladder",silo+new Vector3(0,5,-2.08f),new Vector3(.5f,9,.12f),roof,content);
        }
        }
        for (int i = 0; i < 3; i++) {
            var g = p + new Vector3(-26 - i*9,0,0);
            Box("greenhouse", g + Vector3.up*1.2f, new Vector3(7,2.4f,14), glass, content);
            for(int k=0;k<10;k++) {
                float a=k*Mathf.PI/10,b=(k+1)*Mathf.PI/10;
                var pa=g+new Vector3(Mathf.Cos(a)*3.5f,2.4f+Mathf.Sin(a)*2,0); var pb=g+new Vector3(Mathf.Cos(b)*3.5f,2.4f+Mathf.Sin(b)*2,0);
                var panel=Box("curved greenhouse glazing",(pa+pb)/2,new Vector3(Vector3.Distance(pa,pb),.08f,14),glass,content); panel.transform.Rotate(0,0,Mathf.Atan2(pb.y-pa.y,pb.x-pa.x)*Mathf.Rad2Deg);
                for(int j=0;j<6;j++) { var offset=new Vector3(0,0,-7+j*2.8f); Beam(pa+offset,pb+offset,.08f,.08f,wall,content); }
            }
        }
    }
    void Tractor(Vector3 p)
    {
        if(ReferenceAsset("tractor",p,4.2f,90))return;
        Material body = Mat("tractor", "#bf6848"), tyre = Mat("tyres", "#303934"), cab = Mat("cab", "#709b9e", .6f);
        Box("tractor body", p + Vector3.up*1.5f, new Vector3(4,1.2f,2.3f), body, content);
        Box("tractor cab", p + new Vector3(-.8f,2.8f,0), new Vector3(1.8f,1.8f,1.9f), cab, content);
        Box("tractor roof", p + new Vector3(-.8f,3.8f,0), new Vector3(2.1f,.2f,2.3f), body, content);
        Material trim=Mat("tractor trim", "#e1d8b9"), dark=Mat("grille", "#3b4541");
        Box("exhaust",p+new Vector3(.6f,2.7f,.55f),new Vector3(.18f,1.6f,.18f),dark,content);
        Box("hood grille",p+new Vector3(2.02f,1.5f,0),new Vector3(.08f,.7f,1.7f),dark,content);
        foreach(int side in new[]{-1,1})Box("headlamp",p+new Vector3(2.08f,1.9f,side*.8f),new Vector3(.12f,.24f,.35f),trim,content);
        foreach (int side in new[] { -1,1 }) foreach (int axle in new[] { -1,1 }) {
            var wheel = Box("wheel", p + new Vector3(axle*1.3f,.85f,side*1.3f), new Vector3(1.7f,.45f,1.7f), tyre, content, PrimitiveType.Cylinder); wheel.transform.Rotate(90,0,0);
            Box("wheel hub",p+new Vector3(axle*1.3f,.85f,side*1.77f),new Vector3(.7f,.05f,.7f),trim,content,PrimitiveType.Cylinder).transform.Rotate(90,0,0);
            for(int i=0;i<12;i++){float a=i*Mathf.PI/6;Box("tyre tread",p+new Vector3(axle*1.3f+Mathf.Sin(a)*.84f,.85f+Mathf.Cos(a)*.84f,side*1.3f),new Vector3(.25f,.12f,.88f),tyre,content).transform.Rotate(0,0,-i*30);}
        }
    }
    bool ReferenceAsset(string name,Vector3 position,float height,float angle=0)
    {
        var prefab=Resources.Load<GameObject>("ReferenceFarm/"+name);if(!prefab)return false;
        var obj=Instantiate(prefab,content);obj.transform.localPosition=position;obj.transform.localRotation=Quaternion.Euler(0,angle,0);
        obj.transform.localScale=Vector3.one*(height/Mathf.Max(.01f,prefab.GetComponent<MeshFilter>().sharedMesh.bounds.size.y));
        return true;
    }
    public void SetSeason(string value) { season = value; ColorUtility.TryParseHtmlString(value == "autumn" ? "#c5a24f" : value == "spring" ? "#6b9e54" : "#879e43", out var color); crops.color = color; foreach(var entry in materials) { if(entry.Key.StartsWith("field ") && entry.Key!="field vegetable")entry.Value.color=color*.88f; if(entry.Key.StartsWith("crop cover ") && entry.Key!="crop cover vegetable")entry.Value.color=color; } }
    public void SelectPlot(string id)
    {
        FocusDevice("");
        var center=Vector3.zero;int count=0;
        foreach (var parcel in parcels) { foreach(var edge in parcel.edges) edge.sharedMaterial = parcel.id == id ? selected : materials["road"]; if (parcel.id == id) { center+=parcel.center;count++; } }
        if(count>0){ target=center/count;distance=90; }
    }
    public void SelectParcel(string id) {
        var parcel=parcels.Find(p=>p.parcelId==id);if(parcel==null)return;
        SelectPlot(parcel.id);target=parcel.center;distance=85;pitch=36;
        var inward=farmBounds.center-parcel.center;
        if(inward.sqrMagnitude>25)yaw=Mathf.Atan2(-inward.x,-inward.z)*Mathf.Rad2Deg;
    }
    public void Zoom(string direction) { distance = Mathf.Clamp(distance * (direction == "in" ? .78f : 1.28f),12,maxDistance); }
    public void ResetView(string unused) { FocusDevice("");target = farmBounds.center; yaw = -32; pitch = 30; distance = maxDistance*.72f; }
    void Update()
    {
        if (!cameraView) return;
        if (Input.GetMouseButtonDown(1)) {
            panning = panPlane.Raycast(cameraView.ScreenPointToRay(Input.mousePosition), out float rayDistance);
            if (panning) panAnchor = cameraView.ScreenPointToRay(Input.mousePosition).GetPoint(rayDistance);
        }
        if (Input.GetMouseButton(1) && panning) {
            var ray = cameraView.ScreenPointToRay(Input.mousePosition);
            if (panPlane.Raycast(ray, out float rayDistance)) {
                target += panAnchor - ray.GetPoint(rayDistance);
                target.x = Mathf.Clamp(target.x,-halfSize.x-35,halfSize.x+35); target.z = Mathf.Clamp(target.z,-halfSize.y-35,halfSize.y+35);
            }
        }
        if (Input.GetMouseButtonUp(1)) panning = false;
        if (Input.GetMouseButtonDown(0)) { pointer = Input.mousePosition; dragged = false; }
        if (Input.GetMouseButton(0)) {
            var delta = Input.mousePosition - pointer;
            if (delta.sqrMagnitude > 9) dragged = true;
            if (dragged) { yaw += delta.x * .25f; pitch = Mathf.Clamp(pitch - delta.y * .18f, 12, 82); pointer = Input.mousePosition; }
        }
        if (Input.GetMouseButtonUp(0) && !dragged && Physics.Raycast(cameraView.ScreenPointToRay(Input.mousePosition), out var hit)) {
            foreach (var parcel in parcels) if (hit.collider.gameObject == parcel.ground) {
                SelectPlot(parcel.id);
#if UNITY_WEBGL && !UNITY_EDITOR
                FarmPlot(parcel.id);
#endif
                break;
            }
        }
        if (Input.GetKey(KeyCode.LeftArrow)) target -= cameraView.transform.right * Time.deltaTime * distance * .45f;
        if (Input.GetKey(KeyCode.RightArrow)) target += cameraView.transform.right * Time.deltaTime * distance * .45f;
        var forward=Vector3.ProjectOnPlane(cameraView.transform.forward,Vector3.up).normalized;
        if (Input.GetKey(KeyCode.UpArrow)) target += forward * Time.deltaTime * distance * .45f;
        if (Input.GetKey(KeyCode.DownArrow)) target -= forward * Time.deltaTime * distance * .45f;
        target.x=Mathf.Clamp(target.x,-halfSize.x-35,halfSize.x+35);target.z=Mathf.Clamp(target.z,-halfSize.y-35,halfSize.y+35);
        float previousDistance=distance;
        distance = Mathf.Clamp(distance * Mathf.Exp(-Input.mouseScrollDelta.y * .3f), 12, maxDistance);
        if(Mathf.Abs(distance-previousDistance)>.001f) {
            var zoomRay=cameraView.ScreenPointToRay(Input.mousePosition);
            if(panPlane.Raycast(zoomRay,out float zoomHit))target=Vector3.Lerp(zoomRay.GetPoint(zoomHit),target,distance/previousDistance);
        }
        AnimateDevices();
        Shader.SetGlobalVector("_CropDetailTarget",new Vector4(target.x,0,target.z,0));
        UpdateCrops();
        if(sceneVisible && !validationReported && Time.unscaledTime>=validationAfter) {
            validationFrames.Add(Time.unscaledDeltaTime);
            if(Time.unscaledTime>=validationAfter+5) {
                float total=0;foreach(var frameTime in validationFrames)total+=frameTime;
                validationFrames.Sort();
                float p95=validationFrames[Mathf.Min(validationFrames.Count-1,Mathf.FloorToInt(validationFrames.Count*.95f))]*1000;
                Debug.Log("FarmScene render check: "+(validationFrames.Count/total).ToString("F1")+" fps, p95 "+p95.ToString("F1")+" ms, canvas "+Screen.width+"x"+Screen.height+", target 30 fps, focused "+Application.isFocused);
                validationReported=true;
            }
        }
        cameraView.transform.position = target + Quaternion.Euler(pitch,yaw,0) * new Vector3(0,0,-distance);
        cameraView.transform.LookAt(target);
#if UNITY_WEBGL && !UNITY_EDITOR
        if(Time.unscaledTime >= nextProjection) {
            nextProjection=Time.unscaledTime+.12f;
            var points=new List<Marker>();var seen=new HashSet<string>();
            foreach(var parcel in parcels) {
                if(!seen.Add(parcel.parcelId))continue;
                var screen=cameraView.WorldToViewportPoint(parcel.center+Vector3.up*2);
                points.Add(new Marker { id=parcel.parcelId,kind="parcel",x=screen.x,y=1-screen.y,visible=screen.z>0 && screen.x>.02f && screen.x<.98f && screen.y>.04f && screen.y<.96f });
            }
            foreach(var view in deviceViews.Values) {
                if(!view.root.gameObject.activeSelf)continue;
                var screen=cameraView.WorldToViewportPoint(view.root.position+Vector3.up*(view.data.kind=="PUMP" || view.data.kind=="SOIL" || view.data.kind=="DRONE" ? 2.2f : 4.6f));
                points.Add(new Marker { id=view.data.id,kind="device",x=screen.x,y=1-screen.y,visible=screen.z>0 && screen.x>.02f && screen.x<.98f && screen.y>.04f && screen.y<.96f });
            }
            foreach(var zone in zoneViews.Values) {
                var screen=cameraView.WorldToViewportPoint(zone.anchor+Vector3.up*1.5f);
                points.Add(new Marker { id=zone.data.id,kind="zone",x=screen.x,y=1-screen.y,visible=distance<180 && screen.z>0 && screen.x>.02f && screen.x<.98f && screen.y>.04f && screen.y<.96f });
            }
            FarmPoints(JsonUtility.ToJson(new Projection { points=points.ToArray(),satelliteMapped=satelliteMapped }));
        }
#endif
    }
    public void SetVisibility(string visibility) {
        sceneVisible=visibility=="visible";
        Application.targetFrameRate=sceneVisible?30:1;
        validationAfter=Time.unscaledTime+5;validationFrames.Clear();validationReported=false;
    }
}
