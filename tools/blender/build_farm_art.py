"""Generate the farm scene's scenic art kit in Blender and export FBX for Unity.

Run from the repository root (Blender 4.2+; tested with 5.3):
    blender --background --factory-startup --python tools/blender/build_farm_art.py

Everything is procedural and seeded, so the committed FBX files can be rebuilt
byte-for-byte equivalent. Assets are illustrative scenery only: none of them
represents a registered farm asset, and placement is decided in Unity from the
traced satellite descriptor.

Conventions shared with unity/FarmScene/Assets:
  * 1 Blender unit = 1 metre, Y-up after export, pivot at ground centre.
  * Colours are vertex colours in linear space (one shared material per family).
  * Tree foliage satisfies g > r * 1.12 so Farm/Canopy can tell leaves from bark.
  * Prop meshes use Farm/VertexSurface; read/write is enabled by the importer
    so FarmScene can combine instances into a few draw calls.
"""

import math
import os
import random
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "unity", "FarmScene", "Assets", "Resources", "FarmArt")
LOG = os.path.join(ROOT, ".cache", "blender-farm-art.log")
# Near trees use 42-vertex clumps; "_far" variants use 12-vertex clumps for distant belts.
FOLIAGE_DETAIL = {"subdivisions": 2}


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hex_linear(value, scale=1.0):
    value = value.lstrip("#")
    rgb = [int(value[i : i + 2], 16) / 255 for i in (0, 2, 4)]
    return tuple(srgb_to_linear(min(1.0, c * scale)) for c in rgb) + (1.0,)


class Builder:
    """Accumulates coloured parts into one bmesh, then emits one object."""

    def __init__(self, name, seed):
        self.name = name
        self.bm = bmesh.new()
        # Float colours are stored linear, so the LINEAR FBX export writes them unchanged.
        self.color = self.bm.loops.layers.float_color.new("Color")
        self.rng = random.Random(seed)
        # Per-vertex normal overrides (foliage uses crown-centred normals).
        self.normal_override = {}

    def _paint(self, faces, color, jitter=0.0):
        for face in faces:
            shade = 1.0 + (self.rng.random() - 0.5) * jitter if jitter else 1.0
            c = tuple(min(1.0, ch * shade) for ch in color[:3]) + (1.0,)
            for loop in face.loops:
                loop[self.color] = c

    def box(self, center, size, color, rot_z=0.0, rot_x=0.0, jitter=0.0):
        geom = bmesh.ops.create_cube(self.bm, size=1.0)
        verts = geom["verts"]
        m = (
            Matrix.Translation(Vector(center))
            @ Matrix.Rotation(rot_z, 4, "Z")
            @ Matrix.Rotation(rot_x, 4, "X")
            @ Matrix.Diagonal(Vector(size).to_4d())
        )
        bmesh.ops.transform(self.bm, matrix=m, verts=verts)
        faces = list({f for v in verts for f in v.link_faces})
        self._paint(faces, color, jitter)
        return verts

    def cylinder(self, a, b, r1, r2, color, segments=6, cap=True):
        a, b = Vector(a), Vector(b)
        axis = b - a
        geom = bmesh.ops.create_cone(
            self.bm,
            cap_ends=cap,
            cap_tris=False,
            segments=segments,
            radius1=r1,
            radius2=r2,
            depth=axis.length,
        )
        verts = geom["verts"]
        rot = axis.normalized().to_track_quat("Z", "Y").to_matrix().to_4x4()
        m = Matrix.Translation((a + b) / 2) @ rot
        bmesh.ops.transform(self.bm, matrix=m, verts=verts)
        faces = list({f for v in verts for f in v.link_faces})
        self._paint(faces, color)
        return verts

    def blob(self, center, radii, color, crown_center, roughness=0.18, jitter=0.0):
        """Low-poly foliage clump; normals point away from the crown centre.

        One colour per clump keeps vertices shared (per-face colours would split every
        vertex on import); variation comes from leaf() per clump instead.
        """
        geom = bmesh.ops.create_icosphere(self.bm, subdivisions=FOLIAGE_DETAIL["subdivisions"], radius=1.0)
        verts = geom["verts"]
        for v in verts:
            n = v.co.normalized()
            k = 1.0 + (self.rng.random() - 0.5) * 2 * roughness
            v.co = Vector(center) + Vector((n.x * radii[0], n.y * radii[1], n.z * radii[2])) * k
            outward = (v.co - Vector(crown_center))
            outward.z *= 0.7
            self.normal_override[v] = (outward.normalized() * 0.75 + n * 0.25).normalized()
        faces = list({f for v in verts for f in v.link_faces})
        self._paint(faces, color, jitter)
        return verts

    def quad_strip(self, points, color):
        verts = [self.bm.verts.new(p) for p in points]
        faces = []
        for i in range(0, len(verts) - 2, 2):
            faces.append(self.bm.faces.new((verts[i], verts[i + 1], verts[i + 3], verts[i + 2])))
        self._paint(faces, color)
        return verts

    def finish(self, collection):
        bmesh.ops.remove_doubles(self.bm, verts=[v for v in self.bm.verts if v not in self.normal_override], dist=0.0005)
        mesh = bpy.data.meshes.new(self.name)
        self.bm.verts.index_update()
        overrides = {v.index: self.normal_override[v] for v in self.bm.verts if v in self.normal_override}
        self.bm.to_mesh(mesh)
        self.bm.free()
        mesh.color_attributes.active_color_index = 0
        mesh.color_attributes.render_color_index = 0
        for poly in mesh.polygons:
            poly.use_smooth = bool(overrides)
        if overrides:
            normals = []
            for v in mesh.vertices:
                normals.append(tuple(overrides.get(v.index, v.normal)))
            mesh.normals_split_custom_set_from_vertices(normals)
        obj = bpy.data.objects.new(self.name, mesh)
        collection.objects.link(obj)
        return obj


