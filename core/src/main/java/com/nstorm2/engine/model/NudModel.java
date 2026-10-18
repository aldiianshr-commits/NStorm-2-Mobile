package com.nstorm2.engine.model;

import com.nstorm2.engine.io.BinaryReader;
import com.nstorm2.engine.io.FormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * NDP3 ("NUD") mesh as embedded in nuccChunkModel. Big-endian.
 * Layout follows xfbin_lib's br_nud.py (MIT) / Smash Forge.
 *
 * Header: "NDP3", u32 size, u16 version, u16 groupCount, u16 boneStart, u16 boneEnd,
 * u32 polyStart(+0x30), u32 polySize, u32 vertSize, u32 vertAddSize, 4 floats sphere,
 * then groupCount x 0x30 group headers and 0x30-byte mesh headers.
 */
public final class NudModel {

    public static final class Mesh {
        public final String group;
        public final int vertexCount;
        public final float[] positions;   // xyz
        public final float[] normals;     // xyz (may be zeros)
        public final float[] uvs;         // first UV channel, uv
        public final int[] colors;        // ARGB per vertex or null
        public final int[] boneIds;       // 4 per vertex or null
        public final float[] boneWeights; // 4 per vertex or null
        public final int[] triangles;     // indices, 3 per triangle

        Mesh(String group, int vertexCount, float[] positions, float[] normals, float[] uvs, int[] colors,
             int[] boneIds, float[] boneWeights, int[] triangles) {
            this.group = group;
            this.vertexCount = vertexCount;
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.colors = colors;
            this.boneIds = boneIds;
            this.boneWeights = boneWeights;
            this.triangles = triangles;
        }
    }

