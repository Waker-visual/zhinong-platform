using System;
using System.IO;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEditor.Build.Reporting;

public static class FarmBuilder
{
    public static void Build()
    {
        ReferenceImporter.Import();
        foreach(var imagePath in new[]{"Assets/Resources/SatelliteImagery/region.jpg","Assets/Resources/SatelliteImagery/context.jpg"}) {
        AssetDatabase.ImportAsset(imagePath,ImportAssetOptions.ForceUpdate);
        var imageImporter=AssetImporter.GetAtPath(imagePath) as TextureImporter;
        if(imageImporter) { imageImporter.maxTextureSize=8192;imageImporter.mipmapEnabled=true;imageImporter.anisoLevel=8;imageImporter.wrapMode=TextureWrapMode.Clamp;imageImporter.textureCompression=TextureImporterCompression.CompressedHQ;imageImporter.SaveAndReimport(); }
        }
        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        new GameObject("FarmScene").AddComponent<FarmScene>();
        var camera = new GameObject("Main Camera").AddComponent<Camera>(); camera.tag = "MainCamera";
        camera.clearFlags = CameraClearFlags.Skybox; camera.backgroundColor = new Color(.66f,.75f,.78f); camera.fieldOfView = 38; camera.farClipPlane = 1500;
        var sun = new GameObject("Late afternoon sun").AddComponent<Light>(); sun.type = LightType.Directional; sun.intensity = 1.35f; sun.color = new Color(1,.91f,.74f); sun.shadows = LightShadows.Soft; sun.transform.rotation = Quaternion.Euler(30,-55,0);
        RenderSettings.sun = sun;
        var sky = AssetDatabase.LoadAssetAtPath<Material>("Assets/FarmSky.mat");
        if (!sky) { sky = new Material(Shader.Find("Skybox/Procedural")); AssetDatabase.CreateAsset(sky,"Assets/FarmSky.mat"); }
        sky.SetFloat("_SunDisk",2); sky.EnableKeyword("_SUNDISK_HIGH_QUALITY");
        sky.SetFloat("_SunSize",.045f); sky.SetFloat("_AtmosphereThickness",1.15f);
        sky.SetFloat("_Exposure",1.1f); sky.SetColor("_SkyTint",new Color(.52f,.56f,.63f));
        sky.SetColor("_GroundColor",new Color(.66f,.75f,.78f));
        RenderSettings.skybox = sky; EditorUtility.SetDirty(sky);
        RenderSettings.ambientMode = AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = new Color(.48f,.61f,.75f);
        RenderSettings.ambientEquatorColor = new Color(.45f,.49f,.42f);
        RenderSettings.ambientGroundColor = new Color(.22f,.25f,.17f);
        RenderSettings.fog = true; RenderSettings.fogColor = camera.backgroundColor; RenderSettings.fogMode = FogMode.Linear; RenderSettings.fogStartDistance = 170; RenderSettings.fogEndDistance = 570;
        QualitySettings.shadowDistance = 320; QualitySettings.shadows = ShadowQuality.All; QualitySettings.antiAliasing = 4; QualitySettings.shadowResolution = ShadowResolution.High; QualitySettings.shadowCascades = 4;
        PlayerSettings.colorSpace = ColorSpace.Linear;
        Directory.CreateDirectory("Assets/Scenes");
        const string scenePath = "Assets/Scenes/Farm.unity"; EditorSceneManager.SaveScene(scene, scenePath);
        // Preserve the runtime-created material shader in the player.
        var settings = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/GraphicsSettings.asset")[0]);
        var shaders = settings.FindProperty("m_AlwaysIncludedShaders");
        foreach(var shaderName in new[]{"Standard","Farm/Foliage","Farm/VertexSurface","Farm/Ground","Farm/OrthoGround","Farm/SprayMist","Farm/Canopy","Skybox/Procedural"}) {
        var standard = Shader.Find(shaderName); bool included = false;
        for (int i = 0; i < shaders.arraySize; i++) if (shaders.GetArrayElementAtIndex(i).objectReferenceValue == standard) included = true;
        if (!included) { shaders.arraySize++; shaders.GetArrayElementAtIndex(shaders.arraySize-1).objectReferenceValue = standard; settings.ApplyModifiedProperties(); }
        }
        AssetDatabase.SaveAssets();
        PlayerSettings.companyName = "Zhihe"; PlayerSettings.productName = "FarmScene";
        PlayerSettings.WebGL.compressionFormat = WebGLCompressionFormat.Disabled;
        PlayerSettings.WebGL.decompressionFallback = false;
        PlayerSettings.WebGL.nameFilesAsHashes = false;
        PlayerSettings.WebGL.exceptionSupport = WebGLExceptionSupport.ExplicitlyThrownExceptionsOnly;
        PlayerSettings.SetManagedStrippingLevel(UnityEditor.Build.NamedBuildTarget.WebGL, ManagedStrippingLevel.Low);
        var output = Path.GetFullPath(Path.Combine(Application.dataPath, "../../../.cache/farm-scene"));
        var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions { scenes = new[] { scenePath }, locationPathName = output, target = BuildTarget.WebGL, options = BuildOptions.None });
        if (report.summary.result != BuildResult.Succeeded) throw new Exception("Farm scene build failed: " + report.summary.result);
    }
}
