package com.nstorm2.mobile;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.nstorm2.engine.vfs.GameLibrary;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide state: storage locations, settings and the scanned game library.
 * NStorm 2 Mobile is a native Android app: it reads the original Storm 2 PC data
 * files directly, nothing is emulated or translated.
 */
public class NStormApp extends Application {
    public static final String KEY_GAME_DIR = "game_dir";

    private static NStormApp instance;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile GameLibrary library;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PreferenceManager.setDefaultValues(this, R.xml.settings, false);
        cleanupLegacyRuntime();
    }

    public static NStormApp get() {
        return instance;
    }

    public static NStormApp of(Context c) {
        return (NStormApp) c.getApplicationContext();
    }

    public SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    /** /storage/emulated/0/nstorm2 */
    public static File defaultGameDir() {
        return new File(Environment.getExternalStorageDirectory(), "nstorm2");
    }

    public File gameDir() {
        String p = prefs().getString(KEY_GAME_DIR, null);
        return p == null || p.isEmpty() ? defaultGameDir() : new File(p);
    }

    public void setGameDir(File dir) {
        prefs().edit().putString(KEY_GAME_DIR, dir.getAbsolutePath()).apply();
        closeLibrary();
    }

    /** /storage/emulated/0/nstorm2/saves: engine data (settings backup, model index, exports). */
    public File savesDir() {
        File d = new File(defaultGameDir(), "saves");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public File exportDir() {
        File d = new File(savesDir(), "export");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public ExecutorService io() {
        return io;
    }

    public void post(Runnable r) {
        main.post(r);
    }

    public GameLibrary library() {
        return library;
    }

    public synchronized GameLibrary scanLibrary(GameLibrary.Progress progress) {
        closeLibrary();
        XfbinCache.clear();
        library = GameLibrary.scan(gameDir(), progress);
        return library;
    }

    public synchronized void closeLibrary() {
        if (library != null) {
            library.close();
            library = null;
        }
    }

    /**
     * v1.x of this app bundled a Wine/Box64 runtime (~2 GB unpacked in files/imagefs).
     * The native build no longer uses it, so reclaim the space once.
     */
    private void cleanupLegacyRuntime() {
        File legacy = new File(getFilesDir(), "imagefs");
        if (!legacy.exists()) return;
        io.execute(() -> deleteTree(legacy));
    }

    static void deleteTree(File f) {
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
