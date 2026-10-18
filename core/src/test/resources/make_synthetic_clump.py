"""Builds a synthetic XFBIN with xfbin_lib's own writer, to cross-check the Java parser."""
import struct
import sys

sys.path.insert(0, '/home/claude/research')
from xfbin_lib.xfbin import *  # noqa
from xfbin_lib.xfbin.structure.nucc import *  # noqa
from xfbin_lib.xfbin.structure.nud import *  # noqa
from xfbin_lib.xfbin.structure.br.br_nud import NudVertexType, NudBoneType, NudUvType


def make_nut():
    # one 8x4 DXT1 texture: left block solid red, right block solid black (c0 green > c1 black, idx 1)
    blk_red = struct.pack('<HHI', 0xF800, 0x001F, 0x00000000)
    blk_black = struct.pack('<HHI', 0x07E0, 0x0000, 0x55555555)
    data = blk_red + blk_black
    header_size = 0x50
    tex = struct.pack('>IIIHH', header_size + len(data), 0, len(data), header_size, 0)
    tex += struct.pack('>BBBB', 0, 1, 0, 0)          # mip count 1, format 0 (DXT1)
    tex += struct.pack('>HH', 8, 4)
    tex += struct.pack('>II', 0, 0)                  # type, caps2
    tex += struct.pack('>IIII', header_size, 0, 0, 0)
    tex += b'eXt\x00' + struct.pack('>III', 0x20, 0x10, 0)
    tex += b'GIDX' + struct.pack('>III', 0x10, 0x1234, 0)
    assert len(tex) == header_size, len(tex)
    return b'NTP3' + struct.pack('>HH', 0x0200, 1) + b'\x00' * 8 + tex + data


def vertex(pos, uv, bone):
    v = NudVertex()
    v.position = pos
    v.normal = (0.0, 0.0, 1.0)
    v.bitangent = None
    v.tangent = None
    v.color = (255, 128, 64, 255)
    v.uv = [uv]
    v.bone_ids = (bone, 0, 0, 0)
    v.bone_weights = (1.0, 0.0, 0.0, 0.0)
    return v


def make_material():
    m = NudMaterial()
    m.flags = 0
    m.sourceFactor = 0
    m.destFactor = 0
    m.alphaTest = 0
    m.alphaFunction = 0
    m.refAlpha = 0
    m.cullMode = 0x405
    m.unk1 = 0.0
    m.unk2 = 0.0
    m.zBufferOffset = 0
    t = NudMaterialTexture()
    t.unk0 = -1
    t.mapMode = 0
    t.wrapModeS = t.wrapModeT = 1
    t.minFilter = t.magFilter = 1
    t.mipDetail = 1
    t.unk1 = 0
    t.unk2 = 0
    m.textures = [t]
    p = NudMaterialProperty()
    p.name = 'NU_materialHash'
    p.values = [1.0, 0.0, 0.0, 0.0]
    m.properties = [p]
    return m


def main(out_path):
    texture = NuccChunkTexture('c/test/tex.nut', 'test_tex')
    texture.file_data = make_nut()
    texture.nut = True
    texture.data = b''

    material = NuccChunkMaterial('c/test/test.max', 'test_mat')
    material.field02 = 0xFE
    material.field04 = 0.0
    material.format = 0
    material.floats = tuple()
    group = MaterialTextureGroup()
    group.unk = 0
    group.texture_chunks = [texture]
    material.texture_groups = [group]

    root = NuccChunkCoord('c/test/test.max', 'root')
    root.node = CoordNode(root)
    root.node.position = (0.0, 100.0, 0.0)
    root.node.rotation = (0.0, 0.0, 0.0)
    root.node.scale = (1.0, 1.0, 1.0)
    child = NuccChunkCoord('c/test/test.max', 'child')
    child.node = CoordNode(child)
    child.node.position = (10.0, 0.0, 0.0)
    child.node.rotation = (0.0, 90.0, 0.0)
    child.node.scale = (1.0, 1.0, 1.0)
    child.node.parent = root.node
    root.node.children.append(child.node)

    mesh = NudMesh()
    mesh.vertices = [vertex((0.0, 0.0, 0.0), (0.0, 0.0), 1), vertex((1.0, 0.0, 0.0), (1.0, 0.0), 1),
                     vertex((0.0, 1.0, 0.0), (0.0, 1.0), 1), vertex((1.0, 1.0, 0.0), (1.0, 1.0), 1)]
    mesh.faces = [(0, 1, 2), (2, 1, 3)]
    mesh.materials = [make_material()]
    mesh.vertex_type = 1
    mesh.bone_type = 0x20
    mesh.uv_type = 2
    mesh.face_flag = 0
    mgroup = NudMeshGroup()
    mgroup.name = 'body'
    mgroup.bone_flags = 0
    mgroup.bounding_sphere = (0.0,) * 8
    mgroup.meshes = [mesh]
    nud = Nud()
    nud.name = 'test_model'
    nud.bounding_sphere = (0.0, 0.0, 0.0, 10.0)
    nud.mesh_groups = [mgroup]

    clump = NuccChunkClump('c/test/test.max', 'test_clump')
    model = NuccChunkModel('c/test/test.max', 'test_model')
    model.rigging_flag = RiggingFlag(0x2)
    model.material_flags = (0, 0, 8, 3)
    model.flag1_floats = tuple()
    model.clump_chunk = clump
    model.hit_chunk = NuccChunkNull()
    model.coord_index = 1
    model.coord_chunk = child
    model.nud = nud
    model.material_chunks = [material]

    clump.field00 = 0
    clump.coord_flag0 = 0
    clump.coord_flag1 = 0
    clump.model_flag0 = 0
    clump.model_flag1 = 0
    clump.coord_chunks = [root, child]
    clump.model_chunks = [model]
    clump.model_groups = []
    clump.root_nodes = [root.node]

    for c in (texture, material, root, child, model, clump):
        c.has_props = True
        c.has_data = True
    xf = Xfbin()
    xf.add_clump_page(clump)
    buf = write_xfbin(xf)
    with open(out_path, 'wb') as f:
        f.write(buf)

    # sanity: read back with xfbin_lib itself
    back = read_xfbin(bytearray(buf))
    for p in back.pages:
        print('page', [(NuccChunk.get_nucc_str_from_type(type(c)), c.name) for c in p.chunks])
    m = back.get_chunks_by_type('nuccChunkModel')[0]
    print('faces', m.nud.mesh_groups[0].meshes[0].faces)


if __name__ == '__main__':
    main(sys.argv[1])
