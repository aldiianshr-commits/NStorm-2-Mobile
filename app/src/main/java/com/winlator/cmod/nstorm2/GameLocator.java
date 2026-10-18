package com.winlator.cmod.nstorm2;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Finds the game executable inside the user's game folder. The user may copy
 * either the contents of the Steam install directory straight into
 * /storage/emulated/0/nstorm2/ or the whole "NARUTO SHIPPUDEN Ultimate Ninja
 * STORM 2" folder, so a shallow breadth-first search is used.
 */
public final class GameLocator {
    private static final int MAX_DEPTH = 4;

    /** Lower-case name fragments of executables that are never the game itself. */
    private static final String[] IGNORED_EXE_PARTS = {
            "unins", "setup", "vcredist", "vc_redist", "dxsetup", "dxwebsetup", "directx",
            "crashreport", "crashhandler", "uploader", "dotnet", "physx", "oalinst"
    };

    private GameLocator() {}

    /** Returns NSUNS2.exe inside {@code gameDir} (case-insensitive), or null. */
    @Nullable
    public static File findGameExe(File gameDir) {
        for (File f : walk(gameDir)) {
            if (f.getName().equalsIgnoreCase(NStormPrefs.GAME_EXE)) return f;
        }
        return null;
    }

    /** Every launchable .exe under the folder (for "run another program"). */
    public static List<File> listExecutables(File gameDir) {
        List<File> result = new ArrayList<>();
        for (File f : walk(gameDir)) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".exe")) continue;
            result.add(f);
        }
        Collections.sort(result, (a, b) -> {
            boolean ia = isIgnorable(a), ib = isIgnorable(b);
            if (ia != ib) return ia ? 1 : -1;
            return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
        });
        return result;
    }

    public static boolean isIgnorable(File exe) {
        String n = exe.getName().toLowerCase(Locale.ROOT);
        for (String part : IGNORED_EXE_PARTS) if (n.contains(part)) return true;
        return false;
    }

    /** True when the folder holds the Steamworks API, i.e. the game expects the Steam client. */
    public static boolean usesSteamApi(File exeDir) {
        return exeDir != null && (new File(exeDir, "steam_api64.dll").isFile() || new File(exeDir, "steam_api.dll").isFile());
    }

    /**
     * Converts a file inside {@code driveRoot} into a Windows path on {@code driveLetter},
     * e.g. /storage/emulated/0/nstorm2/Game/NSUNS2.exe -> D:\Game\NSUNS2.exe
     */
    public static String toWindowsPath(File driveRoot, File file, char driveLetter) {
        String root = driveRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        String rel = path.startsWith(root) ? path.substring(root.length()) : "/" + file.getName();
        while (rel.startsWith("/")) rel = rel.substring(1);
        return driveLetter + ":\\" + rel.replace('/', '\\');
    }

    private static List<File> walk(File root) {
        List<File> out = new ArrayList<>();
        if (root == null || !root.isDirectory()) return out;
        Deque<File> dirs = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        dirs.add(root);
        depths.add(0);
        while (!dirs.isEmpty()) {
            File dir = dirs.poll();
            int depth = depths.poll();
            File[] children = dir.listFiles();
            if (children == null) continue;
            // files first so that a shallower NSUNS2.exe wins
            for (File c : children) if (c.isFile()) out.add(c);
            if (depth >= MAX_DEPTH) continue;
            for (File c : children) {
                if (!c.isDirectory()) continue;
                String n = c.getName();
                if (n.startsWith(".")) continue;
                // never descend into our own save folder
                if (depth == 0 && n.equalsIgnoreCase("saves")) continue;
                dirs.add(c);
                depths.add(depth + 1);
            }
        }
        return out;
    }
}
