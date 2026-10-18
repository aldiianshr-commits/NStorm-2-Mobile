package com.nstorm2.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nstorm2.engine.cpk.CriLayla;
import com.nstorm2.engine.cpk.UtfTable;
import com.nstorm2.engine.texture.BcDecoder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Regression tests. The decoders were additionally verified by hand against the real
 * CPK/CRILAYLA samples of CriFsV2Lib (byte-identical output); those samples are not
 * redistributed here.
 */
public class CompressionAndTextureTest {

    /** Reference CRILAYLA encoder (greedy) used only to round-trip the decoder. */
    static byte[] crilaylaEncode(byte[] data) {
        final int pre = CriLayla.PREFIX_SIZE;
        List<Integer> bits = new ArrayList<>();
        int w = data.length - 1;
        while (w >= pre) {
            int bestLen = 0, bestOff = 0;
            for (int off = 3; off <= 3 + 8191 && w + off < data.length; off++) {
                int len = 0;
                while (w - len >= pre && data[w - len] == data[w - len + off] && len < 5000) len++;
                if (len > bestLen) {
                    bestLen = len;
                    bestOff = off;
                }
            }
            if (bestLen >= 3) {
                put(bits, 1, 1);
                put(bits, bestOff - 3, 13);
                int rem = bestLen - 3;
                int v = Math.min(rem, 3); put(bits, v, 2); rem -= v;
                if (v == 3) {
                    v = Math.min(rem, 7); put(bits, v, 3); rem -= v;
                    if (v == 7) {
                        v = Math.min(rem, 31); put(bits, v, 5); rem -= v;
                        if (v == 31) {
                            do {
                                v = Math.min(rem, 255);
                                put(bits, v, 8);
                                rem -= v;
                            } while (v == 255);
                        }
                    }
                }
                w -= bestLen;
            } else {
                put(bits, 0, 1);
                put(bits, data[w] & 0xFF, 8);
                w--;
            }
        }
        while (bits.size() % 8 != 0) bits.add(0);
        byte[] stream = new byte[bits.size() / 8];
        for (int i = 0; i < stream.length; i++) {
            int b = 0;
            for (int k = 0; k < 8; k++) b = (b << 1) | bits.get(i * 8 + k);
            stream[stream.length - 1 - i] = (byte) b; // first-consumed byte sits highest
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("CRILAYLA".getBytes(), 0, 8);
        writeLe(out, data.length - pre);
        writeLe(out, stream.length);
        out.write(stream, 0, stream.length);
        out.write(data, 0, pre);
        return out.toByteArray();
    }

    private static void put(List<Integer> bits, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits.add((value >>> i) & 1);
    }

    private static void writeLe(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    @Test
    public void crilaylaRoundTrip() {
        Random rnd = new Random(1234);
        byte[] data = new byte[0x100 + 6000];
        rnd.nextBytes(data);
        byte[] phrase = "NARUTO SHIPPUDEN Ultimate Ninja STORM 2 / Rasengan! ".getBytes();
        for (int i = 0x100; i < data.length; i++) {
            data[i] = (i % 700) < 500 ? phrase[i % phrase.length] : (byte) rnd.nextInt(4);
        }
        byte[] packed = crilaylaEncode(data);
        assertTrue(CriLayla.isCompressed(packed));
        assertTrue(packed.length < data.length);
        assertArrayEquals(data, CriLayla.decompress(packed));
    }

    @Test
    public void utfTableXorIsInvolution() {
        byte[] plain = "@UTF\0\0\0\0hello world".getBytes();
        byte[] enc = plain.clone();
        UtfTable.decryptInPlace(enc);
        assertTrue(UtfTable.isEncrypted(enc));
        UtfTable.decryptInPlace(enc);
        assertArrayEquals(plain, enc);
        assertFalse(UtfTable.isEncrypted(plain));
    }

    @Test
    public void bc3AlphaAndBc1PunchThrough() {
        // BC3 block: alpha endpoints 255/0, all indices 1 (=0); colour white
        byte[] bc3 = new byte[16];
        bc3[0] = (byte) 255;
        bc3[1] = 0;
        byte[] idx1 = {0x49, (byte) 0x92, 0x24, 0x49, (byte) 0x92, 0x24}; // sixteen 3-bit indices = 1
        System.arraycopy(idx1, 0, bc3, 2, 6);
        bc3[8] = (byte) 0xFF; bc3[9] = (byte) 0xFF; // c0 white
        int[] px = BcDecoder.decode(BcDecoder.BC3, bc3, 0, 4, 4);
        assertEquals(0x00FFFFFF, px[0]);

        // BC1 with c0 <= c1: index 3 is transparent black
        byte[] bc1 = {0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        int[] t = BcDecoder.decode(BcDecoder.BC1, bc1, 0, 4, 4);
        assertEquals(0x00000000, t[5]);
    }
}
