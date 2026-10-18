package com.nstorm2.engine.xfbin;

import com.nstorm2.engine.io.BinaryReader;
import com.nstorm2.engine.io.FormatException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CyberConnect2 XFBIN container ("NUCC"), big-endian.
 *
 * <pre>
 * "NUCC" u32 version, 8 bytes flags
 * chunk header: u32 size, u32 mapIndex, u16 version, u16 unk   (first chunk = index)
 * index: 10 x u32 counts/sizes, chunk type / file path / chunk name strings,
 *        align 4, chunk maps (type, path, name indices), references, map indices
 * then pages: chunks until a nuccChunkPage (u32 pageSize, u32 referenceSize)
 * </pre>
 * Chunk-to-chunk references inside chunk data are indices into the current page's
 * slice of the map-index table (see {@link Page#resolve}).
 *
 * Format knowledge from xfbin_lib / cc2_xfbin_blender (MIT, SutandoTsukai181) and
 * the NUCC 010 template library (KojoBailey).
 */
public final class Xfbin {

    public static final class Chunk {
        public final String type;
        public final String path;
        public final String name;
        public final int mapIndex;
        public final int page;
        /** Chunk payload (without the 12-byte chunk header). */
        public final BinaryReader data;

        Chunk(String type, String path, String name, int mapIndex, int page, BinaryReader data) {
            this.type = type;
            this.path = path;
            this.name = name;
            this.mapIndex = mapIndex;
            this.page = page;
            this.data = data;
        }

        public int size() { return data.length(); }

        @Override
        public String toString() {
            return type + " " + name;
        }
    }

    public final class Page {
        public final int index;
        final int mapStart;
        final int mapCount;
        public final List<Chunk> chunks = new ArrayList<>();
        final java.util.HashMap<Integer, Chunk> byMap = new java.util.HashMap<>();

        Page(int index, int mapStart, int mapCount) {
            this.index = index;
            this.mapStart = mapStart;
            this.mapCount = mapCount;
        }

        /** Resolves a page-relative chunk index used inside chunk data. */
        public Chunk resolve(int localIndex) {
            if (localIndex < 0 || mapStart + localIndex >= mapIndices.length) return null;
            int map = mapIndices[mapStart + localIndex];
            if (map < 0 || map >= chunkByMap.length) return null;
            Chunk own = byMap.get(map);
            return own != null ? own : chunkByMap[map];
        }
    }

    public final int version;
    private final String[] types;
    private final String[] paths;
    private final String[] names;
    private final int[][] maps;      // [mapIndex] -> {type, path, name}
    private final int[] mapIndices;
    private final Chunk[] chunkByMap;
    private final List<Page> pages = new ArrayList<>();
    private final List<Chunk> allChunks = new ArrayList<>();

    public static boolean isXfbin(byte[] d) {
        return d != null && d.length >= 4 && d[0] == 'N' && d[1] == 'U' && d[2] == 'C' && d[3] == 'C';
    }

    public static Xfbin parse(byte[] bytes) {
        if (!isXfbin(bytes)) throw new FormatException("missing NUCC magic");
        return new Xfbin(new BinaryReader(bytes, true));
    }

    private Xfbin(BinaryReader r) {
        r.seek(4);
        version = (int) r.u32();
        r.skip(8);

        // index chunk header
        r.u32();          // size (excludes the reference table)
        r.u32();          // map index
        r.u16();          // version
        r.u16();

        int typeCount = (int) r.u32(); r.u32();
        int pathCount = (int) r.u32(); r.u32();
        int nameCount = (int) r.u32(); r.u32();
        int mapCount = (int) r.u32(); r.u32();
        int mapIndexCount = (int) r.u32();
        int referenceCount = (int) r.u32();
        sanity(typeCount, pathCount, nameCount, mapCount, mapIndexCount, referenceCount);

        types = readStrings(r, typeCount);
        paths = readStrings(r, pathCount);
        names = readStrings(r, nameCount);
        r.align(4);

        maps = new int[mapCount][3];
        for (int i = 0; i < mapCount; i++) {
            maps[i][0] = (int) r.u32();
            maps[i][1] = (int) r.u32();
            maps[i][2] = (int) r.u32();
        }
        r.skip(referenceCount * 8);
        mapIndices = new int[mapIndexCount];
        for (int i = 0; i < mapIndexCount; i++) mapIndices[i] = (int) r.u32();

        chunkByMap = new Chunk[mapCount];

        int pageStart = 0;
        List<Chunk> current = new ArrayList<>();
        while (r.remaining() >= 12) {
            int size = (int) r.u32();
            int local = (int) r.u32();
            r.u16();
            r.u16();
            if (size < 0 || size > r.remaining()) throw new FormatException("chunk size " + size + " exceeds file");
            BinaryReader data = r.slice(r.position(), size);
            r.skip(size);

            int slot = pageStart + local;
            int map = slot >= 0 && slot < mapIndices.length ? mapIndices[slot] : -1;
            if (map < 0 || map >= mapCount) throw new FormatException("bad chunk map index " + local + " in page " + pages.size());
            String type = str(types, maps[map][0]);
            Chunk c = new Chunk(type, str(paths, maps[map][1]), str(names, maps[map][2]), map, pages.size(), data);
            chunkByMap[map] = c;

            if ("nuccChunkPage".equals(type)) {
                int pageSize = size >= 4 ? (int) data.seek(0).u32() : 0;
                Page page = new Page(pages.size(), pageStart, pageSize);
                page.chunks.addAll(current);
                for (Chunk k : current) page.byMap.put(k.mapIndex, k);
                pages.add(page);
                current.clear();
                pageStart += pageSize;
            } else {
                current.add(c);
                allChunks.add(c);
            }
        }
        if (!current.isEmpty()) {
            Page tail = new Page(pages.size(), pageStart, 0);
            tail.chunks.addAll(current);
            for (Chunk k : current) tail.byMap.put(k.mapIndex, k);
            pages.add(tail);
        }
    }

    private static void sanity(int... counts) {
        for (int c : counts) if (c < 0 || c > 1_000_000) throw new FormatException("implausible XFBIN index count " + c);
    }

    private static String[] readStrings(BinaryReader r, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = r.cString(BinaryReader.SHIFT_JIS);
        return out;
    }

    private static String str(String[] a, int i) {
        return i >= 0 && i < a.length ? a[i] : "";
    }

    public List<Page> pages() { return Collections.unmodifiableList(pages); }
    public List<Chunk> chunks() { return Collections.unmodifiableList(allChunks); }

    public Page page(Chunk c) {
        return c.page < pages.size() ? pages.get(c.page) : null;
    }

    public List<Chunk> chunksOfType(String type) {
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : allChunks) if (c.type.equals(type)) out.add(c);
        return out;
    }

    /** Distinct chunk type names declared in the index (cheap way to classify a file). */
    public List<String> declaredTypes() {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, types);
        return out;
    }

    /**
     * Fast check without parsing chunks: does the index declare a chunk type?
     * Only the start of the file is needed.
     */
    public static boolean declaresType(byte[] head, String type) {
        if (!isXfbin(head)) return false;
        byte[] needle = (type + "\0").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return BinaryReader.indexOf(head, 0x28, Math.min(head.length, 0x28 + 0x4000), needle) >= 0;
    }
}
