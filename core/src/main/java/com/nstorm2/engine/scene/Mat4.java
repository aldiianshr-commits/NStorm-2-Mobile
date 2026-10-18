package com.nstorm2.engine.scene;

/** Minimal column-major 4x4 matrix helpers (same layout as OpenGL / android.opengl.Matrix). */
public final class Mat4 {
    private Mat4() {}

    public static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    public static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    public static float[] translation(float x, float y, float z) {
        float[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    public static float[] scale(float x, float y, float z) {
        float[] m = identity();
        m[0] = x;
        m[5] = y;
        m[10] = z;
        return m;
    }

    public static float[] rotX(float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[] m = identity();
        m[5] = c; m[6] = s; m[9] = -s; m[10] = c;
        return m;
    }

    public static float[] rotY(float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[] m = identity();
        m[0] = c; m[2] = -s; m[8] = s; m[10] = c;
        return m;
    }

    public static float[] rotZ(float deg) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float[] m = identity();
        m[0] = c; m[1] = s; m[4] = -s; m[5] = c;
        return m;
    }

    /**
     * Euler rotation in CC2 coord order ("ZYX" in Blender terms): Z is applied first,
     * then Y, then X, i.e. R = Rx * Ry * Rz.
     */
    public static float[] eulerZYX(float x, float y, float z) {
        return multiply(rotX(x), multiply(rotY(y), rotZ(z)));
    }

    public static void transformPoint(float[] m, float[] v, int i, float[] out, int o) {
        float x = v[i], y = v[i + 1], z = v[i + 2];
        out[o] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    public static void transformDir(float[] m, float[] v, int i, float[] out, int o) {
        float x = v[i], y = v[i + 1], z = v[i + 2];
        float nx = m[0] * x + m[4] * y + m[8] * z;
        float ny = m[1] * x + m[5] * y + m[9] * z;
        float nz = m[2] * x + m[6] * y + m[10] * z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        out[o] = nx;
        out[o + 1] = ny;
        out[o + 2] = nz;
    }
}
