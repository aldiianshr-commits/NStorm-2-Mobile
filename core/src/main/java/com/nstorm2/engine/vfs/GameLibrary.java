package com.nstorm2.engine.vfs;

import com.nstorm2.engine.cpk.CpkArchive;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Indexes the user's Storm 2 PC folder: every CPK archive (detected by its "CPK " magic,
 * whatever its extension) and loose XFBIN files. Nothing is copied or modified.
 */
public final class GameLibrary implements Closeable {

    /** A file inside the game data, either loose or inside a CPK. */
    public static final class Asset {
        public final File source;
        public final String entryPath;   // null for loose files
        public final String displayPath;
        public final long size;

        Asset(File source, String entryPath, String displayPath, long size) {
            this.source = source;
            this.entryPath = entryPath;
            this.displayPath = displayPath;
            this.size = size;
        }

        public String name() {
            int s = displayPath.lastIndexOf('/');
            return s < 0 ? displayPath : displayPath.substring(s + 1);
        }

        public String extension() {
            String n = name();
            int d = n.lastIndexOf('.');
            return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.ROOT);
        }

        public boolean isXfbin() {
            return "xfbin".equals(extension());
        }

        /** Stable key, e.g. "data/disc.cpk::chara/1nrt/1nrtbod1.xfbin". */
        public String key(File root) {
            String rel = relative(root, source);
            return entryPath == null ? rel : rel + "::" + entryPath;
        }
    }

    public static final class Archive {
        public final File file;
        public final String relativePath;
        public final int entryCount;
        public final String error;

        Archive(File file, String relativePath, int entryCount, String error) {
            this.file = file;
            this.relativePath = relativePath;
            this.entryCount = entryCount;
            this.error = error;
        }
    }

    public interface Progress {
        void onProgress(String message);
    }

    private final File root;
    private final List<Archive> archives = new ArrayList<>();
    private final List<Asset> assets = new ArrayList<>();
    private final Map<File, CpkArchive> open = new HashMap<>();

    private GameLibrary(File root) {
        this.root = root;
    }

    public static GameLibrary scan(File root, Progress progress) {
        GameLibrary lib = new GameLibrary(root);
        lib.walk(root, 0, progress);
        Collections.sort(lib.assets, (a, b) -> a.displayPath.compareToIgnoreCase(b.displayPath));
        return lib;
    }

    public File root() { return root; }
    public List<Archive> archives() { return Collections.unmodifiableList(archives); }
    public List<Asset> assets() { return Collections.unmodifiableList(assets); }

    static String relative(File root, File f) {
        String r = root.getAbsolutePath(), p = f.getAbsolutePath();
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1).replace(File.separatorChar, '/') : f.getName();
    }

    private void walk(File dir, int depth, Progress progress) {
        File[] children = dir.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : children) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".") || (depth == 0 && (n.equalsIgnoreCase("saves") || n.equalsIgnoreCase("export")))) continue;
                if (depth < 8) walk(f, depth + 1, progress);
                continue;
            }
            String lower = n.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xfbin")) {
                assets.add(new Asset(f, null, relative(root, f), f.length()));
            } else if (f.length() > 64 && CpkArchive.looksLikeCpk(f)) {
                String rel = relative(root, f);
                if (progress != null) progress.onProgress(rel);
                try (CpkArchive cpk = new CpkArchive(f)) {
                    for (CpkArchive.Entry e : cpk.entries()) {
                        assets.add(new Asset(f, e.path(), rel + "/" + e.path(), e.extractSize > 0 ? e.extractSize : e.storedSize));
                    }
                    archives.add(new Archive(f, rel, cpk.entries().size(), null));
                } catch (Exception e) {
                    archives.add(new Archive(f, rel, 0, e.getMessage()));
                }
            }
        }
    }

    private synchronized CpkArchive archive(File f) throws IOException {
        CpkArchive a = open.get(f);
        if (a == null) {
            a = new CpkArchive(f);
            open.put(f, a);
        }
        return a;
    }

    public byte[] read(Asset asset) throws IOException {
        if (asset.entryPath == null) return Files.readAllBytes(asset.source.toPath());
        CpkArchive a = archive(asset.source);
        CpkArchive.Entry e = a.find(asset.entryPath);
        if (e == null) throw new IOException("entry not found: " + asset.entryPath);
        return a.read(e);
    }

    public Asset findByKey(String key) {
        for (Asset a : assets) if (a.key(root).equals(key)) return a;
        return null;
    }

    @Override
    public synchronized void close() {
        for (CpkArchive a : open.values()) {
            try {
                a.close();
            } catch (IOException ignored) {}
        }
        open.clear();
    }
}
