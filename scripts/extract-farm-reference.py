import argparse, pathlib, json
import UnityPy
from UnityPy.helpers.MeshHelper import MeshHandler
from UnityPy.classes import PPtr
parser=argparse.ArgumentParser(description='Extract the supplied local Unity reference meshes and materials.')
parser.add_argument('--source',required=True,help='Decompressed UnityWebData file')
parser.add_argument('--output',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parent.parent/'private-materials'/'_reference-models')
args=parser.parse_args()
root=args.output;root.mkdir(parents=True,exist_ok=True)
env=UnityPy.load(args.source)
wanted={'tree--2--reference--optimalised':'tree','tracktor-main':'tractor','main-buiding--back':'barn','silo-primary--back':'silo','meteostation':'sensor'}
# Parts the capture stores as sibling objects of the main mesh. Without them the tractor has no wheels
# and the barn and silo lose their front halves. Sibling UI overlays and the parent's own ground meshes are skipped.
companions={'tractor':('wheel__',),'barn':('main-buiding--opening',),'silo':('silo-primary--opening',)}
assets=[];done=set()
def rotate(v,q):
 x,y,z=v;u=(q.x,q.y,q.z);s=q.w
 dot=u[0]*x+u[1]*y+u[2]*z;us=sum(a*a for a in u)
 cross=(u[1]*z-u[2]*y,u[2]*x-u[0]*z,u[0]*y-u[1]*x)
 return [2*dot*u[i]+(s*s-us)*v[i]+2*s*cross[i] for i in range(3)]
def component(go,kind):
 # Check the type before parsing: script components in the capture have no readable type tree.
 return next((p.component.read() for p in go.m_Component if p.component.deref().type.name==kind),None)
def part(filter_,transform,local):
 # Vertices in the parent's space when `local` (sibling layout kept), otherwise in the object's own space.
 mesh=filter_.m_Mesh.read();h=MeshHandler(mesh);h.process()
 s,q,o=transform.m_LocalScale,transform.m_LocalRotation,transform.m_LocalPosition
 positions=[list(v[:3]) for v in h.m_Vertices];normals=[list(v[:3]) for v in h.m_Normals]
 if local:
  positions=[[a+b for a,b in zip(rotate([p[0]*s.x,p[1]*s.y,p[2]*s.z],q),(o.x,o.y,o.z))] for p in positions];normals=[rotate(n,q) for n in normals]
 colors=[[float(c)/255 if max(color)>1 else float(c) for c in color] for color in (h.m_Colors or [])]
 uv=[[p[0],p[1]] for p in h.m_UV0] if h.m_UV0 else [[0,0]]*len(positions)
 return dict(positions=positions,normals=normals,uv=uv,colors=colors,triangles=h.get_triangles(),renderer=component(transform.m_GameObject.read(),'MeshRenderer'),name=mesh.m_Name)
def surface(pointer,asset,index):
 m=pointer.read_typetree();props=m['m_SavedProperties'];colors=dict(props['m_Colors']);color=colors.get('_BaseColor',colors.get('_Color',dict(r=1,g=1,b=1,a=1)));texs=dict(props['m_TexEnvs']);tex=texs.get('_BaseMap',texs.get('_MainTex',{})).get('m_Texture',{})
 imageName=''
 if tex.get('m_PathID'):
  reader=pointer.deref()
  tx=PPtr(m_FileID=tex['m_FileID'],m_PathID=tex['m_PathID'],assetsfile=reader.assets_file).read()
  imageName=f'{asset}-{index}.png';tx.image.save(root/imageName)
 return dict(name=m['m_Name'],color=color,texture=imageName)
for obj in env.objects:
 if obj.type.name!='MeshFilter':continue
 f=obj.read()
 try: mesh=f.m_Mesh.read()
 except Exception:continue
 if mesh.m_Name not in wanted or wanted[mesh.m_Name] in done:continue
 name=wanted[mesh.m_Name]
 go=f.m_GameObject.read();renderer=component(go,'MeshRenderer');transform=component(go,'Transform')
 if renderer is None or transform is None:continue
 # Capture contains untextured proxy instances before the final textured tractor.
 if name=='tractor':
  props=renderer.m_Materials[0].read_typetree()['m_SavedProperties']
  if not dict(props['m_TexEnvs']).get('_BaseMap',{}).get('m_Texture',{}).get('m_PathID'):continue
 parent=transform.m_Father.read() if transform.m_Father.path_id else None
 prefixes=companions.get(name,()) if parent else ()
 parts=[part(f,transform,bool(prefixes))]
 for child in (parent.m_Children if prefixes else []):
  sibling=child.read();sgo=sibling.m_GameObject.read();sf=component(sgo,'MeshFilter')
  if sibling.m_GameObject.path_id==f.m_GameObject.path_id or sf is None or not sgo.m_Name.startswith(prefixes):continue
  parts.append(part(sf,sibling,True))
 # Merge parts; a part whose material name already exists joins that submesh so shared atlases stay one draw.
 positions,normals,uv,colors,submeshes,materials,names=[],[],[],[],[],[],[]
 hasColors=any(p['colors'] for p in parts)
 for p in parts:
  base=len(positions);positions+=p['positions'];normals+=p['normals'];uv+=p['uv']
  if hasColors:colors+=p['colors'] or [[1,1,1,1]]*len(p['positions'])
  for pointer,tris in zip(p['renderer'].m_Materials,p['triangles']):
   key=pointer.read_typetree()['m_Name']
   if key not in names:names.append(key);materials.append(surface(pointer,name,len(materials)));submeshes.append([])
   submeshes[names.index(key)]+=[base+i for tri in tris for i in tri]
 t=parent if prefixes else transform
 while t:
  scale=t.m_LocalScale;positions=[rotate([p[0]*scale.x,p[1]*scale.y,p[2]*scale.z],t.m_LocalRotation) for p in positions];normals=[rotate(n,t.m_LocalRotation) for n in normals]
  try:t=t.m_Father.read() if t.m_Father.path_id else None
  except Exception:t=None
 center=[(min(p[i] for p in positions)+max(p[i] for p in positions))/2 for i in range(3)];center[1]=min(p[1] for p in positions)
 positions=[[p[i]-center[i] for i in range(3)] for p in positions]
 assets.append(dict(name=name,sourceMesh=mesh.m_Name,parts=[p['name'] for p in parts],vertices=[dict(zip(['x','y','z'],p)) for p in positions],normals=[dict(zip(['x','y','z'],p)) for p in normals],uv=[{'x':p[0],'y':p[1]} for p in uv],colors=[dict(zip(['r','g','b','a'],c)) for c in colors],submeshes=[dict(indices=s) for s in submeshes],materials=materials))
 done.add(name);print(name,len(positions),'vertices',len(materials),'materials',' + '.join(p['name'] for p in parts))
(root/'manifest.json').write_text(json.dumps(dict(assets=assets)),encoding='utf8')
(root/'SOURCE.md').write_text('Source: https://virtual.cleverfarm.ag/ — supplied local reference capture 2026-10-13. Runtime meshes and material textures recovered for the requested local preview. Copyright remains with original owners. No license grant was included in the reference capture. Not added to Git.\n',encoding='utf8')
