package com.nstorm2.engine.cpk;

import com.nstorm2.engine.io.FormatException;

/**
 * CRILAYLA decompression (CRI's backwards LZSS variant used inside CPK archives).
 *
 * <pre>
 * 00 "CRILAYLA"
 * 08 u32 LE  uncompressed size of the compressed part
 * 0C u32 LE  offset (from 0x10) of the 0x100-byte uncompressed prefix
 * 10 ...     compressed bitstream, read backwards from its end
 * </pre>
 * Output = 0x100 raw prefix followed by the decompressed data, which is written
 * from the end towards the start. A set flag bit introduces a back-reference
 * (13-bit offset + 3, length 3 + 2/3/5/8-bit levels, then repeated 8-bit
 * levels while saturated); a clear flag bit is followed by a literal byte.
 */
public final class CriLayla {
    public static final int PREFIX_SIZE = 0x100;
    private static final byte[] MAGIC = {'C', 'R', 'I', 'L', 'A', 'Y', 'L', 'A'};

    private CriLayla() {}

    public static boolean isCompressed(byte[] data) {
        if (data == null || data.length < 0x10) return false;
        for (int i = 0; i < 8; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
    }

    /** Total stored size according to the header (may exceed a CPK entry's FileSize). */
    public static int storedSize(byte[] header) {
        return le32(header, 12) + 0x10 + PREFIX_SIZE;
    }

    public static byte[] decompress(byte[] input) {
        if (!isCompressed(input)) throw new FormatException("missing CRILAYLA magic");
        int uncompressedSize = le32(input, 8);
        int prefixOffset = le32(input, 12) + 0x10;
        if (uncompressedSize < 0 || prefixOffset < 0x10 || prefixOffset + PREFIX_SIZE > input.length) {
            throw new FormatException("bad CRILAYLA header");
        }
        byte[] out = new byte[uncompressedSize + PREFIX_SIZE];
        System.arraycopy(input, prefixOffset, out, 0, PREFIX_SIZE);

        Bits bits = new Bits(input, prefixOffset - 1);
        int write = PREFIX_SIZE + uncompressedSize - 1;
        final int end = PREFIX_SIZE;

        while (write >= end) {
            if (bits.read(1) != 0) {
                int offset = bits.read(13) + 3;
                int length = 3;
                int level = bits.read(2);
                length += level;
                if (level == 3) {
                    level = bits.read(3);
                    length += level;
                    if (level == 7) {
                        level = bits.read(5);
                        length += level;
                        if (level == 31) {
                            do {
                                level = bits.read(8);
                                length += level;
                            } while (level == 255);
                        }
                    }
                }
                for (int i = 0; i < length && write >= end; i++) {
                    int src = write + offset;
                    if (src >= out.length) throw new FormatException("CRILAYLA back-reference out of range");
                    out[write] = out[src];
                    write--;
                }
            } else {
                out[write--] = (byte) bits.read(8);
            }
        }
        return out;
    }

    /** MSB-first bit reader that consumes bytes from high to low addresses. */
    private static final class Bits {
        private final byte[] data;
        private int index;
        private int pool;
        private int left;

        Bits(byte[] data, int startIndex) {
            this.data = data;
            this.index = startIndex;
        }

        int read(int count) {
            int result = 0;
            int produced = 0;
            while (produced < count) {
                if (left == 0) {
                    if (index < 0x10) throw new FormatException("CRILAYLA stream underrun");
                    pool = data[index--] & 0xFF;
                    left = 8;
                }
                int take = Math.min(left, count - produced);
                result = (result << take) | ((pool >>> (left - take)) & ((1 << take) - 1));
                left -= take;
                produced += take;
            }
            return result;
        }
    }
}
