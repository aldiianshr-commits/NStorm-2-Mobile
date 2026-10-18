package com.nstorm2.engine.texture;

import com.nstorm2.engine.io.BinaryReader;
import com.nstorm2.engine.io.FormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * NTP3 (Namco "NUT") texture container as embedded in nuccChunkTexture.
 * Layout follows Smash Forge's NUT reader (MIT) and xfbin_lib.
 *
 * Pixel formats: 0 DXT1, 1 DXT3, 2 DXT5, 8 RGB565, 12 RGBA4444, 14 / 17 ARGB8888,
 * 16 ABGR8888, 21 BC4, 22 BC5.
 */
public final class NutTexture {
    public final int width;
    public final int height;
    public final int format;
    public final int mipCount;
    public final boolean cubemap;
    private final byte[] data;
    private final int dataOffset;
    private final int level0Size;

    private NutTexture(int width, int height, int format, int mipCount, boolean cubemap, byte[] data, int dataOffset, int level0Size) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.mipCount = mipCount;
        this.cubemap = cubemap;
        this.data = data;
        this.dataOffset = dataOffset;
        this.level0Size = level0Size;
    }

    public static List<NutTexture> parse(BinaryReader nut) {
        BinaryReader r = nut.slice(0, nut.length());
        if (!"NTP3".equals(r.fixedString(4))) throw new FormatException("missing NTP3 magic");
        int version = r.u16();
        int count = r.u16();
        r.skip(8);
        List<NutTexture> out = new ArrayList<>();
        int headerPtr = 0x10;
        for (int i = 0; i < count; i++) {
            r.seek(headerPtr);
            int totalSize = (int) r.u32();
            r.u32();
            int dataSize = (int) r.u32();
            int headerSize = r.u16();
            r.u16();
            r.u8();
            int mipCount = Math.max(1, r.u8());
            r.u8();
            int format = r.u8();
            int width = r.u16();
            int height = r.u16();
            r.u32();
            long caps2 = r.u32();
            boolean cube = (caps2 & 0x200) != 0;
            int dataOffset;
            if (version < 0x200) {
                dataOffset = headerPtr + headerSize;
                r.u32();
            } else {
                dataOffset = (int) r.u32() + headerPtr;
            }
            r.skip(12);
            int cubeFace = 0;
            if (cube) {
                cubeFace = (int) r.u32();
                r.skip(12);
            }
            int level0;
            if (mipCount > 1) {
                level0 = (int) r.u32();
            } else {
                level0 = cube ? cubeFace : dataSize;
            }
            int abs = r.arrayOffset() + dataOffset;
            if (dataOffset < 0 || dataOffset + level0 > r.length()) throw new FormatException("NUT data out of range");
            out.add(new NutTexture(width, height, format, mipCount, cube, r.array(), abs, level0));
            headerPtr += version < 0x200 ? totalSize : headerSize;
        }
        return out;
    }

    public String formatName() {
        switch (format) {
            case 0: return "DXT1";
            case 1: return "DXT3";
            case 2: return "DXT5";
            case 8: return "RGB565";
            case 12: return "RGBA4444";
            case 14: case 17: return "ARGB8888";
            case 16: return "ABGR8888";
            case 21: return "BC4";
            case 22: return "BC5";
            default: return "0x" + Integer.toHexString(format);
        }
    }

    public boolean isSupported() {
        switch (format) {
            case 0: case 1: case 2: case 8: case 12: case 14: case 16: case 17: case 21: case 22: return true;
            default: return false;
        }
    }

    /** Decodes the top mip level (first face for cubemaps) to packed ARGB pixels. */
    public int[] decodeArgb() {
        int n = width * height;
        switch (format) {
            case 0: return BcDecoder.decode(BcDecoder.BC1, data, dataOffset, width, height);
            case 1: return BcDecoder.decode(BcDecoder.BC2, data, dataOffset, width, height);
            case 2: return BcDecoder.decode(BcDecoder.BC3, data, dataOffset, width, height);
            case 21: return BcDecoder.decode(BcDecoder.BC4, data, dataOffset, width, height);
            case 22: return BcDecoder.decode(BcDecoder.BC5, data, dataOffset, width, height);
            case 8: {
                int[] out = new int[n];
                for (int i = 0; i < n && dataOffset + i * 2 + 1 < data.length; i++) {
                    int v = (data[dataOffset + i * 2] & 0xFF) | (data[dataOffset + i * 2 + 1] & 0xFF) << 8;
                    int r = ((v >> 11) & 31) * 255 / 31, g = ((v >> 5) & 63) * 255 / 63, b = (v & 31) * 255 / 31;
                    out[i] = 0xFF000000 | r << 16 | g << 8 | b;
                }
                return out;
            }
            case 12: {
                int[] out = new int[n];
                for (int i = 0; i < n && dataOffset + i * 2 + 1 < data.length; i++) {
                    int v = (data[dataOffset + i * 2] & 0xFF) << 8 | (data[dataOffset + i * 2 + 1] & 0xFF);
                    int r = ((v >> 12) & 15) * 17, g = ((v >> 8) & 15) * 17, b = ((v >> 4) & 15) * 17, a = (v & 15) * 17;
                    out[i] = a << 24 | r << 16 | g << 8 | b;
                }
                return out;
            }
            case 14: case 17: case 16: {
                int[] out = new int[n];
                for (int i = 0; i < n && dataOffset + i * 4 + 3 < data.length; i++) {
                    int o = dataOffset + i * 4;
                    int c0 = data[o] & 0xFF, c1 = data[o + 1] & 0xFF, c2 = data[o + 2] & 0xFF, c3 = data[o + 3] & 0xFF;
                    if (format == 16) out[i] = c0 << 24 | c3 << 16 | c2 << 8 | c1;      // A B G R
                    else out[i] = c0 << 24 | c1 << 16 | c2 << 8 | c3;                    // A R G B
                }
                return out;
            }
            default:
                throw new FormatException("unsupported NUT pixel format " + formatName());
        }
    }

    public int level0Size() { return level0Size; }
}
