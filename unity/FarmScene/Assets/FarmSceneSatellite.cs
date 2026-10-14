using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

public partial class FarmScene
{
    [Serializable] class ImagePoint { public float x,y; }
    [Serializable] class ImagePath { public string kind; public float width; public ImagePoint[] points; }
    [Serializable] class ImageBuilding { public float x,y,width,length,angle,height; }
    [Serializable] class ImageContext { public int zoom,tileX,tileY,width,height; public ImagePath[] paths,woodlands,fields; public ImageBuilding[] buildings; }
    bool satelliteMapped;
    Material satelliteGround;
    ImageContext imageContext;
    double originLatitude,originLongitude;
    readonly Dictionary<Material,List<CombineInstance>> treeBatches=new Dictionary<Material,List<CombineInstance>>();
    readonly Dictionary<int,Mesh> imageTreeMeshes=new Dictionary<int,Mesh>();

    Vector3 ImagePosition(float x,float y)
    {
        double size=256.0*Math.Pow(2,imageContext.zoom);
        double lon=(imageContext.tileX*256.0+x)/size*360-180;
        double lat=Math.Atan(Math.Sinh(Math.PI*(1-2*(imageContext.tileY*256.0+y)/size)))*180/Math.PI;
        const double degree=Math.PI*6378137/180;
        return new Vector3((float)((lon-originLongitude)*Math.Cos(originLatitude*Math.PI/180)*degree),0,(float)((lat-originLatitude)*degree));
    }

    bool BuildSatellite(Farm farm)
    {
        var descriptor=Resources.Load<TextAsset>("SatelliteContext");
        var texture=Resources.Load<Texture2D>("SatelliteImagery/region");
        if(!descriptor || !texture)return false;
        imageContext=JsonUtility.FromJson<ImageContext>(descriptor.text);
        originLatitude=farm.originLatitude;originLongitude=farm.originLongitude;
        var nw=ImagePosition(0,0);var se=ImagePosition(imageContext.width,imageContext.height);
        // Never put a different farm onto this region's photograph.
        if(farmBounds.min.x<nw.x || farmBounds.max.x>se.x || farmBounds.min.z<se.z || farmBounds.max.z>nw.z)return false;
        satelliteMapped=true;
        if(!satelliteGround){satelliteGround=new Material(Shader.Find("Farm/OrthoGround"));materials["satellite ground"]=satelliteGround;}
        satelliteGround.mainTexture=texture;
        satelliteGround.SetVector("_WorldRect",new Vector4(nw.x,se.z,se.x-nw.x,nw.z-se.z));
        satelliteGround.color=new Color(1.14f,1.14f,1.14f);
        var mesh=new Mesh();mesh.vertices=new[]{new Vector3(nw.x,-.14f,se.z),new Vector3(nw.x,-.14f,nw.z),new Vector3(se.x,-.14f,nw.z),new Vector3(se.x,-.14f,se.z)};
        mesh.triangles=new[]{0,1,2,0,2,3};mesh.RecalculateNormals();meshes.Add(mesh);
        var ground=new GameObject("georeferenced satellite surface");ground.transform.SetParent(content);
        ground.AddComponent<MeshFilter>().sharedMesh=mesh;ground.AddComponent<MeshRenderer>().sharedMaterial=satelliteGround;
        BuildSatelliteSurroundings(nw,se);
        var road=Mat("image traced gravel","#8b8972");var channel=Mat("image traced water","#344b42",.68f);var bank=Mat("image traced bank","#555b3a");
        int seed=0;
        foreach(var path in imageContext.paths) {
            var points=new List<Vector3>();foreach(var p in path.points)points.Add(ImagePosition(p.x,p.y));
            if(path.kind=="trees") {
                for(int i=1;i<points.Count;i++) {
                    var a=points[i-1];var b=points[i];var side=Vector3.Cross((b-a).normalized,Vector3.up);
                    for(float d=0;d<Vector3.Distance(a,b);d+=6.5f)for(int row=0;row<2;row++) {
                        seed++;var p=Vector3.Lerp(a,b,d/Vector3.Distance(a,b))+side*((row-.5f)*path.width*.55f+(Noise(seed,2)-.5f)*3);
                        p+=(b-a).normalized*(Noise(seed,3)-.5f)*3;
                        AddImageTree(p,seed);
                    }
                }
            } else {
                ImageCorridor(points,path.width,path.kind,road,channel,bank);
            }
        }
        foreach(var wood in imageContext.woodlands) {
            var polygon=new List<Vector3>();foreach(var p in wood.points)polygon.Add(ImagePosition(p.x,p.y));
            var bounds=new Bounds(polygon[0],Vector3.zero);foreach(var p in polygon)bounds.Encapsulate(p);
            for(float x=bounds.min.x;x<bounds.max.x;x+=11)for(float z=bounds.min.z;z<bounds.max.z;z+=11) {
                seed++;var p=new Vector3(x+(Noise(seed,1)-.5f)*9,0,z+(Noise(seed,2)-.5f)*9);
                if(Inside(p.x,p.z,polygon))AddImageTree(p,seed);
            }
        }
        FlushImageTrees();
        foreach(var building in imageContext.buildings)ImageBuildingModel(building);
        if(imageContext.fields!=null)foreach(var field in imageContext.fields) {
            var polygon=new List<Vector3>();foreach(var point in field.points)polygon.Add(ImagePosition(point.x,point.y));
            cropParcels.Add(new CropParcel {polygon=polygon,crop="stubble",seed=++seed});
        }
        return true;
    }

