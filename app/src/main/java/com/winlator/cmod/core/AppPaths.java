package com.winlator.cmod.core;

import com.winlator.cmod.BuildConfig;

/**
 * Absolute paths that upstream Winlator Cmod hardcoded with its own package
 * name ("/data/data/com.winlator.cmod/..."). NStorm 2 Mobile installs under a
 * different application id, so every such path is derived from it here.
 */
public final class AppPaths {
    private AppPaths() {}

    /** e.g. /data/data/com.nstorm2mobile */
    public static final String DATA_DIR = "/data/data/" + BuildConfig.APPLICATION_ID;
    /** e.g. /data/data/com.nstorm2mobile/files/imagefs */
    public static final String IMAGEFS_DIR = DATA_DIR + "/files/imagefs";
    /** Private drive exposed to Wine as E: by default. */
    public static final String STORAGE_DIR = DATA_DIR + "/storage";

    /** Shared-memory file used by evshim for gamepad player {@code index} (0 = P1). */
    public static String gamepadMemPath(int index) {
        return IMAGEFS_DIR + "/tmp/gamepad" + (index == 0 ? "" : String.valueOf(index)) + ".mem";
    }
}
