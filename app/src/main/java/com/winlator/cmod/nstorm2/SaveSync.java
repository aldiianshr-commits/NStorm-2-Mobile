package com.winlator.cmod.nstorm2;

import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the game's save data in /storage/emulated/0/nstorm2/saves/ instead of
 * the app-private Wine prefix, so saves survive reinstalling the app, can be
 * backed up with any file manager, and are shared by both engine containers.
 *
 * The PC release stores progress through Steam (Steam Cloud,
 * "Steam\\userdata\\&lt;id&gt;\\543870\\remote"); configuration and anything a
 * runtime writes to the user profile live under Documents / AppData\\Roaming.
 * Those locations are mirrored file-by-file:
 *   - before launch: saves/ -> prefix  (only files that are newer or missing)
 *   - after exit:    prefix -> saves/  (only files that are newer or missing;
 *                    a file that would be overwritten is first copied to
 *                    saves/.backup/&lt;timestamp&gt;/)
 * Nothing is ever deleted on either side.
 */
public final class SaveSync {
    private static final String TAG = "NStormSaveSync";
    /** FAT/FUSE timestamps have 2 s resolution. */
    private static final long MTIME_TOLERANCE_MS = 2000;
    private static final int MAX_BACKUPS = 10;

    private static final class Rule {
        final String prefixPath;   // relative to the container root
        final String savesPath;    // relative to saves/
        final boolean steamOnly;   // only <steamid>/543870/** below the folder

        Rule(String prefixPath, String savesPath, boolean steamOnly) {
            this.prefixPath = prefixPath;
            this.savesPath = savesPath;
            this.steamOnly = steamOnly;
        }
    }

    private static final Rule[] RULES = {
            new Rule(".wine/drive_c/Program Files (x86)/Steam/userdata", "steam_userdata", true),
            new Rule(".wine/drive_c/users/" + ImageFs.USER + "/Documents", "Documents", false),
            new Rule(".wine/drive_c/users/" + ImageFs.USER + "/AppData/Roaming", "AppData/Roaming", false),
    };

    public static final class Result {
        public int copied;
        public int failed;
        public File backupDir;
    }

    private SaveSync() {}

    /** saves/ -> container. Call right before starting the game. */
    public static Result syncIn(Container container) {
        Result r = new Result();
        File saves = NStormPrefs.savesDir();
        ensureSavesDir(saves);
        for (Rule rule : RULES) {
            File src = new File(saves, rule.savesPath);
            File dst = new File(container.getRootDir(), rule.prefixPath);
            copyNewer(src, dst, "", rule, r, null);
        }
        Log.i(TAG, "syncIn container " + container.id + ": " + r.copied + " copied, " + r.failed + " failed");
        return r;
    }

    /** container -> saves/. Call after the game exited. */
    public static Result syncOut(Container container) {
        Result r = new Result();
        File saves = NStormPrefs.savesDir();
        ensureSavesDir(saves);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File backupRoot = new File(new File(saves, ".backup"), stamp);
        for (Rule rule : RULES) {
            File src = new File(container.getRootDir(), rule.prefixPath);
            File dst = new File(saves, rule.savesPath);
            copyNewer(src, dst, "", rule, r, new File(backupRoot, rule.savesPath));
        }
        if (backupRoot.isDirectory()) r.backupDir = backupRoot;
        pruneBackups(new File(saves, ".backup"));
        Log.i(TAG, "syncOut container " + container.id + ": " + r.copied + " copied, " + r.failed + " failed");
        return r;
    }

    private static void ensureSavesDir(File saves) {
        if (!saves.isDirectory()) saves.mkdirs();
        File readme = new File(saves, "BACA-SAYA.txt");
        if (!readme.exists()) {
            String text = "Folder save NStorm 2 Mobile\n"
                    + "===========================\n"
                    + "steam_userdata/  : save Steam (userdata\\<id>\\543870\\remote)\n"
                    + "Documents/       : folder Documents pengguna Wine\n"
                    + "AppData/Roaming/ : folder AppData\\Roaming pengguna Wine\n"
                    + ".backup/         : salinan otomatis sebelum file save ditimpa (10 terakhir)\n\n"
                    + "Salin folder ini untuk mencadangkan progres game.\n";
            try (OutputStream out = new FileOutputStream(readme)) {
                out.write(text.getBytes("UTF-8"));
            } catch (IOException ignored) {}
        }
    }

    private static boolean accepted(Rule rule, String rel) {
        if (!rule.steamOnly) return true;
        // rel is "<steamid>" or "<steamid>/543870[/...]"
        String[] parts = rel.split("/");
        if (parts.length == 1) return true; // the <steamid> directory itself
        return NStormPrefs.STEAM_APP_ID.equals(parts[1]);
    }

    private static void copyNewer(File src, File dst, String rel, Rule rule, Result r, File backupDir) {
        if (!src.exists()) return;
        if (src.isDirectory()) {
            String[] names = src.list();
            if (names == null) return;
            Arrays.sort(names);
            for (String name : names) {
                String childRel = rel.isEmpty() ? name : rel + "/" + name;
                if (!accepted(rule, childRel)) continue;
                copyNewer(new File(src, name), new File(dst, name), childRel, rule, r,
                        backupDir == null ? null : new File(backupDir, name));
            }
            return;
        }
        if (!src.isFile()) return;

        long srcTime = src.lastModified();
        if (dst.isFile()) {
            if (srcTime <= dst.lastModified() + MTIME_TOLERANCE_MS && src.length() == dst.length()) return;
            if (srcTime + MTIME_TOLERANCE_MS < dst.lastModified()) return; // destination is newer
            if (backupDir != null) {
                try {
                    copyFile(dst, backupDir);
                } catch (IOException e) {
                    Log.w(TAG, "backup failed for " + dst, e);
                }
            }
        }
        try {
            copyFile(src, dst);
            r.copied++;
        } catch (IOException e) {
            r.failed++;
            Log.e(TAG, "copy failed " + src + " -> " + dst, e);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(dst.getPath() + ".nstorm-tmp");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("cannot replace " + dst);
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("cannot rename to " + dst);
        }
        dst.setLastModified(src.lastModified());
    }

    private static void pruneBackups(File backupRoot) {
        File[] dirs = backupRoot.listFiles(File::isDirectory);
        if (dirs == null || dirs.length <= MAX_BACKUPS) return;
        List<File> list = new ArrayList<>(Arrays.asList(dirs));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < list.size() - MAX_BACKUPS; i++) deleteRecursive(list.get(i));
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
