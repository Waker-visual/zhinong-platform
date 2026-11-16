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
        FarmDeviceChecks.Validate();
        ReferenceImporter.Import();
        foreach(var imagePath in new[]{"Assets/Resources/SatelliteImagery/region.jpg","Assets/Resources/SatelliteImagery/context.jpg"}) {
        if(!File.Exists(imagePath)) continue;
        AssetDatabase.ImportAsset(imagePath,ImportAssetOptions.ForceUpdate);
        var imageImporter=AssetImporter.GetAtPath(imagePath) as TextureImporter;
        // With land-cover maps the photographs are only a fallback, so keep them small in the player.
        if(imageImporter) { imageImporter.maxTextureSize=File.Exists("Assets/Resources/SatelliteImagery/landcover-region.png")?1024:8192;imageImporter.mipmapEnabled=true;imageImporter.anisoLevel=8;imageImporter.wrapMode=TextureWrapMode.Clamp;imageImporter.textureCompression=TextureImporterCompression.CompressedHQ;imageImporter.SaveAndReimport(); }
        }
        // Land-cover maps are data, not colour: linear, mipmapped, clamped.
        foreach(var mapPath in new[]{"Assets/Resources/SatelliteImagery/landcover-region.png","Assets/Resources/SatelliteImagery/landflow-region.png","Assets/Resources/SatelliteImagery/landcover-context.png","Assets/Resources/SatelliteImagery/landflow-context.png"}) {
            if(!File.Exists(mapPath)) continue;
            AssetDatabase.ImportAsset(mapPath,ImportAssetOptions.ForceUpdate);
            if(!(AssetImporter.GetAtPath(mapPath) is TextureImporter map)) continue;
            map.sRGBTexture=false;map.mipmapEnabled=true;map.wrapMode=TextureWrapMode.Clamp;map.filterMode=FilterMode.Trilinear;map.anisoLevel=8;
            map.alphaSource=TextureImporterAlphaSource.FromInput;map.alphaIsTransparency=false;
            map.maxTextureSize=mapPath.Contains("landflow")?1024:4096;map.textureCompression=TextureImporterCompression.CompressedHQ;
            map.SaveAndReimport();
        }
        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        new GameObject("FarmScene").AddComponent<FarmScene>();
        var camera = new GameObject("Main Camera").AddComponent<Camera>(); camera.tag = "MainCamera";
        camera.clearFlags = CameraClearFlags.Skybox; camera.backgroundColor = new Color(.80f,.88f,.94f); camera.fieldOfView = 38; camera.farClipPlane = 1500;
        // Clear late-morning light: a slightly lower, warmer sun gives trees, houses and devices
        // readable cast shadows while the satellite ground stays evenly lit.
        var sun = new GameObject("Late morning sun").AddComponent<Light>(); sun.type = LightType.Directional; sun.intensity = 1.4f; sun.color = new Color(1,.95f,.85f); sun.shadows = LightShadows.Soft; sun.shadowStrength = .72f; sun.shadowBias = .04f; sun.shadowNormalBias = .3f; sun.transform.rotation = Quaternion.Euler(42,-35,0);
        RenderSettings.sun = sun;
        var sky = AssetDatabase.LoadAssetAtPath<Material>("Assets/FarmSky.mat");
        if (!sky) { sky = new Material(Shader.Find("Skybox/Procedural")); AssetDatabase.CreateAsset(sky,"Assets/FarmSky.mat"); }
        sky.SetFloat("_SunDisk",2); sky.EnableKeyword("_SUNDISK_HIGH_QUALITY");
        sky.SetFloat("_SunSize",.035f); sky.SetFloat("_AtmosphereThickness",.72f);
        sky.SetFloat("_Exposure",1.3f); sky.SetColor("_SkyTint",new Color(.5f,.62f,.78f));
        sky.SetColor("_GroundColor",camera.backgroundColor);
        RenderSettings.skybox = sky; EditorUtility.SetDirty(sky);
        RenderSettings.ambientMode = AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = new Color(.70f,.80f,.93f);
        RenderSettings.ambientEquatorColor = new Color(.70f,.74f,.66f);
        RenderSettings.ambientGroundColor = new Color(.42f,.45f,.36f);
        // Water and wet paddies reflect this sky instead of a flat grey default.
        RenderSettings.defaultReflectionMode = DefaultReflectionMode.Skybox;
        RenderSettings.reflectionIntensity = .9f;
        RenderSettings.fog = true; RenderSettings.fogColor = camera.backgroundColor; RenderSettings.fogMode = FogMode.Linear; RenderSettings.fogStartDistance = 170; RenderSettings.fogEndDistance = 570;
        QualitySettings.shadowDistance = 320; QualitySettings.shadows = ShadowQuality.All; QualitySettings.antiAliasing = 4; QualitySettings.shadowResolution = ShadowResolution.High; QualitySettings.shadowCascades = 4;
        PlayerSettings.colorSpace = ColorSpace.Linear;
        Directory.CreateDirectory("Assets/Scenes");
        const string scenePath = "Assets/Scenes/Farm.unity"; EditorSceneManager.SaveScene(scene, scenePath);
        // Preserve the runtime-created material shader in the player.
        var settings = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/GraphicsSettings.asset")[0]);
        var shaders = settings.FindProperty("m_AlwaysIncludedShaders");
        foreach(var shaderName in new[]{"Standard","Farm/Foliage","Farm/VertexSurface","Farm/Ground","Farm/OrthoGround","Farm/SprayMist","Farm/Canopy","Farm/Water","Farm/LandCover","Skybox/Procedural"}) {
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
        // Kit meshes get their materials at runtime, so the build cannot see that their vertex
        // colours are used; mesh-channel stripping would otherwise drop them.
        PlayerSettings.stripUnusedMeshComponents = false;
        PlayerSettings.SetManagedStrippingLevel(UnityEditor.Build.NamedBuildTarget.WebGL, ManagedStrippingLevel.Low);
        var output = Path.GetFullPath(Path.Combine(Application.dataPath, "../../../.cache/farm-scene"));
        var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions { scenes = new[] { scenePath }, locationPathName = output, target = BuildTarget.WebGL, options = BuildOptions.None });
        if (report.summary.result != BuildResult.Succeeded) throw new Exception("Farm scene build failed: " + report.summary.result);
    }
}