    static float Noise(int seed,int channel)
    {
        unchecked {uint x=(uint)(seed*374761393+channel*668265263);x=(x^(x>>13))*1274126177;return (x^(x>>16))/(float)uint.MaxValue;}
    }
    void BuildSatelliteSurroundings(Vector3 innerNW,Vector3 innerSE)
    {
        var texture=Resources.Load<Texture2D>("SatelliteImagery/context");if(!texture)return;
        // Existing cache: z13 x7114..7117, y2871..2874, expressed in the detail image's pixels.
        var nw=ImagePosition((7114*4-imageContext.tileX)*256,(2871*4-imageContext.tileY)*256);
        var se=ImagePosition((7118*4-imageContext.tileX)*256,(2875*4-imageContext.tileY)*256);
        if(!materials.TryGetValue("satellite surroundings",out var material)) {
            material=new Material(Shader.Find("Farm/OrthoGround"));materials["satellite surroundings"]=material;
        }
        material.mainTexture=texture;material.color=satelliteGround.color;
        material.SetVector("_WorldRect",new Vector4(nw.x,se.z,se.x-nw.x,nw.z-se.z));
        var vertices=new List<Vector3>();var triangles=new List<int>();
        void Rect(float west,float south,float east,float north) {
            int i=vertices.Count;
            vertices.AddRange(new[]{new Vector3(west,-.14f,south),new Vector3(west,-.14f,north),new Vector3(east,-.14f,north),new Vector3(east,-.14f,south)});
            triangles.AddRange(new[]{i,i+1,i+2,i,i+2,i+3});
        }
        // Four strips with a central hole avoid depth fighting with the detailed image.
        Rect(nw.x,innerNW.z,se.x,nw.z);Rect(nw.x,se.z,se.x,innerSE.z);
        Rect(nw.x,innerSE.z,innerNW.x,innerNW.z);Rect(innerSE.x,innerSE.z,se.x,innerNW.z);
        var mesh=new Mesh();mesh.SetVertices(vertices);mesh.SetTriangles(triangles,0);mesh.RecalculateNormals();meshes.Add(mesh);
        var ground=new GameObject("georeferenced surrounding imagery");ground.transform.SetParent(content);
        ground.AddComponent<MeshFilter>().sharedMesh=mesh;ground.AddComponent<MeshRenderer>().sharedMaterial=material;
    }
    void AddImageTree(Vector3 p,int seed)
    {
        if(p.sqrMagnitude>1600*1600)return;
        var mesh=ImageTreeMesh(seed%3);
        if(!materials.TryGetValue("image tree canopy",out var material)) {
            material=new Material(Shader.Find("Farm/Canopy"));material.color=Color.white;materials["image tree canopy"]=material;
        }
        var mats=new[]{material};
        float scale=(10+Noise(seed,4)*8)/Mathf.Max(.01f,mesh.bounds.size.y);
        var transform=Matrix4x4.TRS(p,Quaternion.Euler(0,Noise(seed,5)*360,0),new Vector3(scale*(.7f+Noise(seed,6)*.4f),scale,scale*(.7f+Noise(seed,7)*.4f)));
        for(int i=0;i<mesh.subMeshCount;i++) {
            var mat=mats[Mathf.Min(i,mats.Length-1)];
            if(!treeBatches.ContainsKey(mat))treeBatches[mat]=new List<CombineInstance>();
            treeBatches[mat].Add(new CombineInstance {mesh=mesh,subMeshIndex=i,transform=transform});
        }
        if(seed%256==0)FlushImageTrees();
    }