# ---------------------------------------------------------------- trees

BARK = hex_linear("#5b4a36")
BARK_LIGHT = hex_linear("#9a9282")  # poplar bark is pale grey


def leaf(rng, base, spread=0.12):
    r, g, b, _ = base
    k = 1.0 + (rng.random() - 0.5) * spread * 2
    hue = (rng.random() - 0.5) * 0.06
    return (max(0.0, r * k + hue * 0.3), g * k, max(0.0, b * k - hue * 0.2), 1.0)


def tree_poplar(col, seed):
    """Columnar shelter-belt poplar, ~16 m."""
    t = Builder(f"tree_poplar_{seed}", seed)
    rng = t.rng
    height = 16.0
    t.cylinder((0, 0, 0), (0, 0, height * 0.8), 0.24, 0.07, BARK_LIGHT, segments=6)
    base = hex_linear("#4c6a2e") if seed % 2 == 0 else hex_linear("#5a7330")
    crown = Vector((0, 0, height * 0.58))
    layers = 8
    for i in range(layers):
        z = height * (0.22 + 0.72 * i / (layers - 1))
        width = 1.9 * math.sin(math.pi * (0.18 + 0.78 * i / layers)) + 0.35
        for k in range(2):
            ang = rng.random() * math.tau
            off = Vector((math.cos(ang), math.sin(ang), 0)) * width * 0.35
            t.blob(
                Vector((off.x, off.y, z + (rng.random() - 0.5) * 0.6)),
                (width, width * 0.9, 1.45),
                leaf(rng, base),
                crown,
            )
    return t.finish(col)


