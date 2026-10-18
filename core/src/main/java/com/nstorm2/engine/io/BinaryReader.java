package com.nstorm2.engine.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Bounds-checked reader over a byte array with switchable endianness.
 * CyberConnect2 (XFBIN/NUD/NUT) data is big-endian; CRI container headers are little-endian.
 */
public final class BinaryReader {
    private final byte[] data;
    private final int base;
    private final int limit;
    private int pos;
    private boolean bigEndian;

    public static final Charset SHIFT_JIS = charsetOr("Shift_JIS", StandardCharsets.ISO_8859_1);

    private static Charset charsetOr(String name, Charset fallback) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return fallback;
        }
    }

    public BinaryReader(byte[] data, boolean bigEndian) {
        this(data, 0, data.length, bigEndian);
    }

    public BinaryReader(byte[] data, int offset, int length, boolean bigEndian) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new FormatException("slice out of range: " + offset + "+" + length + " > " + data.length);
        }
        this.data = data;
        this.base = offset;
        this.limit = offset + length;
        this.pos = offset;
        this.bigEndian = bigEndian;
    }

    /** A reader over a sub-range of this reader; positions in the new reader start at 0. */
    public BinaryReader slice(int relOffset, int length) {
        return new BinaryReader(data, base + relOffset, length, bigEndian);
    }

    public byte[] array() { return data; }
    public int arrayOffset() { return base; }
    public int length() { return limit - base; }
    public int position() { return pos - base; }
    public int remaining() { return limit - pos; }
    public boolean eof() { return pos >= limit; }
    public void setBigEndian(boolean be) { this.bigEndian = be; }

    public BinaryReader seek(int relPos) {
        if (relPos < 0 || base + relPos > limit) throw new FormatException("seek out of range: " + relPos + " / " + length());
        pos = base + relPos;
        return this;
    }

    public BinaryReader skip(int n) {
        return seek(position() + n);
    }

    public void align(int alignment) {
        int p = position();
        int r = p % alignment;
        if (r != 0) skip(alignment - r);
    }

    private void need(int n) {
        if (n < 0 || pos + n > limit) {
            throw new FormatException("read past end: need " + n + " at " + position() + " / " + length());
        }
    }

    public int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    public int s8() {
        need(1);
        return data[pos++];
    }

    public int u16() {
        need(2);
        int a = data[pos] & 0xFF, b = data[pos + 1] & 0xFF;
        pos += 2;
        return bigEndian ? (a << 8) | b : (b << 8) | a;
    }

    public int s16() {
        return (short) u16();
    }

    public int s32() {
        need(4);
        int a = data[pos] & 0xFF, b = data[pos + 1] & 0xFF, c = data[pos + 2] & 0xFF, d = data[pos + 3] & 0xFF;
        pos += 4;
        return bigEndian ? (a << 24) | (b << 16) | (c << 8) | d : (d << 24) | (c << 16) | (b << 8) | a;
    }

    public long u32() {
        return s32() & 0xFFFFFFFFL;
    }

    public long s64() {
        long hi, lo;
        if (bigEndian) {
            hi = u32();
            lo = u32();
        } else {
            lo = u32();
            hi = u32();
        }
        return (hi << 32) | lo;
    }

    public float f32() {
        return Float.intBitsToFloat(s32());
    }

    public float f16() {
        return halfToFloat(u16());
    }

    public byte[] bytes(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    public String fixedString(int n) {
        need(n);
        String s = new String(data, pos, n, StandardCharsets.ISO_8859_1);
        pos += n;
        return s;
    }

    /** Zero-terminated string; advances past the terminator. */
    public String cString(Charset cs) {
        int start = pos;
        int end = start;
        while (end < limit && data[end] != 0) end++;
        if (end >= limit) throw new FormatException("unterminated string at " + position());
        pos = end + 1;
        return new String(data, start, end - start, cs);
    }

    /** Zero-terminated string at an absolute (relative-to-slice) offset, without moving the cursor. */
    public String cStringAt(int relOffset, Charset cs) {
        int save = pos;
        try {
            seek(relOffset);
            return cString(cs);
        } finally {
            pos = save;
        }
    }

    public static float halfToFloat(int h) {
        int sign = (h >>> 15) & 1;
        int exp = (h >>> 10) & 0x1F;
        int mant = h & 0x3FF;
        float v;
        if (exp == 0) {
            v = (float) (mant * Math.pow(2, -24));
        } else if (exp == 31) {
            v = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            v = (float) ((1 + mant / 1024.0) * Math.pow(2, exp - 15));
        }
        return sign == 1 ? -v : v;
    }

    public static int indexOf(byte[] hay, int from, int to, byte[] needle) {
        outer:
        for (int i = Math.max(0, from); i <= to - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
