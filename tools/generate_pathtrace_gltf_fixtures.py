from pathlib import Path
import struct,json,zlib,binascii,copy
root=Path('dev_docs/agents/fixtures/path_tracing')
def png(name,rgba):
 def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',binascii.crc32(t+b)&0xffffffff)
 data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',2,2,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(b'\0'+bytes(rgba[y*8:y*8+8]) for y in range(2))))+chunk(b'IEND',b'')
 (root/name).write_bytes(data);return data
base=png('base.png',[128,64,32,0, 32,128,64,255, 64,32,128,128, 255,255,255,255])
mr=png('mr.png',[255,128,64,255]*4)
normal=png('normal.png',[128,204,230,255]*4)
blob=bytearray();views=[];access=[]
def add(values,fmt,kind,count):
 while len(blob)%4:blob.append(0)
 offset=len(blob);raw=struct.pack('<'+fmt*len(values),*values);blob.extend(raw)
 views.append({'buffer':0,'byteOffset':offset,'byteLength':len(raw)})
 obj={'bufferView':len(views)-1,'componentType':5126 if fmt=='f' else 5123,'count':count,'type':kind}
 if kind=='VEC3' and not access: obj.update(min=[-2,-2,0],max=[2,2,0])
 access.append(obj);return len(access)-1
pos=add([-2,-2,0,2,-2,0,2,2,0,-2,2,0],'f','VEC3',4)
norm=add([0,0,1]*4,'f','VEC3',4)
uv=add([0,1,1,1,1,0,0,0],'f','VEC2',4)
color=add([.5,1,.25,1]*4,'f','VEC4',4)
idx=add([0,1,2,0,2,3],'H','SCALAR',6)
g={'asset':{'version':'2.0','generator':'Field path tracer contract fixture'},'scene':0,'scenes':[{'nodes':[0]}],'nodes':[{'mesh':0}],
 'meshes':[{'primitives':[{'attributes':{'POSITION':pos,'NORMAL':norm,'TEXCOORD_0':uv,'COLOR_0':color},'indices':idx,'material':0}]}],
 'materials':[{'name':'paint','pbrMetallicRoughness':{'baseColorFactor':[.8,.6,.4,.5],'baseColorTexture':{'index':0},'metallicFactor':.8,'roughnessFactor':.6,'metallicRoughnessTexture':{'index':1}},'emissiveFactor':[1,1,1],'emissiveTexture':{'index':0},'normalTexture':{'index':2},'doubleSided':True}],
 'images':[{'uri':'base.png'},{'uri':'mr.png'},{'uri':'normal.png'}],'samplers':[{'magFilter':9728,'minFilter':9728,'wrapS':33071,'wrapT':33071}],
 'textures':[{'source':i,'sampler':0} for i in range(3)],'buffers':[{'uri':'quad.bin','byteLength':len(blob)}],'bufferViews':views,'accessors':access}
(root/'quad.bin').write_bytes(blob)
(root/'quad.gltf').write_text(json.dumps(g,indent=2)+'\n')
# Same asset, embedded images and buffer in GLB.
b=copy.deepcopy(g);b['buffers'][0].pop('uri')
for i,data in enumerate([base,mr,normal]):
 while len(blob)%4:blob.append(0)
 b['bufferViews'].append({'buffer':0,'byteOffset':len(blob),'byteLength':len(data)})
 blob.extend(data);b['images'][i]={'bufferView':len(b['bufferViews'])-1,'mimeType':'image/png'}
b['buffers'][0]['byteLength']=len(blob)
j=json.dumps(b,separators=(',',':')).encode();j+=b' '*((-len(j))%4);blob.extend(b'\0'*((-len(blob))%4))
chunks=struct.pack('<II',len(j),0x4e4f534a)+j+struct.pack('<II',len(blob),0x004e4942)+blob
(root/'quad.glb').write_bytes(struct.pack('<III',0x46546c67,2,12+len(chunks))+chunks)
# Hierarchy including mirror and shear; default TRS decomposition would lose both.
h=copy.deepcopy(g);h['nodes']=[{'translation':[3,2,1],'children':[1]},{'mesh':0,'matrix':[-2,0,.5,0, .5,3,0,0, .4,.2,1,0, 1,0,0,1]}]
(root/'transforms.gltf').write_text(json.dumps(h,indent=2)+'\n')
# KHR_texture_transform should be baked before V is converted back to glTF convention.
t=copy.deepcopy(g);t['extensionsUsed']=['KHR_texture_transform'];t['materials'][0]['pbrMetallicRoughness']['baseColorTexture']['extensions']={'KHR_texture_transform':{'offset':[.25,.125],'scale':[.5,.75],'rotation':.3}}
(root/'uv-transform.gltf').write_text(json.dumps(t,indent=2)+'\n')
(root/'README.md').write_text('Original, programmatically authored glTF 2.0 fixtures for Field path-tracer contracts.\nNo external assets. quad.gltf and quad.glb encode the same textured quad; transforms.gltf adds hierarchy, reflection and shear; uv-transform.gltf adds KHR_texture_transform.\n')

u=copy.deepcopy(g);u['extensionsUsed']=['KHR_materials_clearcoat'];u['extensionsRequired']=['KHR_materials_clearcoat'];u['materials'][0]['extensions']={'KHR_materials_clearcoat':{'clearcoatFactor':1}}
(root/'unsupported-required.gltf').write_text(json.dumps(u,indent=2)+'\n')
