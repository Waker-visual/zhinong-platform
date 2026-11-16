using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

// Scenic art kit authored in Blender (tools/blender/build_farm_art.py) and placed from the
// traced satellite descriptor. Everything here is illustration: it never changes registered
// plots, devices or irrigation, and it stays outside every registered plot polygon.
public partial class FarmScene
{
    enum TreeKind { Poplar, Willow, Broadleaf }
    enum TreeContext { ShelterBelt, Woodland, CanalBank, Farmyard }

    readonly Dictionary<string, Mesh> artMeshes = new Dictionary<string, Mesh>();
    readonly Dictionary<Material, List<CombineInstance>> propBatches = new Dictionary<Material, List<CombineInstance>>();
    readonly List<List<Vector3>> registeredPlots = new List<List<Vector3>>();
    readonly List<Vector3> wireVertices = new List<Vector3>();
    readonly List<int> wireTriangles = new List<int>();
    int artTreeCount, artPropCount;

    Mesh ArtMesh(string name)
    {
        // Resource meshes are shared assets: never add them to `meshes`, which LoadFarm destroys.
        if (artMeshes.TryGetValue(name, out var cached)) return cached;
        var prefab = Resources.Load<GameObject>("FarmArt/" + name);
        var filter = prefab ? prefab.GetComponentInChildren<MeshFilter>() : null;
        var mesh = filter ? filter.sharedMesh : null;
        artMeshes[name] = mesh && mesh.isReadable ? mesh : null;
        return artMeshes[name];
    }

    Material PropMaterial()
    {
        if (materials.TryGetValue("scenic props", out var material)) return material;
        material = new Material(Shader.Find("Farm/VertexSurface")) { color = Color.white };
        material.SetFloat("_Glossiness", .12f);
        materials["scenic props"] = material;
        return material;
    }

    void RememberRegisteredPlots(Farm farm)
    {
        registeredPlots.Clear();
        if (farm.plots == null) return;
        foreach (var plot in farm.plots) {
            if (plot.points == null || plot.points.Length < 3) continue;
            var polygon = new List<Vector3>();
            foreach (var p in plot.points) polygon.Add(new Vector3(p.x, 0, p.z));
            registeredPlots.Add(polygon);
        }
    }

    // Scenery keeps a margin from registered plots and device clearances.
    bool ScenicSpotFree(Vector3 p, float margin)
    {
        foreach (var polygon in registeredPlots) {
            if (Inside(p.x, p.z, polygon)) return false;
            for (int i = 0, j = polygon.Count - 1; i < polygon.Count; j = i++)
                if (DistanceToSegment(p, polygon[j], polygon[i]) < margin) return false;
        }
        foreach (var device in plantingDevices)
            if ((p.x - device.x) * (p.x - device.x) + (p.z - device.z) * (p.z - device.z) < (margin + 4) * (margin + 4)) return false;
        return true;
    }

    static float DistanceToSegment(Vector3 p, Vector3 a, Vector3 b)
    {
        var ab = new Vector2(b.x - a.x, b.z - a.z); var ap = new Vector2(p.x - a.x, p.z - a.z);
        float t = Mathf.Clamp01(Vector2.Dot(ap, ab) / Mathf.Max(.0001f, ab.sqrMagnitude));
        return (ap - ab * t).magnitude;
    }

    // Which tree grows where. Contexts come from the traced satellite descriptor:
    // ShelterBelt = traced tree rows along roads/ditches, Woodland = traced wood polygons,
    // CanalBank = scenic planting beside traced canals, Farmyard = around traced roofs.
    // `roll` is a stable 0..1 random number for this tree, so the choice never flickers.
    TreeKind TreeVariantFor(TreeContext context, float roll)
    {
        // Northern plain farmland: field shelter belts are mostly poplar with the odd willow or elm;
        // canal banks favour willow; traced woods and farmyards are mainly broadleaf (elm, ash) with poplar mixed in.
        switch (context) {
            case TreeContext.ShelterBelt: return roll < .78f ? TreeKind.Poplar : roll < .9f ? TreeKind.Willow : TreeKind.Broadleaf;
            case TreeContext.CanalBank: return roll < .75f ? TreeKind.Willow : TreeKind.Poplar;
            case TreeContext.Woodland: return roll < .3f ? TreeKind.Poplar : roll < .4f ? TreeKind.Willow : TreeKind.Broadleaf;
            default: return roll < .25f ? TreeKind.Poplar : TreeKind.Broadleaf;
        }
    }