def tree_willow(col, seed):
    """Canal-side weeping willow, ~10 m with drooping skirt."""
    t = Builder(f"tree_willow_{seed}", seed)
    rng = t.rng
    t.cylinder((0, 0, 0), (0.3, 0.1, 3.4), 0.34, 0.22, BARK, segments=6)
    t.cylinder((0.3, 0.1, 3.4), (1.6, 0.6, 6.3), 0.2, 0.08, BARK, segments=5)
    t.cylinder((0.3, 0.1, 3.4), (-1.2, -0.4, 6.0), 0.18, 0.08, BARK, segments=5)
    base = hex_linear("#7d9a3c")
    crown = Vector((0.2, 0, 6.2))
    for i in range(9):
        ang = i / 9 * math.tau + rng.random() * 0.4
        r = 2.6 + rng.random() * 0.8
        top = Vector((math.cos(ang) * r * 0.55, math.sin(ang) * r * 0.55, 7.4 + rng.random() * 1.2))
        t.blob(top, (1.9, 1.9, 1.3), leaf(rng, base), crown)
        # Drooping curtain: elongated clumps hanging from the outer crown.
        hang = Vector((math.cos(ang) * r, math.sin(ang) * r, 4.6 + rng.random() * 0.8))
        t.blob(hang, (0.9, 0.9, 2.4), leaf(rng, base, 0.16), crown, roughness=0.26)
    t.blob((0.2, 0, 8.6), (2.4, 2.4, 1.4), leaf(rng, base), crown)
    return t.finish(col)


def tree_broadleaf(col, seed):
    """Rounded village broadleaf (camphor / Chinese parasol style), ~11 m."""
    t = Builder(f"tree_broadleaf_{seed}", seed)
    rng = t.rng
    t.cylinder((0, 0, 0), (0, 0, 4.6), 0.38, 0.25, BARK, segments=6)
    for ang in (0.3, 2.4, 4.4):
        tip = Vector((math.cos(ang) * 2.0, math.sin(ang) * 2.0, 7.0))
        t.cylinder((0, 0, 4.2), tip, 0.2, 0.08, BARK, segments=5)
    base = hex_linear("#3f6128") if seed % 2 else hex_linear("#4d6d2c")
    crown = Vector((0, 0, 7.6))
    for i in range(10):
        u = rng.random() * math.tau
        v = rng.random() * 0.9
        r = 2.4
        c = crown + Vector((math.cos(u) * r * math.cos(v), math.sin(u) * r * math.cos(v), math.sin(v) * 1.8 - 0.4))
        t.blob(c, (2.3, 2.3, 1.9), leaf(rng, base), crown)
    t.blob(crown + Vector((0, 0, 1.5)), (2.6, 2.6, 1.9), leaf(rng, base), crown)
    return t.finish(col)


# ---------------------------------------------------------------- buildings

WALL = hex_linear("#eae6da")
WALL_BASE = hex_linear("#9d9a8f")
ROOF_TILE = hex_linear("#4d5457")
ROOF_RIDGE = hex_linear("#3a3f42")
WINDOW = hex_linear("#2f3d44")
FRAME = hex_linear("#c9c3b4")
DOOR = hex_linear("#6b3f2c")
SHED_WALL = hex_linear("#d3d6d1")
SHED_ROOF_A = hex_linear("#8fa3ad")
SHED_ROOF_B = hex_linear("#7b8f99")
STEEL = hex_linear("#a6aaa6")
FILM = hex_linear("#e5ebe8")
HAY = hex_linear("#c9a85c")
HAY_END = hex_linear("#b08f48")
CONCRETE = hex_linear("#b4b2a8")
INSULATOR = hex_linear("#4a5a52")


def gable_roof(t, width, depth, wall_h, rise, color, overhang=0.45, ridge=ROOF_RIDGE):
    """Two pitched slabs along +Y with a ridge cap; width along X."""
    half = width / 2 + overhang
    slope = math.atan2(rise, width / 2)
    length = math.hypot(half, rise * half / (width / 2))
    for side in (-1, 1):
        cx = side * half / 2
        cz = wall_h + rise / 2 - 0.02
        verts = t.box((cx, 0, cz), (length, depth + overhang * 2, 0.18), color)
        m = Matrix.Translation(Vector((cx, 0, cz))) @ Matrix.Rotation(side * slope, 4, "Y") @ Matrix.Translation(Vector((-cx, 0, -cz)))
        bmesh.ops.transform(t.bm, matrix=m, verts=verts)
    t.box((0, 0, wall_h + rise + 0.06), (0.35, depth + overhang * 2 + 0.1, 0.22), ridge)
    # Gable triangles close the ends.
    for side in (-1, 1):
        y = side * depth / 2
        a = t.bm.verts.new((-width / 2, y, wall_h))
        b = t.bm.verts.new((width / 2, y, wall_h))
        c = t.bm.verts.new((0, y, wall_h + rise))
        f = t.bm.faces.new((a, b, c) if side < 0 else (b, a, c))
        t._paint([f], WALL)