    public static final class Group {
        public final String name;
        public final List<Mesh> meshes = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }
    }

    public final List<Group> groups = new ArrayList<>();
    public final int boneStart;
    public final int boneEnd;

    private NudModel(int boneStart, int boneEnd) {
        this.boneStart = boneStart;
        this.boneEnd = boneEnd;
    }

    public int meshCount() {
        int n = 0;
        for (Group g : groups) n += g.meshes.size();
        return n;
    }

    public static NudModel parse(BinaryReader nud) {
        BinaryReader r = nud.slice(0, nud.length());
        if (!"NDP3".equals(r.fixedString(4))) throw new FormatException("missing NDP3 magic");
        r.u32();
        r.u16();
        int groupCount = r.u16();
        int boneStart = r.u16();
        int boneEnd = r.u16();
        int polyStart = (int) r.u32() + 0x30;
        int polySize = (int) r.u32();
        int vertStart = polyStart + polySize;
        int vertSize = (int) r.u32();
        int vertAddStart = vertStart + vertSize;
        int vertAddSize = (int) r.u32();
        int nameStart = vertAddStart + vertAddSize;
        r.skip(16);

        NudModel model = new NudModel(boneStart, boneEnd);
        int[] meshCounts = new int[groupCount];
        for (int g = 0; g < groupCount; g++) {
            r.skip(32);
            int nameOff = (int) r.u32();
            String name;
            try {
                name = r.cStringAt(nameStart + nameOff, BinaryReader.SHIFT_JIS);
            } catch (FormatException e) {
                name = "group" + g;
            }
            r.u16();
            r.u16();
            r.u16();
            meshCounts[g] = r.u16();
            r.u32();
            model.groups.add(new Group(name));
        }
        for (int g = 0; g < groupCount; g++) {
            Group group = model.groups.get(g);
            for (int m = 0; m < meshCounts[g]; m++) {
                int meshHeader = r.position();
                group.meshes.add(readMesh(r, group.name, polyStart, vertStart, vertAddStart));
                r.seek(meshHeader + 0x30);
            }
        }
        return model;
    }

    private static Mesh readMesh(BinaryReader r, String groupName, int polyBase, int vertBase, int vertAddBase) {
        int polyStart = (int) r.u32() + polyBase;
        int vertStart = (int) r.u32() + vertBase;
        int vertAddStart = (int) r.u32() + vertAddBase;
        int vertexCount = r.u16();
        int vertexSize = r.u8();
        int uvSize = r.u8();
        r.skip(16); // material offsets (texture binding comes from nuccChunkMaterial)
        int faceCount = r.u16();
        int faceSize = r.u8();
        r.u8();

        int boneType = vertexSize & 0xF0;
        int vertexType = vertexSize & 0x0F;
        int uvCount = uvSize >> 4;
        int uvType = uvSize & 0x0F;

        float[] pos = new float[vertexCount * 3];
        float[] nrm = new float[vertexCount * 3];
        float[] uv = new float[vertexCount * 2];
        int[] col = null;
        int[] bid = boneType != 0 ? new int[vertexCount * 4] : null;
        float[] bw = boneType != 0 ? new float[vertexCount * 4] : null;

        BinaryReader v = r.slice(0, r.length());
        if (boneType > 0) {
            // colours + UVs live in the vertex clump, positions/weights in the "add" clump
            v.seek(vertStart);
            if (uvType == 2 || uvType == 4) col = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                if (uvType == 2) {
                    col[i] = rgba(v.u8(), v.u8(), v.u8(), v.u8());
                } else if (uvType == 4) {
                    col[i] = rgba(f2b(v.f16()), f2b(v.f16()), f2b(v.f16()), f2b(v.f16()));
                }
                for (int u = 0; u < uvCount; u++) {
                    float a = v.f16(), b = v.f16();
                    if (u == 0) {
                        uv[i * 2] = a;
                        uv[i * 2 + 1] = b;
                    }
                }
            }
            v.seek(vertAddStart);
        } else {
            v.seek(vertStart);
        }

        for (int i = 0; i < vertexCount; i++) {
            pos[i * 3] = v.f32();
            pos[i * 3 + 1] = v.f32();
            pos[i * 3 + 2] = v.f32();
            switch (vertexType) {
                case 0:
                    v.f32();
                    break;
                case 1:
                    v.f32();
                    readVec3f(v, nrm, i);
                    v.f32();
                    break;
                case 2:
                    readVec3f(v, nrm, i);
                    v.skip(4 + 36);
                    break;
                case 3:
                    v.f32();
                    readVec3f(v, nrm, i);
                    v.f32();
                    v.skip(32);
                    break;
                case 6:
                    readVec3h(v, nrm, i);
                    v.f16();
                    break;
                case 7:
                    readVec3h(v, nrm, i);
                    v.f16();
                    v.skip(16);
                    break;
                default:
                    throw new FormatException("unsupported NUD vertex type " + vertexType);
            }
            if (boneType == 0) {
                if (uvSize >= 18) {
                    if (col == null) col = new int[vertexCount];
                    col[i] = rgba(v.u8(), v.u8(), v.u8(), v.u8());
                }
                for (int u = 0; u < uvCount; u++) {
                    float a = v.f16(), b = v.f16();
                    if (u == 0) {
                        uv[i * 2] = a;
                        uv[i * 2 + 1] = b;
                    }
                }
            } else if (boneType == 0x10) {
                for (int k = 0; k < 4; k++) bid[i * 4 + k] = (int) v.u32();
                for (int k = 0; k < 4; k++) bw[i * 4 + k] = v.f32();
            } else if (boneType == 0x20) {
                for (int k = 0; k < 4; k++) bid[i * 4 + k] = v.u16();
                for (int k = 0; k < 4; k++) bw[i * 4 + k] = v.f16();
            } else if (boneType == 0x40) {
                for (int k = 0; k < 4; k++) bid[i * 4 + k] = v.u8();
                for (int k = 0; k < 4; k++) bw[i * 4 + k] = v.u8() / 255f;
            } else {
                throw new FormatException("unsupported NUD bone type 0x" + Integer.toHexString(boneType));
            }
        }

        v.seek(polyStart);
        int[] idx = new int[faceCount];
        for (int i = 0; i < faceCount; i++) idx[i] = v.s16();
        int[] tris = (faceSize & 0x40) != 0 ? triangleList(idx, vertexCount) : stripToTriangles(idx, vertexCount);
        return new Mesh(groupName, vertexCount, pos, nrm, uv, col, bid, bw, tris);
    }

    private static void readVec3f(BinaryReader v, float[] out, int i) {
        out[i * 3] = v.f32();
        out[i * 3 + 1] = v.f32();
        out[i * 3 + 2] = v.f32();
    }

    private static void readVec3h(BinaryReader v, float[] out, int i) {
        out[i * 3] = v.f16();
        out[i * 3 + 1] = v.f16();
        out[i * 3 + 2] = v.f16();
    }

    private static int f2b(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255)));
    }

    private static int rgba(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    static int[] triangleList(int[] idx, int vertexCount) {
        int n = idx.length / 3 * 3;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i + 2 < idx.length; i += 3) {
            if (valid(idx[i], vertexCount) && valid(idx[i + 1], vertexCount) && valid(idx[i + 2], vertexCount)) {
                out[k++] = idx[i];
                out[k++] = idx[i + 1];
                out[k++] = idx[i + 2];
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    /** Triangle strips separated by -1 (0xFFFF) restart markers; winding alternates per triangle. */
    static int[] stripToTriangles(int[] idx, int vertexCount) {
        int[] out = new int[Math.max(0, idx.length * 3)];
        int k = 0;
        int i = 0;
        while (i + 2 < idx.length) {
            int f1 = idx[i], f2 = idx[i + 1];
            int dir = 1;
            int j = i + 2;
            while (j < idx.length && idx[j] != -1) {
                int f3 = idx[j];
                dir = -dir;
                if (f1 != f2 && f2 != f3 && f1 != f3 && valid(f1, vertexCount) && valid(f2, vertexCount) && valid(f3, vertexCount)) {
                    if (dir > 0) {
                        out[k++] = f3; out[k++] = f2; out[k++] = f1;
                    } else {
                        out[k++] = f2; out[k++] = f3; out[k++] = f1;
                    }
                }
                f1 = f2;
                f2 = f3;
                j++;
            }
            i = j + 1; // skip the restart marker
        }
        return java.util.Arrays.copyOf(out, k);
    }

    private static boolean valid(int v, int count) {
        return v >= 0 && v < count;
    }
}
