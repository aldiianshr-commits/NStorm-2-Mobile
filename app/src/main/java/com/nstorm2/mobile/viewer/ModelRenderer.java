package com.nstorm2.mobile.viewer;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;

import com.nstorm2.engine.scene.SceneModel;
import com.nstorm2.engine.xfbin.Xfbin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Native OpenGL ES 3.0 renderer for a {@link SceneModel}: one VAO/VBO/IBO per part,
 * diffuse texture, headlight Lambert shading, optional wireframe and alpha test.
 * Camera and input state are written from the UI thread and read on the GL thread.
 */
public final class ModelRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "NStormRenderer";

    private static final String VS = "#version 300 es\n"
            + "layout(location=0) in vec3 aPos;\n"
            + "layout(location=1) in vec3 aNrm;\n"
            + "layout(location=2) in vec2 aUv;\n"
            + "uniform mat4 uMvp;\n"
            + "uniform mat4 uModel;\n"
            + "out vec3 vNrm;\n"
            + "out vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
            + "  vNrm = mat3(uModel) * aNrm;\n"
            + "  vUv = aUv;\n"
            + "}\n";

    private static final String FS = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec3 vNrm;\n"
            + "in vec2 vUv;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform int uHasTex;\n"
            + "uniform int uLighting;\n"
            + "uniform int uAlphaTest;\n"
            + "uniform vec3 uLight;\n"
            + "uniform vec4 uColor;\n"
            + "out vec4 outColor;\n"
            + "void main() {\n"
            + "  vec4 base = uHasTex == 1 ? texture(uTex, vUv) : uColor;\n"
            + "  if (uAlphaTest == 1 && base.a < 0.3) discard;\n"
            + "  float lit = 1.0;\n"
            + "  if (uLighting == 1 && dot(vNrm, vNrm) > 1e-4) {\n"
            + "    vec3 n = normalize(vNrm);\n"
            + "    float d = abs(dot(n, uLight));\n"
            + "    lit = 0.42 + 0.58 * d;\n"
            + "  }\n"
            + "  outColor = vec4(base.rgb * lit, 1.0);\n"
            + "}\n";

    /** Everything the GL thread needs to (re)build GPU resources. */
    public static final class SceneData {
        public final SceneModel model;
        public final Map<Xfbin.Chunk, Bitmap> textures;

        public SceneData(SceneModel model, Map<Xfbin.Chunk, Bitmap> textures) {
            this.model = model;
            this.textures = textures;
        }
    }

    public interface StatsListener {
        void onStats(float fps, int triangles, int drawCalls);
    }

    private static final class GpuPart {
        int vao, vbo, ibo, lineIbo = -1;
        int indexCount, lineCount;
        int texture;
        SceneModel.Part part;
    }

    // ---- settings / state (written from UI thread)
    public volatile boolean wireframe;
    public volatile boolean lighting = true;
    public volatile boolean alphaTest = true;
    public volatile boolean zUp;
    public volatile boolean[] visible;
    public volatile int fpsCap = 60;
    public volatile String textureFilter = "trilinear";
    public volatile float padSensitivity = 1f;
    public volatile boolean invertY;

    // gamepad axes (-1..1), integrated per frame
    public volatile float axisRotX, axisRotY, axisPanX, axisPanY, axisZoom;

    private final Object camLock = new Object();
    private float yaw = 30, pitch = 12, distance = 10;
    private final float[] target = new float[3];
    private float radius = 1;

    private volatile SceneData scene;
    private volatile boolean needsUpload;
    private GpuPart[] gpu = new GpuPart[0];
    private final Map<Xfbin.Chunk, Integer> gpuTextures = new IdentityHashMap<>();

    private int program, uMvp, uModel, uTex, uHasTex, uLighting, uAlphaTest, uLight, uColor;
    private int width = 1, height = 1;
    private final float[] proj = new float[16], view = new float[16], modelM = new float[16], mvp = new float[16], tmp = new float[16];

    private long lastFrame, statsStart;
    private int frames;
    private StatsListener stats;

    public void setStatsListener(StatsListener l) {
        stats = l;
    }

    public void setScene(SceneData data) {
        scene = data;
        boolean[] v = new boolean[data.model.parts.size()];
        java.util.Arrays.fill(v, true);
        visible = v;
        resetCamera();
        needsUpload = true;
    }

    public SceneData scene() {
        return scene;
    }

    // ------------------------------------------------------------------ camera

    public void resetCamera() {
        SceneData s = scene;
        synchronized (camLock) {
            yaw = 30;
            pitch = 12;
            if (s == null) return;
            radius = s.model.radius();
            float[] c = s.model.center();
            float[] m = axisMatrix();
            float[] in = {c[0], c[1], c[2], 1}, out = new float[4];
            Matrix.multiplyMV(out, 0, m, 0, in, 0);
            target[0] = out[0];
            target[1] = out[1];
            target[2] = out[2];
            distance = (float) (radius / Math.tan(Math.toRadians(22.5)) * 1.15);
        }
    }

    public void rotate(float dYawDeg, float dPitchDeg) {
        synchronized (camLock) {
            yaw += dYawDeg;
            pitch = Math.max(-89, Math.min(89, pitch + dPitchDeg));
        }
    }

    public void zoom(float factor) {
        synchronized (camLock) {
            distance = Math.max(radius * 0.05f, Math.min(radius * 30f, distance * factor));
        }
    }

    /** Pans in screen space; dx/dy are fractions of the viewport. */
    public void pan(float dx, float dy) {
        synchronized (camLock) {
            float scale = distance * 0.8f;
            double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
            // camera right and up vectors
            float rx = (float) Math.cos(y), rz = (float) -Math.sin(y);
            float ux = (float) (-Math.sin(p) * Math.sin(y)), uy = (float) Math.cos(p), uz = (float) (-Math.sin(p) * Math.cos(y));
            target[0] += (-dx * rx + dy * ux) * scale;
            target[1] += (dy * uy) * scale;
            target[2] += (-dx * rz + dy * uz) * scale;
        }
    }

    private float[] axisMatrix() {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        if (zUp) Matrix.rotateM(m, 0, -90, 1, 0, 0);
        return m;
    }

    // ------------------------------------------------------------------ GL

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        program = link(VS, FS);
        uMvp = GLES30.glGetUniformLocation(program, "uMvp");
        uModel = GLES30.glGetUniformLocation(program, "uModel");
        uTex = GLES30.glGetUniformLocation(program, "uTex");
        uHasTex = GLES30.glGetUniformLocation(program, "uHasTex");
        uLighting = GLES30.glGetUniformLocation(program, "uLighting");
        uAlphaTest = GLES30.glGetUniformLocation(program, "uAlphaTest");
        uLight = GLES30.glGetUniformLocation(program, "uLight");
        uColor = GLES30.glGetUniformLocation(program, "uColor");
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE); // CC2 meshes mix windings; draw double-sided
        GLES30.glClearColor(0.05f, 0.07f, 0.12f, 1f);
        gpu = new GpuPart[0];
        gpuTextures.clear();
        if (scene != null) needsUpload = true;
        lastFrame = statsStart = SystemClock.elapsedRealtimeNanos();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = SystemClock.elapsedRealtimeNanos();
        int cap = fpsCap;
        if (cap > 0) {
            long budget = 1_000_000_000L / cap;
            long wait = budget - (now - lastFrame);
            if (wait > 1_000_000L) {
                SystemClock.sleep(wait / 1_000_000L);
                now = SystemClock.elapsedRealtimeNanos();
            }
        }
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        if (needsUpload) upload();
        applyGamepad(dt);

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        SceneData s = scene;
        int tris = 0, calls = 0;
        if (s != null && gpu.length > 0) {
            float ex, ey, ez, tx, ty, tz, near, far;
            synchronized (camLock) {
                double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                ex = target[0] + (float) (distance * Math.cos(p) * Math.sin(y));
                ey = target[1] + (float) (distance * Math.sin(p));
                ez = target[2] + (float) (distance * Math.cos(p) * Math.cos(y));
                tx = target[0];
                ty = target[1];
                tz = target[2];
                near = Math.max(0.01f, distance - radius * 3f);
                near = Math.max(near, distance * 0.01f);
                far = distance + radius * 6f;
            }
            Matrix.perspectiveM(proj, 0, 45f, width / (float) height, near, far);
            Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0, 1, 0);
            float[] axis = axisMatrix();
            System.arraycopy(axis, 0, modelM, 0, 16);
            Matrix.multiplyMM(tmp, 0, view, 0, modelM, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

            float lx = ex - tx, ly = ey - ty + radius * 0.6f, lz = ez - tz;
            float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            GLES30.glUseProgram(program);
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0);
            GLES30.glUniform3f(uLight, lx / len, ly / len, lz / len);
            GLES30.glUniform1i(uLighting, lighting ? 1 : 0);
            GLES30.glUniform1i(uTex, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

            boolean wire = wireframe;
            boolean[] vis = visible;
            for (int i = 0; i < gpu.length; i++) {
                GpuPart g = gpu[i];
                if (vis != null && i < vis.length && !vis[i]) continue;
                GLES30.glBindVertexArray(g.vao);
                boolean textured = g.texture != 0 && !wire;
                GLES30.glUniform1i(uHasTex, textured ? 1 : 0);
                GLES30.glUniform1i(uAlphaTest, textured && alphaTest ? 1 : 0);
                if (textured) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, g.texture);
                if (wire) {
                    if (g.lineIbo < 0) buildLines(g);
                    GLES30.glUniform4f(uColor, 1f, 0.55f, 0.15f, 1f);
                    GLES30.glUniform1i(uLighting, 0);
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.lineIbo);
                    GLES30.glDrawElements(GLES30.GL_LINES, g.lineCount, GLES30.GL_UNSIGNED_INT, 0);
                    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
                    GLES30.glUniform1i(uLighting, lighting ? 1 : 0);
                } else {
                    GLES30.glUniform4f(uColor, 0.72f, 0.74f, 0.80f, 1f);
                    GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.indexCount, GLES30.GL_UNSIGNED_INT, 0);
                }
                tris += g.indexCount / 3;
                calls++;
            }
            GLES30.glBindVertexArray(0);
        }

        frames++;
        if (now - statsStart > 500_000_000L) {
            float fps = frames * 1e9f / (now - statsStart);
            frames = 0;
            statsStart = now;
            StatsListener l = stats;
            if (l != null) l.onStats(fps, tris, calls);
        }
    }

    private void applyGamepad(float dt) {
        float s = padSensitivity;
        float rx = axisRotX, ry = axisRotY, px = axisPanX, py = axisPanY, z = axisZoom;
        if (rx != 0 || ry != 0) rotate(-rx * 150f * dt * s, (invertY ? ry : -ry) * 110f * dt * s);
        if (px != 0 || py != 0) pan(px * 0.9f * dt * s, (invertY ? py : -py) * 0.9f * dt * s);
        if (z != 0) zoom((float) Math.pow(1.0 + 1.6 * Math.abs(z) * dt * s, Math.signum(z)));
    }

    // ------------------------------------------------------------------ upload

    private void upload() {
        needsUpload = false;
        SceneData s = scene;
        if (s == null) return;
        // textures
        for (Map.Entry<Xfbin.Chunk, Bitmap> e : s.textures.entrySet()) {
            Bitmap b = e.getValue();
            if (b == null || b.isRecycled()) continue;
            int[] id = new int[1];
            GLES30.glGenTextures(1, id, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0]);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            String f = textureFilter;
            if ("nearest".equals(f)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            } else if ("bilinear".equals(f)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            } else {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
            gpuTextures.put(e.getKey(), id[0]);
        }
        // meshes
        GpuPart[] parts = new GpuPart[s.model.parts.size()];
        for (int i = 0; i < parts.length; i++) {
            SceneModel.Part p = s.model.parts.get(i);
            GpuPart g = new GpuPart();
            g.part = p;
            int[] ids = new int[1];
            GLES30.glGenVertexArrays(1, ids, 0);
            g.vao = ids[0];
            GLES30.glBindVertexArray(g.vao);

            int[] bufs = new int[2];
            GLES30.glGenBuffers(2, bufs, 0);
            g.vbo = bufs[0];
            g.ibo = bufs[1];
            FloatBuffer fb = ByteBuffer.allocateDirect(p.vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(p.vertices).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.vertices.length * 4, fb, GLES30.GL_STATIC_DRAW);
            IntBuffer ib = ByteBuffer.allocateDirect(p.indices.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(p.indices).position(0);
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.length * 4, ib, GLES30.GL_STATIC_DRAW);
            int stride = SceneModel.STRIDE * 4;
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
            GLES30.glEnableVertexAttribArray(1);
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
            GLES30.glEnableVertexAttribArray(2);
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24);
            GLES30.glBindVertexArray(0);

            g.indexCount = p.indices.length;
            Integer tex = p.texture != null ? gpuTextures.get(p.texture) : null;
            g.texture = tex != null ? tex : 0;
            parts[i] = g;
        }
        gpu = parts;
        int err = GLES30.glGetError();
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error after upload: 0x" + Integer.toHexString(err));
    }

    private void buildLines(GpuPart g) {
        int[] tri = g.part.indices;
        HashMap<Long, Boolean> seen = new HashMap<>();
        int[] lines = new int[tri.length * 2];
        int n = 0;
        for (int i = 0; i + 2 < tri.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                int a = tri[i + k], b = tri[i + (k + 1) % 3];
                long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                if (seen.put(key, Boolean.TRUE) == null) {
                    lines[n++] = a;
                    lines[n++] = b;
                }
            }
        }
        int[] ids = new int[1];
        GLES30.glGenBuffers(1, ids, 0);
        g.lineIbo = ids[0];
        IntBuffer ib = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        ib.put(lines, 0, n).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.lineIbo);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, n * 4, ib, GLES30.GL_STATIC_DRAW);
        g.lineCount = n;
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs));
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }
}