def house_rural(col, seed):
    """Two-storey Subei/Jiangnan farmhouse: white walls, grey tile gable roof.

    Nominal footprint 10 m (X) x 8 m (Y), 6.2 m eaves. FarmScene scales it to
    the traced roof footprint, so details are kept proportional rather than exact.
    """
    t = Builder(f"house_rural_{seed}", seed)
    w, d, h = 10.0, 8.0, 6.2
    t.box((0, 0, 0.3), (w + 0.2, d + 0.2, 0.6), WALL_BASE)
    t.box((0, 0, h / 2), (w, d, h), WALL)
    t.box((0, 0, 3.15), (w + 0.12, d + 0.12, 0.16), FRAME)  # floor band
    gable_roof(t, w, d, h, 2.3, ROOF_TILE)
    # Windows front/back on both floors, door at front centre.
    for side in (-1, 1):
        y = side * (d / 2 + 0.03)
        for floor_z in (1.7, 4.6):
            for x in (-3.3, -1.1, 1.1, 3.3):
                if side < 0 and floor_z < 2 and abs(x) < 1.5:
                    continue
                t.box((x, y, floor_z), (1.2, 0.08, 1.35), WINDOW)
                t.box((x, y + side * 0.02, floor_z - 0.75), (1.4, 0.12, 0.1), FRAME)
    t.box((0, -d / 2 - 0.04, 1.15), (1.8, 0.1, 2.3), DOOR)
    t.box((0, -d / 2 - 0.6, 2.55), (2.6, 1.2, 0.12), ROOF_TILE)  # door canopy
    # Small single-storey kitchen annex typical of rural courtyards.
    t.box((w / 2 + 1.6, 1.2, 1.5), (3.2, 4.6, 3.0), WALL)
    t.box((w / 2 + 1.6, 1.2, 3.12), (3.6, 5.0, 0.24), ROOF_TILE)
    t.cylinder((w / 2 + 2.4, 2.6, 3.0), (w / 2 + 2.4, 2.6, 4.4), 0.25, 0.25, WALL_BASE, segments=4)
    return t.finish(col)


def shed_long(col, seed):
    """Steel-frame agricultural shed: low walls, ribbed metal roof.

    Nominal 10 m (X) x 25 m (Y), 4.5 m eaves.
    """
    t = Builder(f"shed_long_{seed}", seed)
    w, d, h = 10.0, 25.0, 4.5
    t.box((0, 0, 0.25), (w + 0.1, d + 0.1, 0.5), CONCRETE)
    t.box((0, 0, h / 2), (w, d, h), SHED_WALL)
    # Ribbed roof: alternate tones every 0.9 m gives the corrugation at distance.
    rise = 1.4
    slope = math.atan2(rise, w / 2)
    length = math.hypot(w / 2 + 0.4, rise)
    ribs = int(d / 0.9)
    for i in range(ribs):
        y = -d / 2 + (i + 0.5) * d / ribs
        for side in (-1, 1):
            cx = side * (w / 2 + 0.4) / 2
            cz = h + rise / 2
            verts = t.box((cx, y, cz), (length, d / ribs + 0.01, 0.12), SHED_ROOF_A if i % 2 else SHED_ROOF_B)
            m = Matrix.Translation(Vector((cx, y, cz))) @ Matrix.Rotation(side * slope, 4, "Y") @ Matrix.Translation(Vector((-cx, -y, -cz)))
            bmesh.ops.transform(t.bm, matrix=m, verts=verts)
    for side in (-1, 1):
        y = side * d / 2
        a = t.bm.verts.new((-w / 2, y, h))
        b = t.bm.verts.new((w / 2, y, h))
        c = t.bm.verts.new((0, y, h + rise))
        t._paint([t.bm.faces.new((a, b, c) if side < 0 else (b, a, c))], SHED_WALL)
        # Roller doors on both gable ends.
        t.box((0, y + side * 0.05, 1.8), (4.2, 0.1, 3.6), STEEL)
        for k in range(9):
            t.box((0, y + side * 0.11, 0.2 + k * 0.4), (4.2, 0.03, 0.05), SHED_ROOF_B)
    # Clerestory windows along the long walls.
    for side in (-1, 1):
        x = side * (w / 2 + 0.04)
        for k in range(6):
            t.box((x, -d / 2 + 2.5 + k * 4, 3.4), (0.08, 2.4, 0.8), WINDOW)
    return t.finish(col)


