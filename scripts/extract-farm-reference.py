import argparse, pathlib, json
import UnityPy
from UnityPy.helpers.MeshHelper import MeshHandler
parser=argparse.ArgumentParser(description='Extract the supplied local Unity reference meshes and materials.')
parser.add_argument('--source',required=True,help='Decompressed UnityWebData file')
parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parent.parent/'private-materials'/'_reference-models')
args=parser.parse_args()
root=args.output;root.mkdir(parents=True,exist_ok=True)
env=UnityPy.load(args.source)
wanted={'tree--2--reference--optimalised':'tree','tracktor-main':'tractor','main-buiding--back':'barn','silo-primary--back':'silo','meteostation':'sensor'}
assets=[];done=set()
def rotate(v,q):
 x,y,z=v;u=(q.x,q.y,q.z);s=q.w
 dot=u[0]*x+u[1]*y+u[2]*z;us=sum(a*a for a in u)
 cross=(u[1]*z-u[2]*y,u[2]*x-u[0]*z,u[0]*y-u[1]*x)
 return [2*dot*u[i]+(s*s-us)*v[i]+2*s*cross[i] for i in range(3)]
for obj in env.objects:
 if obj.type.name!='MeshFilter':continue
 f=obj.read()
 try: mesh=f.m_Mesh.read()
 except Exception:continue
 if mesh.m_Name not in wanted or wanted[mesh.m_Name] in done:continue
 go=f.m_GameObject.read();components=[p.component.read() for p in go.m_Component]
 renderer=next((c for c in components if c.__class__.__name__=='MeshRenderer'),None)
 transform=next((c for c in components if c.__class__.__name__=='Transform'),None)
 if renderer is None or transform is None:continue
 # Capture contains untextured proxy instances before the final textured tractor.
 if wanted[mesh.m_Name]=='tractor':
  props=renderer.m_Materials[0].read_typetree()['m_SavedProperties']
  if not dict(props['m_TexEnvs']).get('_BaseMap',{}).get('m_Texture',{}).get('m_PathID'):continue
 h=MeshHandler(mesh);h.process()
 positions=[list(v[:3]) for v in h.m_Vertices]; normals=[list(v[:3]) for v in h.m_Normals]
 t=transform
 while t:
  scale=t.m_LocalScale;positions=[rotate([p[0]*scale.x,p[1]*scale.y,p[2]*scale.z],t.m_LocalRotation) for p in positions];normals=[rotate(n,t.m_LocalRotation) for n in normals]
  try:t=t.m_Father.read() if t.m_Father.path_id else None
  except Exception:t=None
 center=[(min(p[i] for p in positions)+max(p[i] for p in positions))/2 for i in range(3)];center[1]=min(p[1] for p in positions)
 positions=[[p[i]-center[i] for i in range(3)] for p in positions]
 materials=[]
 for pointer in renderer.m_Materials:
  m=pointer.read_typetree();props=m['m_SavedProperties'];colors=dict(props['m_Colors']);color=colors.get('_BaseColor',colors.get('_Color',dict(r=1,g=1,b=1,a=1)));texs=dict(props['m_TexEnvs']);tex=texs.get('_BaseMap',texs.get('_MainTex',{})).get('m_Texture',{})
  imageName=''
  if tex.get('m_PathID'):
   reader=pointer.deref(); from UnityPy.classes import PPtr
   tx=PPtr(m_FileID=tex['m_FileID'],m_PathID=tex['m_PathID'],assetsfile=reader.assets_file).read()
   imageName=f'{wanted[mesh.m_Name]}-{len(materials)}.png';tx.image.save(root/imageName)
  materials.append(dict(name=m['m_Name'],color=color,texture=imageName))
 uv=[{'x':p[0],'y':p[1]} for p in h.m_UV0] if h.m_UV0 else []
 assets.append(dict(name=wanted[mesh.m_Name],sourceMesh=mesh.m_Name,vertices=[dict(zip(['x','y','z'],p)) for p in positions],normals=[dict(zip(['x','y','z'],p)) for p in normals],uv=uv,colors=[dict(zip(['r','g','b','a'],[float(c)/255 if max(color)>1 else float(c) for c in color])) for color in (h.m_Colors or [])],submeshes=[dict(indices=[i for tri in tris for i in tri]) for tris in h.get_triangles()],materials=materials))
 done.add(wanted[mesh.m_Name]);print(wanted[mesh.m_Name],len(positions),'vertices',len(materials),'materials')
(root/'manifest.json').write_text(json.dumps(dict(assets=assets)),encoding='utf8')
(root/'SOURCE.md').write_text('Source: https://virtual.cleverfarm.ag/ — supplied local reference capture 2026-10-13. Runtime meshes and material textures recovered for the requested local preview. Copyright remains with original owners. No license grant was included in the reference capture. Not added to Git.\n',encoding='utf8')
