package com.nstorm2.engine.cpk;

import com.nstorm2.engine.io.FormatException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Read-only access to a CRI CPK archive, the container format Storm 2 uses for its data.
 *
 * Supports name-based tables of contents (TOC) and ID-only tables (ITOC), encrypted
 * @UTF tables and CRILAYLA-compressed entries. Each section starts with a 16-byte
 * packet header: 4-byte tag ("CPK ", "TOC ", "ITOC"), u32 flags, u64 LE table size.
 */
public final class CpkArchive implements Closeable {

    public static final class Entry {
        public final String directory;
        public final String fileName;
        public final long offset;
        public final int storedSize;
        public final int extractSize;
        public final int id;

        Entry(String directory, String fileName, long offset, int storedSize, int extractSize, int id) {
            this.directory = directory == null ? "" : directory;
            this.fileName = fileName;
            this.offset = offset;
            this.storedSize = storedSize;
            this.extractSize = extractSize;
            this.id = id;
        }

        public String path() {
            return directory.isEmpty() ? fileName : directory + "/" + fileName;
        }

        public boolean isCompressed() {
            return extractSize > 0 && extractSize != storedSize;
        }

        public String extension() {
            int dot = fileName.lastIndexOf('.');
            return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return path();
        }
    }

    private final File file;
    private final RandomAccessFile raf;
    private final List<Entry> entries;
    private final java.util.Map<String, Entry> byPath = new java.util.HashMap<>();

    public CpkArchive(File file) throws IOException {
        this.file = file;
        this.raf = new RandomAccessFile(file, "r");
        try {
            this.entries = Collections.unmodifiableList(readEntries());
            for (Entry e : entries) byPath.put(e.path().toLowerCase(Locale.ROOT), e);
        } catch (RuntimeException | IOException e) {
            raf.close();
            throw e;
        }
    }

    public File file() { return file; }
    public List<Entry> entries() { return entries; }

    public static boolean looksLikeCpk(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] m = new byte[4];
            return r.length() >= 16 && r.read(m) == 4 && m[0] == 'C' && m[1] == 'P' && m[2] == 'K' && m[3] == ' ';
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized byte[] readAt(long offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > raf.length()) {
            throw new FormatException("read outside archive: " + offset + "+" + length);
        }
        byte[] b = new byte[length];
        raf.seek(offset);
        raf.readFully(b);
        return b;
    }

    private UtfTable readPacket(long offset, String tag) throws IOException {
        byte[] head = readAt(offset, 16);
        String got = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!got.equals(tag)) throw new FormatException("expected '" + tag + "' at " + offset + " but found '" + got + "'");
        long size = 0;
        for (int i = 0; i < 8; i++) size |= (long) (head[8 + i] & 0xFF) << (8 * i);
        if (size <= 0 || size > Integer.MAX_VALUE) throw new FormatException("bad " + tag + " table size " + size);
        return UtfTable.parse(readAt(offset + 16, (int) size));
    }

    private List<Entry> readEntries() throws IOException {
        UtfTable header = readPacket(0, "CPK ");
        long tocOffset = header.getLong(0, "TocOffset", 0);
        long itocOffset = header.getLong(0, "ItocOffset", 0);
        long contentOffset = header.getLong(0, "ContentOffset", 0);
        int align = (int) Math.max(1, header.getLong(0, "Align", 1));

        List<Entry> list = new ArrayList<>();
        if (tocOffset > 0) {
            UtfTable toc = readPacket(tocOffset, "TOC ");
            // File offsets are relative to whichever of TOC / content comes first.
            long base = Math.min(tocOffset, contentOffset > 0 ? contentOffset : tocOffset);
            for (int i = 0; i < toc.rowCount(); i++) {
                String dir = toc.getString(i, "DirName");
                String name = toc.getString(i, "FileName");
                long off = toc.getLong(i, "FileOffset", -1);
                int size = (int) toc.getLong(i, "FileSize", 0);
                int extract = (int) toc.getLong(i, "ExtractSize", size);
                int id = (int) toc.getLong(i, "ID", i);
                if (name == null || off < 0) continue;
                list.add(new Entry(dir, name, base + off, size, extract, id));
            }
        } else if (itocOffset > 0) {
            UtfTable itoc = readPacket(itocOffset, "ITOC");
            TreeMap<Integer, int[]> sizes = new TreeMap<>(); // id -> {stored, extract}
            for (String part : new String[]{"DataL", "DataH"}) {
                byte[] raw = itoc.getData(0, part);
                if (raw == null) continue;
                UtfTable t = UtfTable.parse(raw);
                for (int i = 0; i < t.rowCount(); i++) {
                    int id = (int) t.getLong(i, "ID", -1);
                    int size = (int) t.getLong(i, "FileSize", 0);
                    int extract = (int) t.getLong(i, "ExtractSize", size);
                    if (id >= 0) sizes.put(id, new int[]{size, extract});
                }
            }
            long cursor = contentOffset;
            for (java.util.Map.Entry<Integer, int[]> e : sizes.entrySet()) {
                int size = e.getValue()[0];
                list.add(new Entry("", String.format(Locale.ROOT, "%05d.bin", e.getKey()), cursor, size, e.getValue()[1], e.getKey()));
                cursor += size;
                if (size % align != 0) cursor += align - (size % align);
            }
        } else {
            throw new FormatException("CPK has neither TOC nor ITOC");
        }
        return list;
    }

    /** Reads (and decompresses if needed) an entry. */
    public byte[] read(Entry e) throws IOException {
        if (e.storedSize == 0) return new byte[0];
        byte[] raw = readAt(e.offset, e.storedSize);
        if (!CriLayla.isCompressed(raw)) return raw;
        int stored = CriLayla.storedSize(raw);
        if (stored > raw.length) raw = readAt(e.offset, stored); // CRI sometimes records a short FileSize
        return CriLayla.decompress(raw);
    }

    /** Reads at most {@code max} bytes of the stored (possibly compressed) entry. */
    public byte[] readHead(Entry e, int max) throws IOException {
        return readAt(e.offset, Math.min(max, e.storedSize));
    }

    public Entry find(String path) {
        return byPath.get(path.toLowerCase(Locale.ROOT));
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