def greenhouse(col, seed):
    """Arched plastic-film greenhouse, nominal 8 m x 30 m x 3.2 m."""
    t = Builder(f"greenhouse_{seed}", seed)
    w, d, h = 8.0, 30.0, 3.2
    seg = 10
    pts = []
    for i in range(seg + 1):
        a = math.pi * i / seg
        pts.append((math.cos(a) * w / 2, 0.9 + math.sin(a) * (h - 0.9)))
    # Film skin as one strip, slightly darker every bay for the lit ribs.
    for i in range(seg):
        (x0, z0), (x1, z1) = pts[i], pts[i + 1]
        t.quad_strip(
            [
                Vector((x0, -d / 2, z0)), Vector((x1, -d / 2, z1)),
                Vector((x0, d / 2, z0)), Vector((x1, d / 2, z1)),
            ],
            FILM,
        )
    for side in (-1, 1):
        t.quad_strip(
            [Vector((side * w / 2, -d / 2, 0)), Vector((side * w / 2, d / 2, 0)),
             Vector((side * w / 2, -d / 2, 0.9)), Vector((side * w / 2, d / 2, 0.9))],
            hex_linear("#cfd8d4"),
        )
    # Hoops every 1.5 m read as structure from close range.
    for k in range(int(d / 1.5) + 1):
        y = -d / 2 + k * 1.5
        for i in range(seg):
            (x0, z0), (x1, z1) = pts[i], pts[i + 1]
            t.cylinder((x0, y, z0 + 0.02), (x1, y, z1 + 0.02), 0.035, 0.035, STEEL, segments=3, cap=False)
    # End walls.
    for side in (-1, 1):
        y = side * d / 2
        ring = [t.bm.verts.new((x, y, z)) for x, z in pts]
        floor_l = t.bm.verts.new((-w / 2, y, 0))
        floor_r = t.bm.verts.new((w / 2, y, 0))
        face = t.bm.faces.new([floor_r] + ring + [floor_l] if side < 0 else [floor_l] + ring[::-1] + [floor_r])
        t._paint([face], hex_linear("#dbe3df"))
        t.box((0, y + side * 0.03, 1.0), (1.4, 0.06, 2.0), STEEL)
    return t.finish(col)


def utility_pole(col, seed):
    """Rural 10 kV concrete pole with crossarm; wires are drawn in Unity."""
    t = Builder(f"utility_pole_{seed}", seed)
    t.cylinder((0, 0, 0), (0, 0, 9.5), 0.17, 0.1, CONCRETE, segments=6)
    t.box((0, 0, 8.9), (2.2, 0.12, 0.12), STEEL)
    for x in (-0.95, 0.0, 0.95):
        t.cylinder((x, 0, 8.96), (x, 0, 9.3), 0.06, 0.04, INSULATOR, segments=5)
    t.cylinder((0, 0, 8.0), (0.9, 0, 8.85), 0.025, 0.025, STEEL, segments=3)
    t.cylinder((0, 0, 8.0), (-0.9, 0, 8.85), 0.025, 0.025, STEEL, segments=3)
    return t.finish(col)


def hay_bales(col, seed):
    """A small stack of round bales left at a field corner."""
    t = Builder(f"hay_bales_{seed}", seed)
    rng = t.rng
    spots = [(0, 0, 0.7), (1.55, 0.2, 0.7), (0.8, 0.1, 1.95)]
    for x, y, z in spots:
        yaw = rng.random() * 0.3
        a = Vector((x - math.cos(yaw) * 0.6, y - math.sin(yaw) * 0.6, z))
        b = Vector((x + math.cos(yaw) * 0.6, y + math.sin(yaw) * 0.6, z))
        verts = t.cylinder(a, b, 0.72, 0.72, HAY, segments=10)
        # Shade the end caps a little darker than the wrapped body.
        for v in verts:
            for f in v.link_faces:
                if abs(f.normal.dot((b - a).normalized())) > 0.9:
                    t._paint([f], HAY_END)
    return t.finish(col)