    // Returns false when the kit is missing, so the procedural tree is used instead.
    bool AddArtTree(Vector3 p, int seed, TreeContext context)
    {
        var kind = TreeVariantFor(context, Noise(seed, 11));
        string name = kind == TreeKind.Poplar ? "tree_poplar_" + (seed & 1) : kind == TreeKind.Willow ? "tree_willow_0" : "tree_broadleaf_" + (seed & 1);
        // Belts beyond close camera range use the 12-vertex-clump variant (about a third of the vertices).
        bool far = new Vector2(p.x - farmBounds.center.x, p.z - farmBounds.center.z).magnitude > farmBounds.extents.magnitude + 220;
        var mesh = far ? ArtMesh(name + "_far") ?? ArtMesh(name) : ArtMesh(name);
        if (!mesh) return false;
        float height = kind == TreeKind.Poplar ? 14 + Noise(seed, 4) * 6 : kind == TreeKind.Willow ? 8 + Noise(seed, 4) * 3 : 9 + Noise(seed, 4) * 4;
        float scale = height / Mathf.Max(.01f, mesh.bounds.size.y);
        float girth = scale * (.85f + Noise(seed, 6) * .3f);
        var transform = Matrix4x4.TRS(p, Quaternion.Euler(0, Noise(seed, 5) * 360, 0), new Vector3(girth, scale, girth));
        var material = CanopyMaterial();
        if (!treeBatches.ContainsKey(material)) treeBatches[material] = new List<CombineInstance>();
        treeBatches[material].Add(new CombineInstance { mesh = mesh, subMeshIndex = 0, transform = transform });
        artTreeCount++;
        if (treeBatches[material].Count >= 192) FlushImageTrees();
        return true;
    }

    Material CanopyMaterial()
    {
        if (!materials.TryGetValue("image tree canopy", out var material)) {
            material = new Material(Shader.Find("Farm/Canopy")) { color = Color.white };
            materials["image tree canopy"] = material;
        }
        return material;
    }

    void AddProp(Mesh mesh, Matrix4x4 transform)
    {
        var material = PropMaterial();
        if (!propBatches.TryGetValue(material, out var list)) propBatches[material] = list = new List<CombineInstance>();
        list.Add(new CombineInstance { mesh = mesh, subMeshIndex = 0, transform = transform });
        artPropCount++;
        if (list.Count >= 256) FlushProps();
    }

    void FlushProps()
    {
        foreach (var batch in propBatches) {
            if (batch.Value.Count == 0) continue;
            var mesh = new Mesh { indexFormat = IndexFormat.UInt32 };
            mesh.CombineMeshes(batch.Value.ToArray(), true, true);
            meshes.Add(mesh);
            var obj = new GameObject("scenic props (illustration)"); obj.transform.SetParent(content);
            obj.AddComponent<MeshFilter>().sharedMesh = mesh; obj.AddComponent<MeshRenderer>().sharedMaterial = batch.Key;
            batch.Value.Clear();
        }
    }

    // Traced roof footprint -> farmhouse, long shed or film greenhouse; height stays inferred.
    bool ArtBuilding(ImageBuilding building, Vector3 p)
    {
        float longSide = Mathf.Max(building.width, building.length), shortSide = Mathf.Min(building.width, building.length);
        string name = longSide / Mathf.Max(1, shortSide) >= 2 ? (building.height < 3.6f ? "greenhouse" : "shed_long") : "house_rural";
        var mesh = ArtMesh(name);
        if (!mesh) return false;
        // Kit models are authored long side along Z for sheds, along X for houses.
        bool alongZ = name != "house_rural";
        bool rotate = alongZ ? building.width > building.length : building.length > building.width * 1.15f;
        float footprintX = rotate ? building.length : building.width, footprintZ = rotate ? building.width : building.length;
        var size = mesh.bounds.size;
        float eaves = name == "house_rural" ? 6.2f : name == "shed_long" ? 4.5f : 3.2f;
        float sy = Mathf.Max(.72f, building.height / eaves);
        var scale = new Vector3(footprintX / size.x, sy, footprintZ / size.z);
        var rotation = Quaternion.Euler(0, building.angle + (rotate ? 90 : 0), 0);
        AddProp(mesh, Matrix4x4.TRS(p, rotation, scale));
        var bales = name == "shed_long" ? ArtMesh("hay_bales") : null;
        if (bales) {
            var spot = p + rotation * new Vector3(footprintX * .5f + 3.5f, 0, footprintZ * .3f);
            if (ScenicSpotFree(spot, 3)) AddProp(bales, Matrix4x4.TRS(spot, rotation * Quaternion.Euler(0, 20, 0), Vector3.one));
        }
        return true;
    }

