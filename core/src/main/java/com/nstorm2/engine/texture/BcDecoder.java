package com.nstorm2.engine.texture;

/**
 * Software decoders for block-compressed textures (BC1/DXT1, BC2/DXT3, BC3/DXT5,
 * BC4/ATI1, BC5/ATI2). Mobile GPUs such as the Adreno 650 do not expose S3TC in
 * OpenGL ES, so textures are expanded to 32-bit pixels on the CPU.
 *
 * Output pixels are packed ARGB ints (Android Bitmap.Config.ARGB_8888 order).
 * Block data is little-endian as in DDS.
 */
public final class BcDecoder {
    private BcDecoder() {}

    public static final int BC1 = 1, BC2 = 2, BC3 = 3, BC4 = 4, BC5 = 5;

    public static int blockSize(int format) {
        return format == BC1 || format == BC4 ? 8 : 16;
    }

    public static int dataSize(int format, int width, int height) {
        return Math.max(1, (width + 3) / 4) * Math.max(1, (height + 3) / 4) * blockSize(format);
    }

    public static int[] decode(int format, byte[] src, int offset, int width, int height) {
        int[] out = new int[width * height];
        int bw = Math.max(1, (width + 3) / 4);
        int bh = Math.max(1, (height + 3) / 4);
        int bs = blockSize(format);
        int[] block = new int[16];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int p = offset + (by * bw + bx) * bs;
                if (p + bs > src.length) return out;
                switch (format) {
                    case BC1: decodeColor(src, p, block, true, null); break;
                    case BC2: decodeBc2(src, p, block); break;
                    case BC3: decodeBc3(src, p, block); break;
                    case BC4: decodeBc4(src, p, block); break;
                    case BC5: decodeBc5(src, p, block); break;
                    default: throw new IllegalArgumentException("format " + format);
                }
                for (int y = 0; y < 4; y++) {
                    int py = by * 4 + y;
                    if (py >= height) break;
                    for (int x = 0; x < 4; x++) {
                        int px = bx * 4 + x;
                        if (px >= width) break;
                        out[py * width + px] = block[y * 4 + x];
                    }
                }
            }
        }
        return out;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static int argb(int a, int r, int g, int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    /** BC1 colour block at p (8 bytes). alphaOut, if given, receives per-texel alpha override. */
    static void decodeColor(byte[] s, int p, int[] out, boolean allowPunchThrough, int[] alphaOut) {
        int c0 = u16(s, p), c1 = u16(s, p + 2);
        int[] r = new int[4], g = new int[4], b = new int[4], a = {255, 255, 255, 255};
        r[0] = ((c0 >> 11) & 31) * 255 / 31; g[0] = ((c0 >> 5) & 63) * 255 / 63; b[0] = (c0 & 31) * 255 / 31;
        r[1] = ((c1 >> 11) & 31) * 255 / 31; g[1] = ((c1 >> 5) & 63) * 255 / 63; b[1] = (c1 & 31) * 255 / 31;
        if (c0 > c1 || !allowPunchThrough) {
            r[2] = (2 * r[0] + r[1]) / 3; g[2] = (2 * g[0] + g[1]) / 3; b[2] = (2 * b[0] + b[1]) / 3;
            r[3] = (r[0] + 2 * r[1]) / 3; g[3] = (g[0] + 2 * g[1]) / 3; b[3] = (b[0] + 2 * b[1]) / 3;
        } else {
            r[2] = (r[0] + r[1]) / 2; g[2] = (g[0] + g[1]) / 2; b[2] = (b[0] + b[1]) / 2;
            r[3] = 0; g[3] = 0; b[3] = 0; a[3] = 0;
        }
        int bits = (s[p + 4] & 0xFF) | (s[p + 5] & 0xFF) << 8 | (s[p + 6] & 0xFF) << 16 | (s[p + 7] & 0xFF) << 24;
        for (int i = 0; i < 16; i++) {
            int idx = (bits >>> (2 * i)) & 3;
            int alpha = alphaOut != null ? alphaOut[i] : a[idx];
            out[i] = argb(alpha, r[idx], g[idx], b[idx]);
        }
    }

    private static void decodeBc2(byte[] s, int p, int[] out) {
        int[] alpha = new int[16];
        for (int i = 0; i < 16; i++) {
            int nib = (s[p + i / 2] >> ((i & 1) * 4)) & 0xF;
            alpha[i] = nib * 17;
        }
        decodeColor(s, p + 8, out, false, alpha);
    }

    /** BC4-style 8-bit channel block (alpha of BC3, red of BC4, red/green of BC5). */
    static void decodeChannel(byte[] s, int p, int[] values) {
        int v0 = s[p] & 0xFF, v1 = s[p + 1] & 0xFF;
        int[] pal = new int[8];
        pal[0] = v0;
        pal[1] = v1;
        if (v0 > v1) {
            for (int i = 1; i <= 6; i++) pal[i + 1] = ((7 - i) * v0 + i * v1) / 7;
        } else {
            for (int i = 1; i <= 4; i++) pal[i + 1] = ((5 - i) * v0 + i * v1) / 5;
            pal[6] = 0;
            pal[7] = 255;
        }
        long bits = 0;
        for (int i = 0; i < 6; i++) bits |= (long) (s[p + 2 + i] & 0xFF) << (8 * i);
        for (int i = 0; i < 16; i++) values[i] = pal[(int) ((bits >>> (3 * i)) & 7)];
    }

    private static void decodeBc3(byte[] s, int p, int[] out) {
        int[] alpha = new int[16];
        decodeChannel(s, p, alpha);
        decodeColor(s, p + 8, out, false, alpha);
    }

    private static void decodeBc4(byte[] s, int p, int[] out) {
        int[] v = new int[16];
        decodeChannel(s, p, v);
        for (int i = 0; i < 16; i++) out[i] = argb(255, v[i], v[i], v[i]);
    }

    private static void decodeBc5(byte[] s, int p, int[] out) {
        int[] rr = new int[16], gg = new int[16];
        decodeChannel(s, p, rr);
        decodeChannel(s, p + 8, gg);
        for (int i = 0; i < 16; i++) {
            // reconstruct Z of a tangent-space normal map so it previews sensibly
            double x = rr[i] / 127.5 - 1, y = gg[i] / 127.5 - 1;
            double z = Math.sqrt(Math.max(0, 1 - x * x - y * y));
            out[i] = argb(255, rr[i], gg[i], (int) Math.round((z + 1) * 127.5));
        }
    }
}
