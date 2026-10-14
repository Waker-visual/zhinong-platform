using System;
using System.IO;
using UnityEngine;
using UnityEditor;

// The supplied capture stays private. Only a local build with the extracted manifest imports it.
public static class ReferenceImporter
{
    [Serializable] class Bundle { public Model[] assets; }
    [Serializable] class Model { public string name; public Vector3[] vertices, normals; public Vector2[] uv; public Color[] colors; public Part[] submeshes; public Surface[] materials; }
    [Serializable] class Part { public int[] indices; }
    [Serializable] class Surface { public string name, texture; public Color color; }
    public static void Import()
    {
        var source=Path.GetFullPath(Path.Combine(Application.dataPath,"../../../private-materials/_reference-models"));
        if(!File.Exists(Path.Combine(source,"manifest.json"))) return;
        const string destination="Assets/Resources/ReferenceFarm";
        Directory.CreateDirectory(destination); AssetDatabase.Refresh();
        var bundle=JsonUtility.FromJson<Bundle>(File.ReadAllText(Path.Combine(source,"manifest.json")));
        foreach(var model in bundle.assets) {
            var mesh=new Mesh { indexFormat=UnityEngine.Rendering.IndexFormat.UInt32, name=model.name, vertices=model.vertices, normals=model.normals, uv=model.uv, subMeshCount=model.submeshes.Length };
            for(int i=0;i<model.submeshes.Length;i++)mesh.SetTriangles(model.submeshes[i].indices,i);
            if(model.colors != null && model.colors.Length == model.vertices.Length) mesh.colors = model.colors;
            mesh.RecalculateBounds();
            var meshPath=destination+"/"+model.name+".asset";
            var stored=AssetDatabase.LoadAssetAtPath<Mesh>(meshPath);
            if(stored){EditorUtility.CopySerialized(mesh,stored);UnityEngine.Object.DestroyImmediate(mesh);mesh=stored;}else AssetDatabase.CreateAsset(mesh,meshPath);
            var materials=new Material[model.materials.Length];
            for(int i=0;i<materials.Length;i++) {
                var surface=model.materials[i];var path=destination+"/"+model.name+"-"+i+".mat";
                var mat=AssetDatabase.LoadAssetAtPath<Material>(path);
                if(!mat){mat=new Material(Shader.Find("Standard"));AssetDatabase.CreateAsset(mat,path);}
                mat.shader=Shader.Find(model.colors != null && model.colors.Length == model.vertices.Length ? "Farm/VertexSurface" : "Standard");
                mat.enableInstancing=true; mat.name=surface.name;mat.color=surface.color;mat.SetFloat("_Glossiness",.15f);
                if(!string.IsNullOrEmpty(surface.texture)) {
                    var texturePath=destination+"/"+surface.texture;
                    File.Copy(Path.Combine(source,surface.texture),texturePath,true);AssetDatabase.ImportAsset(texturePath);
                    mat.mainTexture=AssetDatabase.LoadAssetAtPath<Texture2D>(texturePath);
                }
                materials[i]=mat;EditorUtility.SetDirty(mat);
            }
            var go=new GameObject(model.name);go.AddComponent<MeshFilter>().sharedMesh=mesh;go.AddComponent<MeshRenderer>().sharedMaterials=materials;
            PrefabUtility.SaveAsPrefabAsset(go,destination+"/"+model.name+".prefab");UnityEngine.Object.DestroyImmediate(go);
        }
        File.Copy(Path.Combine(source,"SOURCE.md"),destination+"/SOURCE.md",true);
        AssetDatabase.SaveAssets();AssetDatabase.Refresh();
    }
}
