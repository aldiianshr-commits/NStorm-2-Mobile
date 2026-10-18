package com.nstorm2.engine.xfbin;

import com.nstorm2.engine.io.BinaryReader;
import com.nstorm2.engine.io.FormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed views of the nuccChunk payloads needed to assemble a character/stage model.
 * All indices stored in chunk data are page-relative and resolved through {@link Xfbin.Page}.
 */
public final class NuccChunks {
    private NuccChunks() {}

    public static final String CLUMP = "nuccChunkClump";
    public static final String COORD = "nuccChunkCoord";
    public static final String MODEL = "nuccChunkModel";
    public static final String MATERIAL = "nuccChunkMaterial";
    public static final String TEXTURE = "nuccChunkTexture";

    /** Bone / transform node. Position in centimetres, rotation as Euler degrees (applied Z, then Y, then X). */
    public static final class Coord {
        public final String name;
        public final float[] position = new float[3];
        public final float[] rotation = new float[3];
        public final float[] scale = new float[3];
        public int parent = -1;

        Coord(String name) {
            this.name = name;
        }
    }

    public static Coord parseCoord(Xfbin.Chunk c) {
        BinaryReader r = c.data.slice(0, c.data.length());
        Coord k = new Coord(c.name);
        for (int i = 0; i < 3; i++) k.position[i] = r.f32();
        for (int i = 0; i < 3; i++) k.rotation[i] = r.f32();
        for (int i = 0; i < 3; i++) k.scale[i] = r.f32();
        return k;
    }

    /** A clump: the skeleton plus the list of models that make up an object. */
    public static final class Clump {
        public final String name;
        public final List<Coord> coords = new ArrayList<>();
        public final List<Xfbin.Chunk> models = new ArrayList<>();

        Clump(String name) {
            this.name = name;
        }
    }

    public static Clump parseClump(Xfbin xfbin, Xfbin.Chunk c) {
        Xfbin.Page page = xfbin.page(c);
        if (page == null) throw new FormatException("clump without page");
        BinaryReader r = c.data.slice(0, c.data.length());
        Clump clump = new Clump(c.name);
        r.u32();
        int coordCount = r.u16();
        r.u8();
        r.u8();
        int[] parents = new int[coordCount];
        for (int i = 0; i < coordCount; i++) parents[i] = r.s16();
        for (int i = 0; i < coordCount; i++) {
            Xfbin.Chunk cc = page.resolve((int) r.u32());
            Coord k = cc != null && COORD.equals(cc.type) ? parseCoord(cc) : new Coord("coord" + i);
            k.parent = parents[i] >= 0 && parents[i] < coordCount ? parents[i] : -1;
            clump.coords.add(k);
        }
        java.util.LinkedHashSet<Xfbin.Chunk> models = new java.util.LinkedHashSet<>();
        int modelCount = r.u16();
        r.u8();
        r.u8();
        r.u32();
        for (int i = 0; i < modelCount; i++) addModel(models, page.resolve((int) r.u32()));
        // model groups (alternate parts, e.g. damaged costume / effects)
        while (r.remaining() >= 2) {
            int count = r.s16();
            if (count == -1 || r.remaining() < 6) break;
            r.u8();
            r.u8();
            r.s32();
            for (int i = 0; i < count && r.remaining() >= 4; i++) {
                int idx = r.s32();
                if (idx >= 0) addModel(models, page.resolve(idx));
            }
        }
        clump.models.addAll(models);
        return clump;
    }

    private static void addModel(java.util.Set<Xfbin.Chunk> out, Xfbin.Chunk c) {
        if (c != null && MODEL.equals(c.type)) out.add(c);
    }

    /** nuccChunkModel: an NDP3 (NUD) mesh with its bone and material links. */
    public static final class Model {
        public final String name;
        public final int meshBone;              // index into the clump's coords, or -1
        public final BinaryReader nud;          // NDP3 data
        public final List<Material> materials = new ArrayList<>();

        Model(String name, int meshBone, BinaryReader nud) {
            this.name = name;
            this.meshBone = meshBone;
            this.nud = nud;
        }
    }