    Mesh ImageTreeMesh(int variant)
    {
        if(imageTreeMeshes.TryGetValue(variant,out var cached))return cached;
        var vertices=new List<Vector3>();var normals=new List<Vector3>();var colors=new List<Color>();var triangles=new List<int>();
        void Crown(Vector3 center,Vector3 scale,Color color,int seed) {
            int start=vertices.Count;const int rows=5,columns=8;
            for(int y=0;y<=rows;y++)for(int x=0;x<=columns;x++) {
                float a=x*2*Mathf.PI/columns,b=y*Mathf.PI/rows;
                var n=new Vector3(Mathf.Sin(b)*Mathf.Cos(a),Mathf.Cos(b),Mathf.Sin(b)*Mathf.Sin(a));
                float rough=.9f+Noise(seed+y*17+x,8)*.2f;
                vertices.Add(center+Vector3.Scale(n,scale)*rough);normals.Add(new Vector3(n.x/scale.x,n.y/scale.y,n.z/scale.z).normalized);
                colors.Add((color*(.82f+Noise(seed+y*17+x,9)*.25f)).linear);
                if(y<rows && x<columns){int i=start+y*(columns+1)+x;triangles.AddRange(new[]{i,i+1,i+columns+1,i+1,i+columns+2,i+columns+1});}
            }
        }
        void Branch(Vector3 a,Vector3 b,float radius) {
            int start=vertices.Count;var rotation=Quaternion.FromToRotation(Vector3.up,(b-a).normalized);
            for(int end=0;end<2;end++)for(int i=0;i<7;i++) {
                var normal=rotation*new Vector3(Mathf.Cos(i*Mathf.PI/3),0,Mathf.Sin(i*Mathf.PI/3));
                vertices.Add((end==0?a:b)+normal*radius*(end==0?1:.6f));normals.Add(normal);colors.Add(new Color(.24f,.22f,.16f).linear);
                if(end==0 && i<6){int v=start+i;triangles.AddRange(new[]{v,v+7,v+1,v+1,v+7,v+8});}
            }
        }
        Branch(Vector3.zero,Vector3.up*.89f,.018f);
        for(int i=0;i<13;i++) {
            float angle=(i*137+variant*41)*Mathf.Deg2Rad;
            float height=.4f+i*.039f;float spread=Mathf.Sin((height-.2f)*Mathf.PI)*.09f;
            var center=new Vector3(Mathf.Cos(angle)*spread,height,Mathf.Sin(angle)*spread);
            Branch(new Vector3(0,height-.11f,0),center,.006f);
            Crown(center,new Vector3(.115f,.16f,.11f)*(1+Noise(i+variant*31,4)*.25f),variant==2?new Color(.31f,.34f,.13f):new Color(.22f,.30f,.14f),i+variant*101);
        }
        var mesh=new Mesh();mesh.SetVertices(vertices);mesh.SetNormals(normals);mesh.SetColors(colors);mesh.SetTriangles(triangles,0);mesh.RecalculateBounds();meshes.Add(mesh);imageTreeMeshes[variant]=mesh;return mesh;
    }