    // Concrete poles with three sagging conductors along traced roads.
    void PowerLine(List<Vector3> road, float roadWidth)
    {
        var pole = ArtMesh("utility_pole");
        if (!pole || road.Count < 2) return;
        var spots = new List<Vector3>(); var sides = new List<Vector3>(); var usable = new List<bool>();
        float next = 18;
        for (int i = 1; i < road.Count; i++) {
            var a = road[i - 1]; var b = road[i]; float length = Vector3.Distance(a, b);
            if (length < .01f) continue;
            var side = Vector3.Cross((b - a) / length, Vector3.up);
            for (; next < length; next += 42) {
                var p = Vector3.Lerp(a, b, next / length) + side * (roadWidth * .5f + 1.6f);
                spots.Add(p); sides.Add(side); usable.Add(p.sqrMagnitude < 900 * 900 && ScenicSpotFree(p, 2));
            }
            next -= length;
        }
        for (int i = 0; i < spots.Count; i++) {
            if (!usable[i]) continue;
            var rotation = Quaternion.LookRotation(Vector3.Cross(Vector3.up, sides[i]), Vector3.up);
            AddProp(pole, Matrix4x4.TRS(spots[i], rotation, Vector3.one));
            if (i == 0 || !usable[i - 1]) continue;
            foreach (float offset in new[] { -.95f, 0, .95f })
                Wire(spots[i - 1] + sides[i - 1] * offset + Vector3.up * 9.3f, spots[i] + sides[i] * offset + Vector3.up * 9.3f);
        }
    }

    void Wire(Vector3 a, Vector3 b)
    {
        const int segments = 8; const float halfWidth = .035f;
        float span = Vector3.Distance(a, b), sag = span * .018f;
        var across = Vector3.Cross((b - a).normalized, Vector3.up).normalized * halfWidth;
        int start = wireVertices.Count;
        for (int i = 0; i <= segments; i++) {
            float t = (float)i / segments;
            var point = Vector3.Lerp(a, b, t) + Vector3.down * (sag * 4 * t * (1 - t));
            wireVertices.Add(point - across + Vector3.up * halfWidth); wireVertices.Add(point + across - Vector3.up * halfWidth);
            if (i > 0) { int v = start + i * 2; wireTriangles.AddRange(new[] { v - 2, v - 1, v, v - 1, v + 1, v, v, v - 1, v - 2, v, v + 1, v - 1 }); }
        }
    }

    void FlushWires()
    {
        if (wireVertices.Count == 0) return;
        var mesh = new Mesh { indexFormat = IndexFormat.UInt32 };
        mesh.SetVertices(wireVertices); mesh.SetTriangles(wireTriangles, 0); mesh.RecalculateNormals(); meshes.Add(mesh);
        var obj = new GameObject("scenic power lines (illustration)"); obj.transform.SetParent(content);
        obj.AddComponent<MeshFilter>().sharedMesh = mesh;
        var renderer = obj.AddComponent<MeshRenderer>(); renderer.sharedMaterial = Mat("power line", "#2d3433", .3f);
        renderer.shadowCastingMode = ShadowCastingMode.Off;
        wireVertices.Clear(); wireTriangles.Clear();
    }

    // Willows lean over canal banks at irregular intervals, never inside a registered plot.
    void CanalPlanting(List<Vector3> canal, float width, ref int seed)
    {
        for (int i = 1; i < canal.Count; i++) {
            var a = canal[i - 1]; var b = canal[i]; float length = Vector3.Distance(a, b);
            if (length < .01f) continue;
            var side = Vector3.Cross((b - a) / length, Vector3.up);
            for (float d = 6; d < length; d += 14 + Noise(seed, 13) * 18) {
                seed++;
                if (Noise(seed, 12) < .45f) continue;
                float bank = Noise(seed, 14) < .5f ? -1 : 1;
                var p = Vector3.Lerp(a, b, d / length) + side * bank * (width * .5f + 2.5f + Noise(seed, 15) * 2);
                if (p.sqrMagnitude > 1600 * 1600 || !ScenicSpotFree(p, 3)) continue;
                AddArtTree(p, seed, TreeContext.CanalBank);
            }
        }
    }
}
