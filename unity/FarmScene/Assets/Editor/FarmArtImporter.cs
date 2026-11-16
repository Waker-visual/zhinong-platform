using UnityEditor;

// Import rules for the Blender-authored scenery kit (tools/blender/build_farm_art.py).
// Meshes stay readable so FarmScene can merge thousands of instances into a few draw calls;
// materials come from FarmScene at runtime, so none are generated from the FBX files.
public class FarmArtImporter : AssetPostprocessor
{
    void OnPreprocessModel()
    {
        if (!assetPath.Replace('\\', '/').Contains("/Resources/FarmArt/")) return;
        var importer = (ModelImporter)assetImporter;
        importer.globalScale = 1;
        importer.useFileScale = true;
        importer.bakeAxisConversion = true;
        importer.isReadable = true;
        importer.importCameras = false;
        importer.importLights = false;
        importer.importAnimation = false;
        importer.importBlendShapes = false;
        importer.animationType = ModelImporterAnimationType.None;
        importer.materialImportMode = ModelImporterMaterialImportMode.None;
        importer.addCollider = false;
        importer.importNormals = ModelImporterNormals.Import;
        importer.importTangents = ModelImporterTangents.None;
        importer.meshCompression = ModelImporterMeshCompression.Low;
        importer.optimizeMeshPolygons = true;
        importer.optimizeMeshVertices = true;
        importer.weldVertices = true;
    }
}
