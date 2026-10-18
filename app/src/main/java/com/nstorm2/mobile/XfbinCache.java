package com.nstorm2.mobile;

import com.nstorm2.engine.vfs.GameLibrary;
import com.nstorm2.engine.xfbin.Xfbin;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the last few parsed XFBIN files so moving between screens does not re-read the archive. */
public final class XfbinCache {
    private static final int CAPACITY = 3;
    private static final Map<String, Xfbin> cache = new LinkedHashMap<String, Xfbin>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Xfbin> eldest) {
            return size() > CAPACITY;
        }
    };

    private XfbinCache() {}

    /** Loads (blocking) the XFBIN for an asset key; call off the main thread. */
    public static Xfbin load(NStormApp app, String key) throws IOException {
        synchronized (cache) {
            Xfbin x = cache.get(key);
            if (x != null) return x;
        }
        GameLibrary lib = app.library();
        if (lib == null) throw new IOException("Data game belum dipindai");
        GameLibrary.Asset a = lib.findByKey(key);
        if (a == null) throw new IOException("Aset tidak ditemukan: " + key);
        byte[] data = lib.read(a);
        if (!Xfbin.isXfbin(data)) throw new IOException("Bukan file XFBIN (magic: " + magic(data) + ")");
        Xfbin x = Xfbin.parse(data);
        synchronized (cache) {
            cache.put(key, x);
        }
        return x;
    }

    private static String magic(byte[] d) {
        if (d.length < 4) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            char c = (char) (d[i] & 0xFF);
            sb.append(c >= 32 && c < 127 ? c : '.');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