    private static final byte[] NDP3 = {'N', 'D', 'P', '3'};

    public static Model parseModel(Xfbin xfbin, Xfbin.Chunk c) {
        Xfbin.Page page = xfbin.page(c);
        BinaryReader r = c.data.slice(0, c.data.length());
        r.u16();
        r.u16();                 // rigging flag
        r.skip(4);               // material flags
        r.u32();
        r.u32();                 // clump index
        r.u32();                 // hit index
        int nudStart = BinaryReader.indexOf(r.array(), r.arrayOffset(), r.arrayOffset() + r.length(), NDP3) - r.arrayOffset();
        if (nudStart < 0) throw new FormatException("model " + c.name + " has no NDP3 data");
        BinaryReader probe = r.slice(nudStart + 4, 4);
        int nudSize = (int) probe.u32();
        int meshBone = (int) r.u32();
        if (meshBone == nudSize) meshBone = -1;
        if (nudSize <= 0 || nudStart + nudSize > r.length()) throw new FormatException("model " + c.name + " NUD size out of range");
        Model m = new Model(c.name, meshBone, r.slice(nudStart, nudSize));
        r.seek(nudStart + nudSize);
        if (r.remaining() >= 2 && page != null) {
            int materialCount = r.u16();
            for (int i = 0; i < materialCount && r.remaining() >= 4; i++) {
                Xfbin.Chunk mc = page.resolve((int) r.u32());
                m.materials.add(mc != null && MATERIAL.equals(mc.type) ? parseMaterial(xfbin, mc) : null);
            }
        }
        return m;
    }

    /** nuccChunkMaterial: shader constants + groups of texture chunks. */
    public static final class Material {
        public final String name;
        public final List<List<Xfbin.Chunk>> textureGroups = new ArrayList<>();

        Material(String name) {
            this.name = name;
        }

        /** First texture of the first group = the diffuse map in CC2 materials. */
        public Xfbin.Chunk diffuse() {
            for (List<Xfbin.Chunk> g : textureGroups) {
                for (Xfbin.Chunk t : g) if (t != null) return t;
            }
            return null;
        }
    }

    static int floatCount(int format) {
        int n = 0;
        if ((format & 0x40) != 0) n += 1;
        if ((format & 0x20) != 0) n += 1;
        if ((format & 0x10) != 0) n += 2;
        if ((format & 0x08) != 0) n += 4;
        if ((format & 0x04) != 0) n += 4;
        if ((format & 0x02) != 0) n += 4;
        if ((format & 0x01) != 0) n += 4;
        return n;
    }

    public static Material parseMaterial(Xfbin xfbin, Xfbin.Chunk c) {
        Xfbin.Page page = xfbin.page(c);
        BinaryReader r = c.data.slice(0, c.data.length());
        Material m = new Material(c.name);
        int groupCount = r.u16();
        r.u8();
        r.u8();
        r.f32();
        r.skip(3);
        int format = r.u8();
        r.skip(4 * floatCount(format));
        for (int g = 0; g < groupCount && r.remaining() >= 8; g++) {
            int count = r.s16();
            r.u16();
            r.s32();
            List<Xfbin.Chunk> group = new ArrayList<>();
            for (int i = 0; i < count && r.remaining() >= 4; i++) {
                Xfbin.Chunk t = page == null ? null : page.resolve((int) r.u32());
                group.add(t != null && TEXTURE.equals(t.type) ? t : null);
            }
            m.textureGroups.add(group);
        }
        return m;
    }

    /** nuccChunkTexture: u16, u16 width, u16 height, u16, u32 nutSize, NTP3 data. */
    public static BinaryReader textureNut(Xfbin.Chunk c) {
        BinaryReader r = c.data.slice(0, c.data.length());
        r.skip(8);
        int size = (int) r.u32();
        if (size <= 0 || size > r.remaining()) throw new FormatException("texture " + c.name + " NUT size out of range");
        return r.slice(12, size);
    }
}