# ---------------------------------------------------------------- export

def export(obj, name):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    path = os.path.join(OUT, name + ".fbx")
    bpy.ops.export_scene.fbx(
        filepath=path,
        use_selection=True,
        object_types={"MESH"},
        apply_unit_scale=True,
        apply_scale_options="FBX_SCALE_UNITS",
        axis_forward="-Z",
        axis_up="Y",
        bake_space_transform=True,
        mesh_smooth_type="FACE",
        use_mesh_modifiers=True,
        colors_type="LINEAR",
        add_leaf_bones=False,
        bake_anim=False,
        path_mode="STRIP",
    )
    return path


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    col = bpy.data.collections.new("FarmArt")
    bpy.context.scene.collection.children.link(col)
    def lod(make, subdivisions):
        def run():
            FOLIAGE_DETAIL["subdivisions"] = subdivisions
            try:
                return make()
            finally:
                FOLIAGE_DETAIL["subdivisions"] = 2
        return run

    trees = [
        ("tree_poplar_0", lambda: tree_poplar(col, 0)),
        ("tree_poplar_1", lambda: tree_poplar(col, 1)),
        ("tree_willow_0", lambda: tree_willow(col, 0)),
        ("tree_broadleaf_0", lambda: tree_broadleaf(col, 0)),
        ("tree_broadleaf_1", lambda: tree_broadleaf(col, 1)),
    ]
    makers = trees + [(name + "_far", lod(make, 1)) for name, make in trees] + [
        ("house_rural", lambda: house_rural(col, 0)),
        ("shed_long", lambda: shed_long(col, 0)),
        ("greenhouse", lambda: greenhouse(col, 0)),
        ("utility_pole", lambda: utility_pole(col, 0)),
        ("hay_bales", lambda: hay_bales(col, 0)),
    ]
    lines = []
    for name, make in makers:
        obj = make()
        obj.name = name
        obj.data.name = name
        export(obj, name)
        dims = obj.dimensions
        lines.append(f"{name}: verts={len(obj.data.vertices)} tris={sum(len(p.vertices) - 2 for p in obj.data.polygons)} size={dims.x:.1f}x{dims.y:.1f}x{dims.z:.1f}")
    if "--preview" in sys.argv:
        render_preview(col)
    with open(LOG, "w", encoding="utf-8") as fh:
        fh.write("OK\n" + "\n".join(lines) + "\n")


def render_preview(col):
    """Contact sheet for review (ignored .cache); not part of the Unity build."""
    x = 0.0
    for obj in col.objects:
        width = obj.dimensions.x
        obj.location = (x + width / 2, 0, 0)
        x += width + 4
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    shading = scene.display.shading
    shading.light = "STUDIO"
    shading.color_type = "VERTEX"
    shading.show_shadows = True
    shading.show_cavity = True
    scene.render.resolution_x, scene.render.resolution_y = 2400, 700
    scene.view_settings.view_transform = "Standard"
    cam_data = bpy.data.cameras.new("preview")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = x + 4
    cam = bpy.data.objects.new("preview", cam_data)
    scene.collection.objects.link(cam)
    cam.location = (x / 2, -60, 12)
    cam.rotation_euler = (math.radians(84), 0, 0)
    scene.camera = cam
    scene.world = bpy.data.worlds.new("preview") if not scene.world else scene.world
    scene.render.filepath = os.path.join(ROOT, ".cache", "review", "farm-art-preview.png")
    bpy.ops.render.render(write_still=True)


try:
    main()
except Exception as exc:  # The Store launcher hides stdout, so record failures.
    import traceback

    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, "w", encoding="utf-8") as fh:
        fh.write("FAILED\n" + traceback.format_exc())
    sys.exit(1)
