package com.nstorm2.engine.scene;

import com.nstorm2.engine.io.FormatException;
import com.nstorm2.engine.model.NudModel;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renderable, bind-pose model assembled from an XFBIN clump: bone hierarchy ->
 * world matrices, every NUD mesh transformed by its mesh bone, and the diffuse
 * texture chunk of the matching nuccChunkMaterial (mesh i of a group uses
 * material i of its model, as in cc2_xfbin_blender).
 */
public final class SceneModel {

    public static final class Part {
        public final String name;
        /** Interleaved per vertex: px py pz nx ny nz u v. */
        public final float[] vertices;
        public final int[] indices;
        /** Diffuse texture chunk or null. */
        public final Xfbin.Chunk texture;
        public final String material;

        Part(String name, float[] vertices, int[] indices, Xfbin.Chunk texture, String material) {
            this.name = name;
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.material = material;
        }

        public int vertexCount() { return vertices.length / STRIDE; }
        public int triangleCount() { return indices.length / 3; }
    }

    public static final int STRIDE = 8;

    public final String name;
    public final List<Part> parts;
    public final List<NuccChunks.Coord> bones;
    public final float[][] boneWorld;
    public final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
    public final float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
    public final List<String> warnings = new ArrayList<>();

    private SceneModel(String name, List<Part> parts, List<NuccChunks.Coord> bones, float[][] boneWorld) {
        this.name = name;
        this.parts = parts;
        this.bones = bones;
        this.boneWorld = boneWorld;
    }

    public int triangleCount() {
        int n = 0;
        for (Part p : parts) n += p.triangleCount();
        return n;
    }

    public float radius() {
        if (parts.isEmpty()) return 1;
        float dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2];
        return Math.max(1e-3f, 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    public float[] center() {
        return new float[]{(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
    }

    /** Names of the clumps in a file (one model per clump). */
    public static List<Xfbin.Chunk> clumps(Xfbin x) {
        return x.chunksOfType(NuccChunks.CLUMP);
    }

    /** Builds the model of one clump; when the file has no clump, all loose models are used. */
    public static SceneModel build(Xfbin x, Xfbin.Chunk clumpChunk) {
        List<NuccChunks.Coord> bones;
        List<Xfbin.Chunk> modelChunks;
        String name;
        List<String> warnings = new ArrayList<>();
        if (clumpChunk != null) {
            NuccChunks.Clump clump = NuccChunks.parseClump(x, clumpChunk);
            bones = clump.coords;
            modelChunks = clump.models;
            name = clump.name;
        } else {
            bones = Collections.emptyList();
            modelChunks = x.chunksOfType(NuccChunks.MODEL);
            name = "models";
        }

        float[][] world = new float[bones.size()][];
        for (int i = 0; i < bones.size(); i++) world[i] = boneMatrix(bones, world, i, 0);

        List<Part> parts = new ArrayList<>();
        for (Xfbin.Chunk mc : modelChunks) {
            NuccChunks.Model model;
            NudModel nud;
            try {
                model = NuccChunks.parseModel(x, mc);
                nud = NudModel.parse(model.nud);
            } catch (FormatException e) {
                warnings.add(mc.name + ": " + e.getMessage());
                continue;
            }
            float[] m = model.meshBone >= 0 && model.meshBone < world.length ? world[model.meshBone] : Mat4.identity();
            for (NudModel.Group g : nud.groups) {
                for (int i = 0; i < g.meshes.size(); i++) {
                    NudModel.Mesh mesh = g.meshes.get(i);
                    if (mesh.triangles.length == 0) continue;
                    NuccChunks.Material mat = i < model.materials.size() ? model.materials.get(i) : null;
                    Xfbin.Chunk tex = mat != null ? mat.diffuse() : null;
                    parts.add(new Part(model.name + "/" + g.name + "#" + i, interleave(mesh, m), mesh.triangles, tex,
                            mat != null ? mat.name : ""));
                }
            }
        }
        SceneModel s = new SceneModel(name, parts, bones, world);
        s.warnings.addAll(warnings);
        for (Part p : parts) {
            for (int v = 0; v < p.vertices.length; v += STRIDE) {
                for (int k = 0; k < 3; k++) {
                    float c = p.vertices[v + k];
                    if (Float.isNaN(c) || Float.isInfinite(c)) continue;
                    if (c < s.min[k]) s.min[k] = c;
                    if (c > s.max[k]) s.max[k] = c;
                }
            }
        }
        if (parts.isEmpty()) {
            for (int k = 0; k < 3; k++) {
                s.min[k] = -1;
                s.max[k] = 1;
            }
        }
        return s;
    }

    private static float[] boneMatrix(List<NuccChunks.Coord> bones, float[][] cache, int i, int depth) {
        if (cache[i] != null) return cache[i];
        NuccChunks.Coord c = bones.get(i);
        float[] local = Mat4.multiply(Mat4.translation(c.position[0], c.position[1], c.position[2]),
                Mat4.multiply(Mat4.eulerZYX(c.rotation[0], c.rotation[1], c.rotation[2]),
                        Mat4.scale(Math.abs(c.scale[0]), Math.abs(c.scale[1]), Math.abs(c.scale[2]))));
        float[] result = local;
        if (c.parent >= 0 && c.parent != i && depth < 512) {
            result = Mat4.multiply(boneMatrix(bones, cache, c.parent, depth + 1), local);
        }
        cache[i] = result;
        return result;
    }

    private static float[] interleave(NudModel.Mesh mesh, float[] m) {
        float[] out = new float[mesh.vertexCount * STRIDE];
        for (int i = 0; i < mesh.vertexCount; i++) {
            int o = i * STRIDE;
            Mat4.transformPoint(m, mesh.positions, i * 3, out, o);
            Mat4.transformDir(m, mesh.normals, i * 3, out, o + 3);
            out[o + 6] = mesh.uvs[i * 2];
            out[o + 7] = mesh.uvs[i * 2 + 1];
        }
        return out;
    }
}