    void ImageCorridor(List<Vector3> points,float width,string kind,Material road,Material water,Material bank)
    {
        // Raised shoulders and sloping ditch banks remain visible from ground level.
        var vertices=new List<Vector3>();var triangles=new List<int>();
        float half=width*.5f;
        for(int i=0;i<points.Count;i++) {
            var direction=points[Mathf.Min(i+1,points.Count-1)]-points[Mathf.Max(0,i-1)];
            var side=Vector3.Cross(direction.normalized,Vector3.up);
            var cross=kind=="canal"?new[]{new Vector2(-half-1.2f,.22f),new Vector2(-half,-.08f),new Vector2(half,-.08f),new Vector2(half+1.2f,.22f)}:new[]{new Vector2(-half-.45f,-.1f),new Vector2(-half,.12f),new Vector2(half,.12f),new Vector2(half+.45f,-.1f)};
            foreach(var c in cross)vertices.Add(points[i]+side*c.x+Vector3.up*c.y);
            if(i>0)for(int j=0;j<3;j++){int a=(i-1)*4+j,b=i*4+j;triangles.AddRange(new[]{a,a+1,b,a+1,b+1,b});}
        }
        var mesh=new Mesh();mesh.SetVertices(vertices);mesh.SetTriangles(triangles,0);mesh.RecalculateNormals();meshes.Add(mesh);
        var obj=new GameObject("image traced "+kind+" cross section");obj.transform.SetParent(content);obj.AddComponent<MeshFilter>().sharedMesh=mesh;obj.AddComponent<MeshRenderer>().sharedMaterial=kind=="road"?road:bank;
        if(kind=="canal") {
            var lowered=new List<Vector3>();foreach(var p in points)lowered.Add(p-Vector3.up*.14f);
            Ribbon(lowered,half*.96f,water);
        }
    }
    void FlushImageTrees()
    {
        foreach(var batch in treeBatches) {
            if(batch.Value.Count==0)continue;
            var mesh=new Mesh {indexFormat=IndexFormat.UInt32};mesh.CombineMeshes(batch.Value.ToArray());meshes.Add(mesh);
            var obj=new GameObject("image traced shelter belt");obj.transform.SetParent(content);
            obj.AddComponent<MeshFilter>().sharedMesh=mesh;obj.AddComponent<MeshRenderer>().sharedMaterial=batch.Key;batch.Value.Clear();
        }
    }
    void ImageBuildingModel(ImageBuilding building)
    {
        var p=ImagePosition(building.x,building.y);if(p.sqrMagnitude>1600*1600)return;
        var root=new GameObject("image traced roof; inferred height").transform;root.SetParent(content);root.position=p;root.rotation=Quaternion.Euler(0,building.angle,0);
        var wall=Mat("image wall","#c0bdab");var roof=Mat("image metal roof","#727f7f",.3f);var dark=Mat("image doors","#455357");
        Box("walls",Vector3.up*building.height*.5f,new Vector3(building.width,building.height,building.length),wall,root);
        for(int side=-1;side<=1;side+=2)Box("pitched roof",new Vector3(side*building.width*.25f,building.height+.5f,0),new Vector3(building.width*.54f,.14f,building.length+1),roof,root).transform.Rotate(0,0,-side*14);
        Box("service door",new Vector3(0,1.5f,-building.length*.5f-.03f),new Vector3(3,3,.06f),dark,root);
        for(float z=-building.length*.4f;z<building.length*.4f;z+=4)for(int side=-1;side<=1;side+=2)Box("window",new Vector3(side*(building.width*.5f+.02f),2.6f,z),new Vector3(.04f,.9f,1.2f),dark,root);
    }
}
